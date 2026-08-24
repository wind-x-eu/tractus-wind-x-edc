/********************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ********************************************************************************/

package org.eclipse.tractusx.edc.windx.mms.client;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MMS TCP-Proxy mit kontinuierlicher Datenabfrage.
 * 
 * WICHTIG: Verwendet MMSClient (wie die funktionierende MMSDataPlaneExtension),
 * NICHT direkt AasIecConnector2. Der MMSClient startet einen Listener-Thread,
 * der spontane Server-Daten konsumiert – ohne diesen Thread können Read-Responses
 * durch spontane Reports im Socket-Buffer korrumpiert werden (hardware-fault).
 *
 * Der Proxy agiert als vollständiger MMS-Server gegenüber Clients:
 *   1. COTP Connection Confirm auf COTP Connect Request
 *   2. MMS Associate Response auf MMS Associate Request
 *   3. MMS Read Responses mit gecachten Polling-Daten
 *
 * MMS-Server ◄──Polling── MMSTcpProxy (MMS-Server-Rolle) ◄──MMS── Consumer (MMS-Client)
 */
public class MMSTcpProxy {

    private static final long DEFAULT_POLLING_INTERVAL_MS = 5000;

    private final String mmsHost;
    private final int mmsPort;
    private final String logicalDevice;
    private final String sensors;
    private final long pollingIntervalMs;
    private final ServerSocket serverSocket;
    private volatile boolean running;

    // Cache für die zuletzt abgefragten Daten (vollständiger TPKT-Frame)
    private volatile byte[] cachedMmsResponse = null;
    private volatile long lastUpdateTimestamp = 0;

    // Liste der verbundenen Clients
    private final CopyOnWriteArrayList<Socket> connectedClients = new CopyOnWriteArrayList<>();

    // Scheduler für kontinuierliche Abfragen
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    // MMSClient – identisch zur funktionierenden MMSDataPlaneExtension
    private MMSClient mmsClient;

    // Inkrementierende InvokeId (wie bei MMSClientManager)
    private final AtomicInteger invokeIdCounter = new AtomicInteger(0x20);

    MMSTcpProxy(String mmsHost, int mmsPort, int serverPort, String logicalDevice, String sensors) throws IOException {
        this(mmsHost, mmsPort, serverPort, logicalDevice, sensors, DEFAULT_POLLING_INTERVAL_MS);
    }

    public MMSTcpProxy(String mmsHost, int mmsPort, int serverPort, String logicalDevice, String sensors, long pollingIntervalMs) throws IOException {
        this.mmsHost = mmsHost;
        this.mmsPort = mmsPort;
        this.logicalDevice = logicalDevice;
        this.sensors = sensors;
        this.pollingIntervalMs = pollingIntervalMs;
        this.serverSocket = new ServerSocket(serverPort);
    }

    public int getLocalPort() {
        return serverSocket.getLocalPort();
    }

    String getLogicalDevice() {
        return logicalDevice;
    }

    String getSensors() {
        return sensors;
    }

    public void start() throws IOException {
        running = true;

        // =====================================================================
        // Detailliertes Logging der Konfiguration
        // =====================================================================
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  MMS Light Proxy - Startup                          ║");
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.println("║  MMS Server Host:     " + mmsHost);
        System.out.println("║  MMS Server Port:     " + mmsPort);
        System.out.println("║  Logical Device:      " + logicalDevice);
        System.out.println("║  Sensors:             " + sensors);
        System.out.println("║  Polling Interval:    " + pollingIntervalMs + " ms");
        System.out.println("║  Proxy Server Port:   " + serverSocket.getLocalPort());
        System.out.println("╚══════════════════════════════════════════════════════╝");

        // =====================================================================
        // 1. MMS-Verbindung über MMSClient aufbauen
        //    (identisch zu MMSDataPlaneExtension → MMSClientManager → MMSClient)
        //    MMSClient.connect() macht intern:
        //      - AasIecConnector2.connect()     → COTP Handshake
        //      - AasIecConnector2.associate()    → MMS Associate
        //      - startMessageListener()          → Listener-Thread für spontane Daten
        // =====================================================================
        System.out.println("[MMSTcpProxy] Schritt 1: Erstelle MMSClient für " + mmsHost + ":" + mmsPort);
        mmsClient = new MMSClient(mmsHost, mmsPort);

        System.out.println("[MMSTcpProxy] Schritt 2: Verbinde zum MMS-Server (connect + associate + listener)...");
        mmsClient.connect();
        System.out.println("[MMSTcpProxy] ✓ MMSClient verbunden (inkl. Listener-Thread)");

        // =====================================================================
        // 2. Server-Modell abfragen (GetNameList)
        //    MMSDataPlaneExtension macht das auch: client.readServerModel()
        // =====================================================================
        System.out.println("[MMSTcpProxy] Schritt 3: Lese Server-Modell (GetNameList)...");
        mmsClient.readServerModel();
        System.out.println("[MMSTcpProxy] ✓ Server-Modell gelesen");

        // =====================================================================
        // 3. Reports abonnieren (wie MMSDataPlaneExtension: subscribeToReports)
        // =====================================================================
        System.out.println("[MMSTcpProxy] Schritt 4: Abonniere Reports für '" + logicalDevice + "'...");
        mmsClient.subscribeToReports(logicalDevice);
        System.out.println("[MMSTcpProxy] ✓ Reports abonniert");

        // =====================================================================
        // 4. Validierung: Ersten Read-Request testen
        // =====================================================================
        System.out.println("[MMSTcpProxy] Schritt 5: Validierung - Lese " + logicalDevice + "/" + sensors + "...");
        int testInvokeId = nextInvokeId();
        try {
            byte[] rawReq = AasIecConnector2.buildMmsReadRequest3(logicalDevice, sensors, testInvokeId);
            byte[] rawResp = mmsClient.getConnector().sendRequest("read", rawReq);
            String value = mmsClient.decodeDataAndReturnValue(rawResp);
            cachedMmsResponse = rawResp;
            lastUpdateTimestamp = System.currentTimeMillis();
            System.out.println("[MMSTcpProxy] ✓ Validierung: " + logicalDevice + "/" + sensors + " = " + value);
        } catch (Exception e) {
            System.err.println("[MMSTcpProxy] ⚠ Validierung fehlgeschlagen: " + e.getMessage());
        }

        System.out.println("[MMSTcpProxy] Schritt 6: Starte Accept-Loop und Polling...");

        // 5. Starte Accept-Loop
        Thread acceptThread = new Thread(this::acceptLoop, "mms-light-proxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        // 6. Starte Polling
        scheduler.scheduleAtFixedRate(this::pollMmsData, pollingIntervalMs, pollingIntervalMs, TimeUnit.MILLISECONDS);

        System.out.println("[MMSTcpProxy] ✓ Proxy vollständig gestartet: Polling alle " + pollingIntervalMs + "ms");
    }

    public void stop() {
        running = false;

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // MMS-Verbindung trennen (über MMSClient – sauber mit Listener-Thread)
        if (mmsClient != null) {
            mmsClient.disconnect();
            System.out.println("[MMSTcpProxy] MMS Verbindung getrennt: " + mmsHost + ":" + mmsPort);
        }

        // Client-Handlers schließen
        for (ClientHandler h : clientHandlers) {
            h.close();
        }
        clientHandlers.clear();

        for (Socket s : connectedClients) {
            try { s.close(); } catch (IOException ignored) {}
        }
        connectedClients.clear();

        try { serverSocket.close(); } catch (IOException ignored) {}
        System.out.println("[MMSTcpProxy] Proxy gestoppt");
    }

    // =========================================================================
    // Accept-Loop
    // =========================================================================

    private void acceptLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                connectedClients.add(clientSocket);
                System.out.println("[MMSTcpProxy] Client verbunden: " + clientSocket.getRemoteSocketAddress()
                        + " (Total: " + connectedClients.size() + ")");

                Thread handler = new Thread(() -> handleClient(clientSocket), "mms-light-client-handler");
                handler.setDaemon(true);
                handler.start();

            } catch (IOException e) {
                if (running) {
                    System.err.println("[MMSTcpProxy] Accept-Fehler: " + e.getMessage());
                }
            }
        }
    }

    // =========================================================================
    // Client-Handler: Vollständiges MMS-Protokoll (Server-Rolle)
    // =========================================================================

    /**
     * Repräsentiert eine verbundene Client-Verbindung mit Output-Stream für Push.
     */
    private static class ClientHandler {
        final Socket socket;
        final OutputStream out;
        
        ClientHandler(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }
        
        boolean sendData(byte[] data) {
            try {
                out.write(data);
                out.flush();
                return true;
            } catch (IOException e) {
                return false;
            }
        }
        
        void close() {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
    
    // Aktive Client-Handler für Push
    private final CopyOnWriteArrayList<ClientHandler> clientHandlers = new CopyOnWriteArrayList<>();

    private void handleClient(Socket clientSocket) {
        try {
            InputStream in = clientSocket.getInputStream();
            OutputStream out = clientSocket.getOutputStream();

            // Phase 1: COTP Handshake
            byte[] cotpRequest = readTpktFrame(in);
            if (!isCotpConnectRequest(cotpRequest)) {
                System.err.println("[MMSTcpProxy] Kein gültiger COTP Connect Request");
                clientSocket.close();
                return;
            }
            byte[] cotpConfirm = buildCotpConnectionConfirm(cotpRequest);
            out.write(cotpConfirm);
            out.flush();
            System.out.println("[MMSTcpProxy] COTP Handshake OK: " + clientSocket.getRemoteSocketAddress());

            // Phase 2: MMS Associate
            byte[] assocRequest = readTpktFrame(in);
            byte[] assocResponse = buildMmsAssociateResponse();
            out.write(assocResponse);
            out.flush();
            System.out.println("[MMSTcpProxy] MMS Associate OK: " + clientSocket.getRemoteSocketAddress());

            // Phase 3: Client als Push-Empfänger registrieren
            ClientHandler handler = new ClientHandler(clientSocket, out);
            clientHandlers.add(handler);
            System.out.println("[MMSTcpProxy] Client registriert für Push-Nachrichten (Total: " + clientHandlers.size() + ")");

            // Sofort gecachte Daten senden, falls vorhanden
            byte[] cached = cachedMmsResponse;
            if (cached != null) {
                handler.sendData(repackForClientPush(cached));
                System.out.println("[MMSTcpProxy] Gecachte Daten an neuen Client gesendet");
            }

            // Phase 4: Hält die Verbindung offen und liest optionale Requests
            // Der MMSRequestMessages-Client sendet KEINE Requests, wartet nur passiv.
            // Andere Clients könnten Read-Requests senden.
            while (running && !clientSocket.isClosed()) {
                // Non-blocking check: Gibt es Daten vom Client?
                if (in.available() > 0) {
                    byte[] request = readTpktFrame(in);
                    if (request == null) break; // Client hat Verbindung geschlossen

                    System.out.println("[MMSTcpProxy] Client-Request empfangen: " + bytesToHex(request));

                    // Auf Read-Request antworten
                    int clientInvokeId = extractInvokeId(request);
                    byte[] response = cachedMmsResponse;
                    if (response != null) {
                        byte[] adjustedResponse = adjustInvokeId(response, clientInvokeId);
                        byte[] clientResponse = repackForClientPush(adjustedResponse);
                        out.write(clientResponse);
                        out.flush();
                    }
                } else {
                    // Keine Daten vom Client – kurz warten
                    Thread.sleep(100);
                }
            }

        } catch (IOException e) {
            if (running) {
                System.err.println("[MMSTcpProxy] Client-Handler Fehler: " + e.getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // Client aus Push-Liste entfernen
            clientHandlers.removeIf(h -> h.socket == clientSocket);
            connectedClients.remove(clientSocket);
            try { clientSocket.close(); } catch (IOException ignored) {}
            System.out.println("[MMSTcpProxy] Client getrennt. Push-Empfänger: " + clientHandlers.size());
        }
    }

    // =========================================================================
    // Polling via MMSClient + Push an alle verbundenen Clients
    // =========================================================================

    private void pollMmsData() {
        if (!mmsClient.isConnected()) {
            System.err.println("[MMSTcpProxy] Polling übersprungen – nicht verbunden");
            tryReconnect();
            return;
        }

        int invokeId = nextInvokeId();
        try {
            // Ein einzelner Read-Request
            byte[] rawRequest = AasIecConnector2.buildMmsReadRequest3(logicalDevice, sensors, invokeId);
            byte[] rawResponse = mmsClient.getConnector().sendRequest("read", rawRequest);

            // Wert dekodieren
            String value = mmsClient.decodeDataAndReturnValue(rawResponse);

            // Cache aktualisieren
            cachedMmsResponse = rawResponse;
            lastUpdateTimestamp = System.currentTimeMillis();

            System.out.println("[MMSTcpProxy] Polling OK: " + logicalDevice + "/" + sensors
                    + " = " + value + " (invokeId=0x" + Integer.toHexString(invokeId)
                    + ", clients=" + clientHandlers.size() + ")");

            // ============================================================
            // PUSH: Daten an ALLE verbundenen Clients senden
            // Die Daten werden ohne Session-Header verpackt,
            // damit der MMSClient-Listener sie korrekt parsen kann.
            // ============================================================
            if (!clientHandlers.isEmpty()) {
                byte[] clientData = repackForClientPush(rawResponse);
                for (ClientHandler handler : clientHandlers) {
                    if (!handler.sendData(clientData)) {
                        // Client nicht mehr erreichbar
                        clientHandlers.remove(handler);
                        handler.close();
                        System.out.println("[MMSTcpProxy] Client entfernt (nicht erreichbar). Verbleibend: " + clientHandlers.size());
                    }
                }
            }

        } catch (IOException e) {
            System.err.println("[MMSTcpProxy] Polling Fehler: " + e.getMessage());
            tryReconnect();
        }
    }

    private void tryReconnect() {
        try {
            System.out.println("[MMSTcpProxy] Reconnect wird versucht...");
            if (mmsClient != null) {
                mmsClient.disconnect();
            }
            mmsClient = new MMSClient(mmsHost, mmsPort);
            mmsClient.connect();
            mmsClient.readServerModel();
            mmsClient.subscribeToReports(logicalDevice);
            System.out.println("[MMSTcpProxy] Reconnect erfolgreich");
        } catch (Exception e) {
            System.err.println("[MMSTcpProxy] Reconnect fehlgeschlagen: " + e.getMessage());
        }
    }

    // =========================================================================
    // ISO/RFC1006 Protokoll-Hilfsmethoden
    // =========================================================================

    private static byte[] readTpktFrame(InputStream in) throws IOException {
        byte[] header = new byte[4];
        int read = 0;
        while (read < 4) {
            int r = in.read(header, read, 4 - read);
            if (r == -1) return null;
            read += r;
        }
        int tpktLen = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        byte[] frame = new byte[tpktLen];
        System.arraycopy(header, 0, frame, 0, 4);
        int remaining = tpktLen - 4;
        int offset = 4;
        while (remaining > 0) {
            int r = in.read(frame, offset, remaining);
            if (r == -1) throw new IOException("EOF während TPKT-Payload-Lesen");
            offset += r;
            remaining -= r;
        }
        return frame;
    }

    private static boolean isCotpConnectRequest(byte[] data) {
        return data != null && data.length >= 7
                && (data[0] & 0xFF) == 0x03
                && (data[5] & 0xFF) == 0xE0;
    }

    private static byte[] buildCotpConnectionConfirm(byte[] connectRequest) {
        int clientSrcRef1 = connectRequest.length > 6 ? (connectRequest[6] & 0xFF) : 0x00;
        int clientSrcRef2 = connectRequest.length > 7 ? (connectRequest[7] & 0xFF) : 0x01;
        return new byte[] {
            (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x0B,
            (byte) 0x06, (byte) 0xD0,
            (byte) clientSrcRef1, (byte) clientSrcRef2,
            (byte) 0x00, (byte) 0x01, (byte) 0x00
        };
    }

    private static byte[] buildMmsAssociateResponse() {
        return hexToBytes(
            "0300008f02f0800e8605061301001601021402000234020001c1743172" +
            "a003800101a26b830400000001a512300780010081025101300780010081025101" +
            "614f304d020101a0486146a107060528ca220203a203020100a305a103020100" +
            "be2f282d020103a028a926800300fde8810105820105830105a416800101810305f100" +
            "820c03ee1c00000002000040ed18"
        );
    }

    private static int extractInvokeId(byte[] frame) {
        for (int i = 7; i < frame.length - 2; i++) {
            if ((frame[i] & 0xFF) == 0x02 && (frame[i + 1] & 0xFF) == 0x01) {
                return frame[i + 2] & 0xFF;
            }
        }
        return 0x03;
    }

    private static byte[] adjustInvokeId(byte[] response, int newInvokeId) {
        byte[] adjusted = Arrays.copyOf(response, response.length);
        for (int i = 7; i < adjusted.length - 2; i++) {
            if ((adjusted[i] & 0xFF) == 0x02 && (adjusted[i + 1] & 0xFF) == 0x01) {
                adjusted[i + 2] = (byte) newInvokeId;
                break;
            }
        }
        return adjusted;
    }

    // =========================================================================
    // Utility
    // =========================================================================

    /**
     * Baut einen MMS InformationReport (PDU-Typ 0xA3) aus der gepolten MMS Read Response.
     * 
     * Der Client (MMSClient.readMMSMessage → parseMMSInformationReport) erwartet:
     *   TPKT(4) + payload, wobei payload[0] = 0xA3 (InformationReport)
     * 
     * InformationReport-Struktur:
     *   A3 <len> {
     *     A0 <len> {                     ← variableAccessSpecification
     *       A0 <len> {                   ← listOfVariable  
     *         1A <len> "domain/item"     ← VisibleString (→ wird Report-ID)
     *       }
     *     }
     *     A1 <len> {                     ← listOfData
     *       <Datenwerte aus der Response>
     *     }
     *   }
     */
    private byte[] repackForClientPush(byte[] rawTpktResponse) {
        if (rawTpktResponse == null || rawTpktResponse.length < 11) {
            return rawTpktResponse;
        }
        
        // 1. Extrahiere die Datenwerte aus der Read-Response
        //    Suche das innerste a1-Tag (listOfAccessResult) in der Response
        //    Response-Struktur: ...a4 XX a1 YY <data>...
        byte[] dataContent = null;
        for (int i = rawTpktResponse.length - 3; i >= 7; i--) {
            if ((rawTpktResponse[i] & 0xFF) == 0xA1) {
                int contentLen = rawTpktResponse[i + 1] & 0xFF;
                if (i + 2 + contentLen <= rawTpktResponse.length) {
                    dataContent = new byte[contentLen];
                    System.arraycopy(rawTpktResponse, i + 2, dataContent, 0, contentLen);
                    break;
                }
            }
        }
        
        if (dataContent == null) {
            // Fallback: verwende einen Platzhalter-Wert
            dataContent = new byte[] { (byte) 0x06, 0x01, 0x00 }; // integer 0
        }
        
        // MMS context-specific Tags auf universelle Tags umschreiben,
        // die der Client's parseDataItems versteht:
        //   MMS 0x83 (boolean)       → Client 0x03 (Boolean)
        //   MMS 0x85 (integer)       → Client 0x06 (Integer)
        //   MMS 0x86 (unsigned)      → Client 0x06 (Integer)
        //   MMS 0x87 (floating-point)→ Client 0x05 (FloatingPoint)
        //   MMS 0x89 (visible-string)→ Client 0x1A (VisibleString)
        //   MMS 0x8A (generalized-time) → Client 0x1A (VisibleString)
        //   MMS 0x84 (bit-string)    → Client 0x06 (Integer)
        dataContent = convertMmsTagsToUniversal(dataContent);
        
        // 2. Baue den VisibleString für die Report-ID: "WPPS/LCCS1$ST$Mod$stVal"
        String reportId = logicalDevice + "/" + sensors;
        byte[] reportIdBytes = reportId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        
        // VisibleString: 1A <len> <bytes>
        byte[] visibleString = new byte[2 + reportIdBytes.length];
        visibleString[0] = 0x1A;
        visibleString[1] = (byte) reportIdBytes.length;
        System.arraycopy(reportIdBytes, 0, visibleString, 2, reportIdBytes.length);
        
        // 3. Baue variableAccessSpecification: A0 { A0 { visibleString } }
        byte[] innerA0 = new byte[2 + visibleString.length];
        innerA0[0] = (byte) 0xA0;
        innerA0[1] = (byte) visibleString.length;
        System.arraycopy(visibleString, 0, innerA0, 2, visibleString.length);
        
        byte[] outerA0 = new byte[2 + innerA0.length];
        outerA0[0] = (byte) 0xA0;
        outerA0[1] = (byte) innerA0.length;
        System.arraycopy(innerA0, 0, outerA0, 2, innerA0.length);
        
        // 4. Baue listOfData: A1 { dataContent }
        byte[] a1Data = new byte[2 + dataContent.length];
        a1Data[0] = (byte) 0xA1;
        a1Data[1] = (byte) dataContent.length;
        System.arraycopy(dataContent, 0, a1Data, 2, dataContent.length);
        
        // 5. Baue InformationReport: A3 { outerA0 + a1Data }
        int reportContentLen = outerA0.length + a1Data.length;
        byte[] a3Report = new byte[2 + reportContentLen];
        a3Report[0] = (byte) 0xA3;
        a3Report[1] = (byte) reportContentLen;
        System.arraycopy(outerA0, 0, a3Report, 2, outerA0.length);
        System.arraycopy(a1Data, 0, a3Report, 2 + outerA0.length, a1Data.length);
        
        // 6. Wrap in TPKT
        int tpktLength = 4 + a3Report.length;
        byte[] result = new byte[tpktLength];
        result[0] = 0x03;
        result[1] = 0x00;
        result[2] = (byte) ((tpktLength >> 8) & 0xFF);
        result[3] = (byte) (tpktLength & 0xFF);
        System.arraycopy(a3Report, 0, result, 4, a3Report.length);
        
        return result;
    }

    private int nextInvokeId() {
        return invokeIdCounter.getAndUpdate(id -> id >= 0xFF ? 0x20 : id + 1);
    }

    /**
     * Konvertiert MMS-Daten in ein Format das der Client's parseDataItems versteht.
     * 
     * Probleme die gelöst werden:
     * 1. MMS Float (87 05 08 XXXXXXXX) → Client Float (05 04 XXXXXXXX) 
     *    - MMS hat 1 Extra-Byte (exponent-width) das entfernt werden muss
     * 2. MMS Strukturen (A2) und Arrays (A1) werden ausgepackt
     *    - Der Client kennt nur flache primitive Typen
     * 3. Tags werden auf Client-kompatible universelle Tags umgeschrieben
     */
    private static byte[] convertMmsTagsToUniversal(byte[] data) {
        // Sammle die konvertierten Bytes in einem ByteArrayOutputStream
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        convertMmsRecursive(data, 0, data.length, out);
        byte[] result = out.toByteArray();
        return result.length > 0 ? result : data; // Fallback auf Original
    }
    
    private static void convertMmsRecursive(byte[] data, int offset, int end, java.io.ByteArrayOutputStream out) {
        int i = offset;
        while (i < end - 1) {
            int tag = data[i] & 0xFF;
            int len = data[i + 1] & 0xFF;
            int contentStart = i + 2;
            
            // BER Long Form Length handling
            if (len >= 0x80) {
                int numLenBytes = len & 0x7F;
                len = 0;
                for (int j = 0; j < numLenBytes && contentStart + j < end; j++) {
                    len = (len << 8) | (data[contentStart + j] & 0xFF);
                }
                contentStart += numLenBytes;
            }
            
            if (contentStart + len > end) break;
            
            switch (tag) {
                case 0xA1: // MMS array – Inhalt rekursiv auspacken
                case 0xA2: // MMS structure – Inhalt rekursiv auspacken
                    convertMmsRecursive(data, contentStart, contentStart + len, out);
                    break;
                    
                case 0x87: // MMS floating-point: 87 05 08 XXXXXXXX
                    // Entferne das Exponent-Width-Byte (erstes Byte des Contents)
                    // Client erwartet: 05 04 XXXXXXXX (Tag=0x05, Len=4, pure IEEE 754)
                    if (len >= 5) {
                        out.write(0x05);            // Client FloatingPoint tag
                        out.write(len - 1);         // Länge ohne Exponent-Width-Byte
                        out.write(data, contentStart + 1, len - 1); // Skip exponent-width
                    } else if (len == 4) {
                        // Kein Exponent-Width-Byte vorhanden
                        out.write(0x05);
                        out.write(len);
                        out.write(data, contentStart, len);
                    }
                    break;
                    
                case 0x83: // MMS boolean → Client 0x03
                    out.write(0x03);
                    out.write(len);
                    out.write(data, contentStart, len);
                    break;
                    
                case 0x85: // MMS integer → Client 0x06
                case 0x86: // MMS unsigned → Client 0x06
                case 0x84: // MMS bit-string → Client 0x06
                    out.write(0x06);
                    out.write(len);
                    out.write(data, contentStart, len);
                    break;
                    
                case 0x89: // MMS visible-string → Client 0x1A
                case 0x8A: // MMS generalized-time → Client 0x1A
                case 0x8F: // MMS mms-string → Client 0x1A
                    out.write(0x1A);
                    out.write(len);
                    out.write(data, contentStart, len);
                    break;
                    
                case 0x91: // MMS UTC-time (8 bytes) → als Hex-String senden
                    // Konvertiere zu VisibleString mit dem Hex-Wert
                    String utcHex = bytesToHex(Arrays.copyOfRange(data, contentStart, contentStart + len));
                    byte[] utcBytes = utcHex.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    out.write(0x1A);
                    out.write(utcBytes.length);
                    out.write(utcBytes, 0, utcBytes.length);
                    break;
                    
                default:
                    // Unbekannte Tags: als Integer weiterreichen falls kurz genug
                    if (len <= 4 && len > 0) {
                        out.write(0x06);
                        out.write(len);
                        out.write(data, contentStart, len);
                    }
                    // Sonst überspringen
                    break;
            }
            
            i = contentStart + len;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        String clean = hex.replaceAll("\\s+", "").toLowerCase();
        byte[] result = new byte[clean.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    public byte[] getCachedResponse() {
        return cachedMmsResponse;
    }

    public long getLastUpdateTimestamp() {
        return lastUpdateTimestamp;
    }
}
