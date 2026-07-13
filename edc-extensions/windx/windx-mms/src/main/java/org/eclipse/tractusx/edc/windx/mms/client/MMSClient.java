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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MMS Client für IEC 61400-25 Kommunikation
 * Verwendet den AasIecConnector2 für die Verbindung und Kommunikation
 * Empfängt und verarbeitet MMS-Nachrichten von Windenergieanlagen
 */
public class MMSClient {
    
    private static final Logger logger = LoggerFactory.getLogger(MMSClient.class);
    
    private AasIecConnector2 connector;
    private String serverHost;
    private int serverPort;
    private int timeoutMs;
    private boolean connected;
    private Thread listenerThread;
    private List<MMSMessageListener> messageListeners;
    private List<String> logicalDevices;
    
    /**
     * Interface für MMS Message Listener
     */
    public interface MMSMessageListener {
        void onMessageReceived(MMSMessage message);
    }
    
    /**
     * Erstellt einen neuen MMS-Client
     * 
     * @param serverHost Hostname oder IP-Adresse des MMS-Servers
     * @param serverPort Port des MMS-Servers (Standard: 102)
     */
    public MMSClient(String serverHost, int serverPort) {
        this(serverHost, serverPort, 50000); // Default Timeout 50 Sekunden
    }
    
    /**
     * Erstellt einen neuen MMS-Client mit benutzerdefiniertem Timeout
     * 
     * @param serverHost Hostname oder IP-Adresse des MMS-Servers
     * @param serverPort Port des MMS-Servers (Standard: 102)
     * @param timeoutMs Timeout in Millisekunden
     */
    public MMSClient(String serverHost, int serverPort, int timeoutMs) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.timeoutMs = timeoutMs;
        this.connected = false;
        this.messageListeners = new CopyOnWriteArrayList<>();
        this.logicalDevices = new ArrayList<>();
        
        // Initialisiere AasIecConnector2
        this.connector = new AasIecConnector2(serverHost, serverPort, timeoutMs);
        
        // Füge Standard-Listener hinzu der Nachrichten auf der Konsole ausgibt
        this.messageListeners.add(new ConsoleMessageListener());
    }
    
    /**
     * Fügt einen Message Listener hinzu
     */
    public void addMessageListener(MMSMessageListener listener) {
        messageListeners.add(listener);
    }
    
    /**
     * Gibt den internen AasIecConnector2 zurück (für direkten Zugriff auf sendRequest).
     */
    public AasIecConnector2 getConnector() {
        return connector;
    }
    
    /**
     * Verbindet zum MMS-Server
     * 
     * @throws IOException bei Verbindungsproblemen
     */
    public void connect() throws IOException {
        logger.info("Verbinde zu MMS-Server {}:{}...", serverHost, serverPort);
        
        // Prüfe ob bereits verbunden
        if (connected) {
            logger.warn("Bereits verbunden - trenne zuerst die bestehende Verbindung");
            disconnect();
        }
        
        try {
            // 1. TCP + COTP Connect via AasIecConnector2
            logger.debug("Stelle TCP/COTP-Verbindung her...");
            connector.connect();
            logger.info("✓ TCP/COTP-Verbindung hergestellt");
            
            // 2. MMS Associate via AasIecConnector2
            logger.debug("Sende MMS Associate Request...");
            byte[] assocResponse = connector.associate(null);
            logger.info("✓ MMS Association erfolgreich");
            
            if (assocResponse != null && assocResponse.length > 0) {
                logger.debug("Associate Response erhalten: {} Bytes", assocResponse.length);
            }
            
            connected = true;
            System.out.println("✓ Verbunden mit " + serverHost + ":" + serverPort);
            System.out.println("  Status: MMS-Kommunikation aktiv");
            
            // Starte Listener-Thread für eingehende Nachrichten
            logger.debug("Starte Message Listener Thread...");
            startMessageListener();
            logger.info("✓ Message Listener aktiv");
            
        } catch (IOException e) {
            logger.error("Fehler beim Verbinden: {}", e.getMessage(), e);
            cleanupConnection();
            throw new IOException("Verbindungsfehler: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unerwarteter Fehler beim Verbinden: {}", e.getMessage(), e);
            cleanupConnection();
            throw new IOException("Unerwarteter Fehler: " + e.getMessage(), e);
        }
    }
    
    /**
     * Räumt Verbindungsressourcen auf (ohne connected-Flag zu ändern)
     */
    private void cleanupConnection() {
        try {
            if (connector != null) {
                connector.disconnect();
            }
        } catch (Exception e) {
            logger.debug("Fehler beim Bereinigen der Verbindung: {}", e.getMessage());
        }
    }
    
    /**
     * Trennt die Verbindung zum MMS-Server
     */
    public void disconnect() {
        disconnect("Manueller Disconnect");
    }
    
    /**
     * Trennt die Verbindung zum MMS-Server mit Grund
     * 
     * @param reason Grund für den Disconnect
     */
    private void disconnect(String reason) {
        if (connected) {
            logger.info("Trenne Verbindung zum MMS-Server. Grund: {}", reason);
            connected = false;
            
            // Stoppe Listener-Thread
            if (listenerThread != null && listenerThread.isAlive()) {
                logger.debug("Beende Listener-Thread...");
                listenerThread.interrupt();
                try {
                    listenerThread.join(2000); // Warte max. 2 Sekunden
                    if (listenerThread.isAlive()) {
                        logger.warn("Listener-Thread konnte nicht sauber beendet werden");
                    }
                } catch (InterruptedException e) {
                    logger.debug("Warten auf Listener-Thread unterbrochen");
                }
            }
            
            // Schließe Verbindung via AasIecConnector2
            cleanupConnection();
            
            logger.info("✓ Verbindung zum MMS-Server getrennt");
            System.out.println("✓ Verbindung getrennt - " + reason);
        } else {
            logger.debug("disconnect() aufgerufen, aber nicht verbunden");
        }
    }
    
    /**
     * Liest den Server-Modell und gibt alle verfügbaren Logical Devices aus
     */
    public void readServerModel() {
        if (!connected) {
            logger.error("Nicht verbunden! Bitte zuerst connect() aufrufen.");
            System.out.println("✗ Fehler: Nicht verbunden!");
            return;
        }
        
        try {
            logger.info("Lese Server-Modell...");
            System.out.println("\n=== MMS Server Model ===");
            
            // Verwende AasIecConnector2 um Logical Devices abzufragen
            byte[] modelResponse = connector.getLogicalDevices();
            logger.debug("Model Response erhalten: {} Bytes", modelResponse.length);
            
            // Dekodiere die Antwort
            List<String> devices = AasIecConnector2.decodeGetNameListResponse(modelResponse);
            logicalDevices.clear();
            logicalDevices.addAll(devices);
            
            if (devices.isEmpty()) {
                System.out.println("Keine Logical Devices gefunden.");
                logger.warn("Keine Logical Devices in der Antwort gefunden");
            } else {
                System.out.println("Logical Devices gefunden: " + devices.size());
                for (String device : devices) {
                    System.out.println("  - " + device);
                }
            }
            
            System.out.println("========================\n");
            
        } catch (IOException e) {
            logger.error("Fehler beim Lesen des Server-Modells: {}", e.getMessage(), e);
            System.out.println("✗ Fehler beim Lesen des Server-Modells: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unerwarteter Fehler beim Lesen des Server-Modells: {}", e.getMessage(), e);
            System.out.println("✗ Unerwarteter Fehler: " + e.getMessage());
        }
    }
    
    /**
     * Liest einen spezifischen Datenpunkt und gibt ihn auf der Konsole aus
     * 
     * @param reference Objektreferenz (z.B. "ld0/GGIO1.Ind1.stVal")
     */
    /**
     * Liest eine einzelne Variable direkt über den Connector.
     * Gibt das ReadResult zurück, damit der Aufrufer den Wert weiterverarbeiten kann.
     *
     * @param domainId Domain (z.B. "WPPS")
     * @param itemId   Sensor-Pfad (z.B. "LCCS1$ST$Mod$stVal")
     * @param invokeId Laufende Invoke-ID für die MMS-Anfrage
     * @return ReadResult mit ok-Flag und ggf. Fehlerdaten
     * @throws IOException bei Kommunikationsproblemen
     */
    public String readVariable(String domainId, String itemId, int invokeId) throws IOException {
        if (!connected) {
            throw new IOException("Nicht verbunden");
        }
        byte[] readRequest = AasIecConnector2.buildMmsReadRequest3(domainId, itemId, invokeId);
        byte[] readResponse = connector.sendRequest("Read", readRequest);
        logger.debug("readVariable {}/{} (invokeId=0x{}) → {} Bytes",
                domainId, itemId, Integer.toHexString(invokeId), readResponse.length);
        return decodeDataAndReturnValue(readResponse);
    }

    /**
     * Dekodiert eine MMS Read Response und gibt den reinen Datenwert als String zurück,
     * ohne Typbezeichnungen oder Metainformationen.
     *
     * @param data Rohe MMS Read Response (vollständiges TPKT-Paket)
     * @return Wert als String, oder Fehlermeldung wenn die Antwort nicht dekodiert werden kann
     */
    public String decodeDataAndReturnValue(byte[] data) {
        int i = findTag(data, 0xa4);
        if (i < 0) return "(kein readResponse-Tag)";
        i++;
        int[] lr = berLen(data, i); i += lr[1];

        if (i >= data.length || (data[i] & 0xFF) != 0xa1) return "(kein listOfAccessResult-Tag)";
        i++;
        int[] lr2 = berLen(data, i);
        int listEnd = i + lr2[1] + lr2[0];
        i += lr2[1];

        List<String> values = new ArrayList<>();
        while (i < listEnd && i < data.length) {
            int tag = data[i] & 0xFF; i++;
            int[] lr3 = berLen(data, i);
            int len = lr3[0]; i += lr3[1];
            values.add(decodeValueOnly(tag, data, i, len));
            i += len;
        }

        if (values.isEmpty()) return "(keine Datenwerte)";
        return values.size() == 1 ? values.get(0) : String.join("\n", values);
    }

    private String decodeValueOnly(int tag, byte[] data, int offset, int len) {
        if (offset + len > data.length) return "(Datenfehler: zu kurz)";
        switch (tag) {
            case 0x83: // boolean
                return len > 0 && data[offset] != 0 ? "TRUE" : "FALSE";
            case 0x85: { // integer
                long v = 0;
                for (int j = 0; j < len; j++) v = (v << 8) | (data[offset + j] & 0xFF);
                if (len > 0 && (data[offset] & 0x80) != 0) v |= (-1L << (len * 8));
                return Long.toString(v);
            }
            case 0x86: { // unsigned
                long v = 0;
                for (int j = 0; j < len; j++) v = (v << 8) | (data[offset + j] & 0xFF);
                return Long.toString(v);
            }
            case 0x87: // floating-point (1 Byte exponent-width + IEEE 754 big-endian)
                if (len >= 5) {
                    int bits = ((data[offset + 1] & 0xFF) << 24) | ((data[offset + 2] & 0xFF) << 16)
                             | ((data[offset + 3] & 0xFF) << 8)  |  (data[offset + 4] & 0xFF);
                    return Float.toString(Float.intBitsToFloat(bits));
                }
                return "(" + len + " bytes)";
            case 0x84: { // bit-string
                StringBuilder sb = new StringBuilder("0b");
                for (int j = 0; j < len; j++)
                    sb.append(String.format("%8s", Integer.toBinaryString(data[offset + j] & 0xFF))
                               .replace(' ', '0'));
                return sb.toString();
            }
            case 0x88: { // octet-string
                StringBuilder sb = new StringBuilder("0x");
                for (int j = 0; j < len; j++)
                    sb.append(String.format("%02x", data[offset + j] & 0xFF));
                return sb.toString();
            }
            case 0x89: // visible-string
            case 0x8a: // generalized-time
            case 0x8f: // mms-string (UTF-8)
                return new String(data, offset, len, StandardCharsets.UTF_8);
            case 0x8b: // binary-time
            case 0x91: // UTC (IEC 61850, 8 bytes)
                return decodeUtcTimeValue(data, offset, len);
            case 0x80: // DataAccessError
                return "ERROR code=" + (len > 0 ? (data[offset] & 0xFF) : "?");
            case 0xa1: // array
            case 0xa2: { // structure
                List<String> parts = new ArrayList<>();
                int j = offset, end = offset + len;
                while (j < end) {
                    int t2 = data[j] & 0xFF; j++;
                    int[] lr = berLen(data, j); j += lr[1];
                    parts.add(decodeValueOnly(t2, data, j, lr[0]));
                    j += lr[0];
                }
                return String.join(", ", parts);
            }
            default: {
                boolean printable = len > 0;
                for (int j = 0; j < len && printable; j++) {
                    int c = data[offset + j] & 0xFF;
                    if (c < 32 || c > 126) printable = false;
                }
                if (printable) return new String(data, offset, len, StandardCharsets.US_ASCII);
                StringBuilder sb = new StringBuilder("0x");
                for (int j = 0; j < Math.min(len, 32); j++)
                    sb.append(String.format("%02x", data[offset + j] & 0xFF));
                if (len > 32) sb.append("...");
                return sb.toString();
            }
        }
    }

    private String decodeUtcTimeValue(byte[] data, int offset, int len) {
        if (len < 8) return "(UTC, " + len + " bytes – zu kurz)";
        long secs = ((data[offset]     & 0xFFL) << 24)
                  | ((data[offset + 1] & 0xFFL) << 16)
                  | ((data[offset + 2] & 0xFFL) << 8)
                  |  (data[offset + 3] & 0xFFL);
        long frac = ((data[offset + 4] & 0xFFL) << 16)
                  | ((data[offset + 5] & 0xFFL) << 8)
                  |  (data[offset + 6] & 0xFFL);
        long millis = secs * 1000L + (frac * 1000L / 0x1000000L);
        LocalDateTime ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        return ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
    }

    private static int findTag(byte[] data, int tag) {
        for (int i = 0; i < data.length; i++)
            if ((data[i] & 0xFF) == tag) return i;
        return -1;
    }

    private static int[] berLen(byte[] data, int offset) {
        if (offset >= data.length) return new int[]{0, 1};
        int b = data[offset] & 0xFF;
        if (b < 0x80) return new int[]{b, 1};
        int n = b & 0x7F, len = 0;
        for (int i = 1; i <= n && offset + i < data.length; i++)
            len = (len << 8) | (data[offset + i] & 0xFF);
        return new int[]{len, 1 + n};
    }

    public void readDataPoint(String reference) {
        if (!connected) {
            logger.error("Nicht verbunden! Bitte zuerst connect() aufrufen.");
            System.out.println("✗ Fehler: Nicht verbunden!");
            return;
        }
        
        try {
            logger.info("Lese Datenpunkt: {}", reference);
            
            // Parse reference in domainId und itemId
            String[] parts = parseReference(reference);
            String domainId = parts[0];
            String itemId = parts[1];
            
            // Baue MMS Read Request
            byte[] readRequest = AasIecConnector2.buildMmsReadRequest3(domainId, itemId, 0x20);
            
            // Sende Request via AasIecConnector2
            byte[] readResponse = connector.sendRequest("read", readRequest);
            logger.debug("Read Response erhalten: {} Bytes", readResponse.length);
            
            // Dekodiere Response
            AasIecConnector2.ReadResult result = AasIecConnector2.decodeMmsReadResponse(readResponse);
            
            System.out.println("\nDatepunkt: " + reference);
            if (result.ok) {
                System.out.println("  Status: Erfolgreich gelesen");
                System.out.println("  Quality: Good");
                // TODO: Wert aus Response extrahieren
                System.out.println("  Wert: (siehe Raw Response)");
            } else {
                System.out.println("  Status: Fehler");
                System.out.println("  Error Code: " + result.errorCode);
                System.out.println("  Error Name: " + result.errorName);
            }
            System.out.println("  Zeitstempel: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            System.out.println();
            
        } catch (Exception e) {
            logger.error("Fehler beim Lesen des Datenpunkts: {}", e.getMessage(), e);
            System.out.println("✗ Fehler beim Lesen: " + e.getMessage());
        }
    }
    
    /**
     * Parst eine IEC 61850 Referenz in domainId und itemId
     * Format: "domainId/itemId" oder nur "itemId"
     */
    private String[] parseReference(String reference) {
        if (reference.contains("/")) {
            String[] parts = reference.split("/", 2);
            return new String[]{parts[0], parts[1]};
        } else {
            // Default domain wenn nicht angegeben
            return new String[]{"ld0", reference};
        }
    }
    
    /**
     * Führt eine Test-Abfrage mit Standard-Werten durch.
     * Liest standardmäßig: domainId="WPPS", itemId="LCCS1$DC$NamPlt$vendor"
     * 
     * Diese Methode dient zum Testen der MMS-Verbindung und zum Auslesen
     * von Herstellerinformationen aus dem Device.
     */
    public void runTestQuery() {
        runTestQuery("WPPS", "LCCS1$DC$NamPlt$vendor");
    }
    
    /**
     * Führt eine Test-Abfrage mit benutzerdefinierten Werten durch.
     * 
     * @param domainId Die Domain ID (z.B. "WPPS")
     * @param itemId Die Item ID (z.B. "LCCS1$DC$NamPlt$vendor")
     */
    public void runTestQuery(String domainId, String itemId) {
        if (!connected) {
            logger.error("Nicht verbunden! Bitte zuerst connect() aufrufen.");
            System.out.println("✗ Fehler: Nicht verbunden!");
            return;
        }
        
        try {
            logger.info("=== Test-Abfrage gestartet ===");
            System.out.println("\n=== MMS Test-Abfrage ===");
            System.out.println("Domain ID: " + domainId);
            System.out.println("Item ID: " + itemId);
            System.out.println("========================");
            
            // Baue MMS Read Request
            byte[] readRequest = AasIecConnector2.buildMmsReadRequest3(domainId, itemId, 0x20);
            logger.debug("Read Request erstellt: {} Bytes", readRequest.length);
            
            // Sende Request via AasIecConnector2
            logger.info("Sende Read Request...");
            byte[] readResponse = connector.sendRequest("read", readRequest);
            logger.info("Read Response erhalten: {} Bytes", readResponse.length);
            
            // Zeige Raw Response (Hex)
            System.out.println("\nRaw Response (Hex):");
            System.out.println(AasIecConnector2.bytesToHex(readResponse));
            System.out.println();
            
            // Dekodiere Response
            AasIecConnector2.ReadResult result = AasIecConnector2.decodeMmsReadResponse(readResponse);
            
            System.out.println("Ergebnis:");
            if (result.ok) {
                System.out.println("  ✓ Status: Erfolgreich gelesen");
                System.out.println("  ✓ Quality: Good");
                System.out.println("  ℹ Hinweis: Wert ist in der Raw Response enthalten");
                logger.info("Test-Abfrage erfolgreich abgeschlossen");
            } else {
                System.out.println("  ✗ Status: Fehler beim Lesen");
                System.out.println("  ✗ Error Code: " + result.errorCode);
                System.out.println("  ✗ Error Name: " + result.errorName);
                logger.error("Test-Abfrage fehlgeschlagen: {} (Code: {})", result.errorName, result.errorCode);
            }
            
            System.out.println("  Zeitstempel: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            System.out.println("========================\n");
            
        } catch (IOException e) {
            logger.error("Fehler bei Test-Abfrage: {}", e.getMessage(), e);
            System.out.println("\n✗ Fehler bei Test-Abfrage: " + e.getMessage());
            System.out.println("  Details: " + e.getClass().getSimpleName());
        } catch (Exception e) {
            logger.error("Unerwarteter Fehler bei Test-Abfrage: {}", e.getMessage(), e);
            System.out.println("\n✗ Unerwarteter Fehler: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Abonniert Datenänderungen (Reports) vom Server
     * 
     * @param logicalDevice Name des Logical Device (z.B. "ld0")
     */
    public void subscribeToReports(String logicalDevice) {
        if (!connected) {
            logger.error("Nicht verbunden! Bitte zuerst connect() aufrufen.");
            System.out.println("✗ Fehler: Nicht verbunden!");
            return;
        }
        
        try {
            logger.info("Abonniere Reports für Logical Device: {}", logicalDevice);
            
            // TODO: Report Subscribe Request implementieren
            logger.warn("Report Subscribe noch nicht vollständig implementiert");
            
            System.out.println("✓ Reports abonniert für " + logicalDevice);
            System.out.println("Warte auf eingehende MMS-Nachrichten...\n");
            
        } catch (Exception e) {
            logger.error("Fehler beim Abonnieren von Reports: {}", e.getMessage());
        }
    }
    
    /**
     * Startet den Message Listener Thread
     */
    private void startMessageListener() {
        listenerThread = new Thread(() -> {
            logger.info("Message Listener Thread gestartet - Warte auf echte MMS-Nachrichten vom Server");
            
            while (connected && !Thread.currentThread().isInterrupted()) {
                try {
                    // Prüfe Socket-Status vom Connector
                    if (connector.socket == null || connector.socket.isClosed() || !connector.socket.isConnected()) {
                        String reason = "Socket-Status ungültig: " + 
                                      (connector.socket == null ? "null" : 
                                       connector.socket.isClosed() ? "geschlossen" : 
                                       "nicht verbunden");
                        logger.error("Verbindung verloren: {}", reason);
                        System.err.println("\n✗ VERBINDUNG VERLOREN: " + reason);
                        disconnect(reason);
                        break;
                    }
                    
                    // Lese echte MMS-Nachricht vom Server
                    MMSMessage message = readMMSMessage();
                    
                    if (message != null && connected) {
                        logger.debug("MMS-Nachricht vom Server empfangen: {}", message.getReportId());
                        
                        // Benachrichtige alle Listener
                        for (MMSMessageListener listener : messageListeners) {
                            try {
                                listener.onMessageReceived(message);
                            } catch (Exception e) {
                                logger.error("Fehler in Message Listener: {}", e.getMessage(), e);
                            }
                        }
                    }
                    
                } catch (InterruptedException e) {
                    logger.info("Message Listener Thread wurde unterbrochen (normal beim Beenden)");
                    break;
                    
                } catch (java.net.SocketTimeoutException e) {
                    // Socket Read Timeout - normal, einfach weiter warten
                    logger.trace("Socket Read Timeout (normal) - warte weiter auf Daten");
                    continue;
                    
                } catch (java.net.SocketException e) {
                    String reason = "Socket-Fehler: " + e.getMessage();
                    logger.error("Verbindung unerwartet getrennt: {}", reason, e);
                    System.err.println("\n✗ VERBINDUNG UNERWARTET GETRENNT");
                    System.err.println("  Grund: " + reason);
                    System.err.println("  Details: " + e.getClass().getSimpleName());
                    disconnect(reason);
                    break;
                    
                } catch (EOFException e) {
                    String reason = "Server hat Verbindung geschlossen (EOF)";
                    logger.error("{}", reason);
                    System.err.println("\n✗ SERVER HAT VERBINDUNG GESCHLOSSEN");
                    System.err.println("  Der MMS-Server hat die Verbindung beendet.");
                    System.err.println("  Mögliche Gründe:");
                    System.err.println("    - Server wurde heruntergefahren");
                    System.err.println("    - Ungültige MMS-Anfrage gesendet");
                    System.err.println("    - Server-Timeout");
                    System.err.println("    - Protokoll-Fehler");
                    disconnect(reason);
                    break;
                    
                } catch (IOException e) {
                    String reason = "I/O-Fehler beim Lesen: " + e.getMessage();
                    logger.error("I/O-Fehler beim Lesen von MMS-Nachrichten: {}", e.getMessage(), e);
                    System.err.println("\n✗ I/O-FEHLER");
                    System.err.println("  Grund: " + reason);
                    System.err.println("  Exception: " + e.getClass().getName());
                    disconnect(reason);
                    break;
                    
                } catch (Exception e) {
                    logger.error("Unerwarteter Fehler im Message Listener: {}", e.getMessage(), e);
                    System.err.println("\n✗ UNERWARTETER FEHLER");
                    System.err.println("  " + e.getClass().getName() + ": " + e.getMessage());
                    e.printStackTrace();
                    // Bei unerwarteten Fehlern nicht disconnecten, sondern weitermachen
                }
            }
            
            logger.info("Message Listener Thread beendet");
        });
        
        listenerThread.setDaemon(true);
        listenerThread.setName("MMS-MessageListener");
        listenerThread.start();
    }
    
    /**
     * Liest eine MMS-Nachricht (Report) vom Server
     * Prüft erst ob Daten verfügbar sind (nicht-blockierend für Silent Server)
     * 
     * @return Geparste MMS-Nachricht oder null bei Fehler
     * @throws IOException bei Verbindungsproblemen
     * @throws InterruptedException bei Thread-Unterbrechung
     */
    private MMSMessage readMMSMessage() throws IOException, InterruptedException {
        try {
            // Prüfe Verbindungsstatus
            if (!connected || connector.socket == null || connector.socket.isClosed()) {
                logger.trace("readMMSMessage abgebrochen - Socket nicht verbunden");
                return null;
            }
            
            InputStream inputStream = connector.socket.getInputStream();
            
            // WICHTIG: Prüfe ERST ob Daten verfügbar sind (nicht-blockierend)
            int available = inputStream.available();
            
            if (available == 0) {
                // Keine Daten verfügbar - warte kurz und versuche erneut
                Thread.sleep(100);
                return null; // Kein blockierendes Read
            }
            
            logger.trace("Daten verfügbar: {} Bytes", available);
            
            // Jetzt gibt es Daten - versuche zu lesen
            byte[] payload;
            try {
                // Empfange TPKT Paket (oder RAW Daten)
                payload = receiveTPKT(inputStream);
                if (payload == null) {
                    return null;
                }
            } catch (java.net.SocketTimeoutException e) {
                // Timeout ist normal - Server sendet einfach gerade keine Daten
                throw e;
            }
            
            logger.debug("TPKT-Paket empfangen: {} Bytes, erster Byte: 0x{}", 
                        payload.length, Integer.toHexString(payload[0] & 0xFF));
            
            int offset = 0;
            
            // Prüfe ob COTP Data oder direkt MMS
            if (payload[0] == (byte)0xF0 && payload.length >= 3) {
                logger.trace("COTP Data erkannt, überspringe Header");
                // Überspringe COTP Header (3 Bytes: Type + EOT + TPDU-NR)
                offset = 3;
            } else {
                logger.trace("Kein COTP Header, direktes MMS/ACSE");
                // Kein COTP - direktes MMS/ACSE
                offset = 0;
            }
            
            if (payload.length <= offset) {
                logger.warn("Payload zu kurz");
                return null;
            }
            
            // Lese MMS/ACSE PDU Type
            int pduType = payload[offset] & 0xFF;
            logger.debug("MMS/ACSE PDU Type: 0x{}", Integer.toHexString(pduType));
            
            // Lese PDU Length (ASN.1 BER kodiert)
            offset++;
            int pduLength = readASN1LengthFromArray(payload, offset);
            int lengthBytes = calculateASN1LengthBytes(payload[offset] & 0xFF);
            offset += lengthBytes;
            
            if (pduLength <= 0 || pduLength > 65536) {
                logger.warn("Ungültige PDU-Länge: {}", pduLength);
                return null;
            }
            
            // Extrahiere PDU Body
            byte[] pduData = new byte[pduLength];
            if (offset + pduLength > payload.length) {
                logger.warn("Payload zu kurz für angegebene PDU-Länge");
                return null;
            }
            System.arraycopy(payload, offset, pduData, 0, pduLength);
            
            // Parse MMS PDUs
            if (pduType == 0xA3) { // MMS_INFORMATION_REPORT - Unconfirmed Information Report
                logger.debug("Parsing MMS Information Report (Unconfirmed)");
                return parseMMSInformationReport(pduData);
                
            } else if (pduType == 0xA1) { // MMS_CONFIRMED_RESPONSE - Confirmed Response
                logger.debug("Parsing MMS Confirmed Response");
                return parseMMSConfirmedResponse(pduData);
                
            } else if (pduType == 0xA9) { // MMS_INITIATE_RESPONSE - Initiate Response
                logger.debug("MMS Initiate Response empfangen (ignoriert im Listener)");
                return null;
                
            } else {
                logger.debug("Unbekannter MMS PDU-Typ: 0x{}", Integer.toHexString(pduType));
                return null;
            }
            
        } catch (EOFException e) {
            logger.warn("Verbindung vom Server geschlossen");
            throw new IOException("Verbindung geschlossen", e);
        }
    }
    
    /**
     * Empfängt TPKT-Paket vom InputStream und gibt Payload zurück
     */
    private byte[] receiveTPKT(InputStream inputStream) throws IOException {
        // Lese TPKT Header (4 Bytes)
        byte[] header = new byte[4];
        int bytesRead = 0;
        while (bytesRead < 4) {
            int read = inputStream.read(header, bytesRead, 4 - bytesRead);
            if (read == -1) {
                throw new EOFException("Verbindung geschlossen beim Lesen des TPKT Headers");
            }
            bytesRead += read;
        }
        
        // Prüfe TPKT Version
        if (header[0] != 0x03) {
            logger.warn("Ungültige TPKT Version: 0x{}", Integer.toHexString(header[0] & 0xFF));
            return null;
        }
        
        // Lese Länge
        int totalLength = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        int payloadLength = totalLength - 4;
        
        if (payloadLength <= 0 || payloadLength > 65536) {
            logger.warn("Ungültige TPKT Payload-Länge: {}", payloadLength);
            return null;
        }
        
        // Lese Payload
        byte[] payload = new byte[payloadLength];
        bytesRead = 0;
        while (bytesRead < payloadLength) {
            int read = inputStream.read(payload, bytesRead, payloadLength - bytesRead);
            if (read == -1) {
                throw new EOFException("Verbindung geschlossen beim Lesen der TPKT Payload");
            }
            bytesRead += read;
        }
        
        logger.trace("TPKT empfangen: {} Bytes gesamt ({} Bytes Payload)", totalLength, payloadLength);
        return payload;
    }
    
    /**
     * Liest ASN.1 BER-kodierte Länge aus Byte-Array
     */
    private int readASN1LengthFromArray(byte[] data, int offset) {
        int firstByte = data[offset] & 0xFF;
        
        if ((firstByte & 0x80) == 0) {
            // Short form
            return firstByte;
        } else {
            // Long form
            int numBytes = firstByte & 0x7F;
            int length = 0;
            for (int i = 0; i < numBytes && offset + 1 + i < data.length; i++) {
                length = (length << 8) | (data[offset + 1 + i] & 0xFF);
            }
            return length;
        }
    }
    
    /**
     * Berechnet wie viele Bytes die Längen-Kodierung benötigt
     */
    private int calculateASN1LengthBytes(int firstByte) {
        if ((firstByte & 0x80) == 0) {
            return 1; // Short form
        } else {
            return 1 + (firstByte & 0x7F); // Long form: 1 + Anzahl der Längen-Bytes
        }
    }
    
    /**
     * Parst ein MMS InformationReport (unbestätigte Meldung vom Server)
     */
    private MMSMessage parseMMSInformationReport(byte[] data) {
        try {
            MMSMessage message = new MMSMessage();
            
            // Vereinfachtes Parsing - In Produktion: ASN.1 Parser verwenden
            int offset = 0;
            
            // VariableAccessSpecification lesen
            if (data[offset] == (byte)0xA0) { // Context-specific [0]
                offset++;
                int varAccessLen = data[offset++] & 0xFF;
                
                // ListOfVariable
                if (data[offset] == (byte)0xA0) {
                    offset++;
                    int listLen = data[offset++] & 0xFF;
                    
                    // Extrahiere Report ID aus Variable Names
                    String reportId = extractReportId(data, offset);
                    message.setReportId(reportId != null ? reportId : "REPORT_" + System.currentTimeMillis());
                    
                    offset += listLen;
                }
            }
            
            // ListOfData lesen
            if (offset < data.length && data[offset] == (byte)0xA1) { // Context-specific [1]
                offset++;
                int dataLen = data[offset++] & 0xFF;
                
                // Parse Data Items
                Map<String, Object> dataMap = parseDataItems(data, offset, dataLen);
                message.setData(dataMap);
            }
            
            // Dataset-Referenz (vereinfacht)
            message.setDataSet("ld0/LLN0$DataSet");
            message.setTimestamp(LocalDateTime.now());
            
            logger.debug("MMS InformationReport geparst: {} Datenpunkte", message.getData().size());
            return message;
            
        } catch (Exception e) {
            logger.error("Fehler beim Parsen des InformationReports: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Parst eine MMS Confirmed-Response (Antwort auf Read-Request)
     */
    private MMSMessage parseMMSConfirmedResponse(byte[] data) {
        try {
            MMSMessage message = new MMSMessage();
            message.setReportId("READ_RESPONSE");
            message.setTimestamp(LocalDateTime.now());
            
            // Parse AccessResult
            Map<String, Object> dataMap = parseDataItems(data, 0, data.length);
            message.setData(dataMap);
            
            return message;
            
        } catch (Exception e) {
            logger.error("Fehler beim Parsen der Confirmed-Response: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Extrahiert die Report-ID aus den MMS-Daten
     */
    private String extractReportId(byte[] data, int offset) {
        try {
            // Suche nach VisibleString mit Report ID
            for (int i = offset; i < Math.min(offset + 100, data.length - 1); i++) {
                if (data[i] == 0x1A) { // VisibleString Tag
                    int len = data[i + 1] & 0xFF;
                    if (len > 0 && len < 50 && i + 2 + len <= data.length) {
                        return new String(data, i + 2, len, "ASCII");
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Konnte Report-ID nicht extrahieren: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * Parst MMS Data Items (vereinfachte Implementierung)
     */
    private Map<String, Object> parseDataItems(byte[] data, int offset, int length) {
        Map<String, Object> result = new HashMap<>();
        int endOffset = Math.min(offset + length, data.length);
        int dataPointIndex = 0;
        
        try {
            for (int i = offset; i < endOffset - 1; i++) {
                byte tag = data[i];
                int len = data[i + 1] & 0xFF;
                
                if (i + 2 + len > endOffset) break;
                
                Object value = null;
                String key = "DataPoint_" + dataPointIndex++;
                
                // Parse nach MMS Data Type
                switch (tag) {
                    case 0x03: // Boolean
                        value = data[i + 2] != 0;
                        break;
                    case 0x05: // FloatingPoint (IEEE 754)
                        if (len == 4) {
                            int bits = ((data[i + 2] & 0xFF) << 24) |
                                      ((data[i + 3] & 0xFF) << 16) |
                                      ((data[i + 4] & 0xFF) << 8) |
                                      (data[i + 5] & 0xFF);
                            value = Float.intBitsToFloat(bits);
                        }
                        break;
                    case 0x06: // Integer
                        if (len <= 4) {
                            int intValue = 0;
                            for (int j = 0; j < len; j++) {
                                intValue = (intValue << 8) | (data[i + 2 + j] & 0xFF);
                            }
                            value = intValue;
                        }
                        break;
                    case 0x1A: // VisibleString
                        value = new String(data, i + 2, len, "ASCII");
                        key = value.toString(); // Verwende String als Key
                        i += 2 + len;
                        
                        // Lese folgenden Wert
                        if (i < endOffset - 1) {
                            byte valueTag = data[i];
                            int valueLen = data[i + 1] & 0xFF;
                            value = parseSimpleValue(data, i, valueTag, valueLen);
                        }
                        break;
                    default:
                        // Unbekannter Typ, überspringe
                        value = "Unknown_Type_0x" + Integer.toHexString(tag & 0xFF);
                }
                
                if (value != null) {
                    result.put(key, value);
                }
                
                i += 1 + len;
            }
        } catch (Exception e) {
            logger.debug("Fehler beim Parsen der Data Items: {}", e.getMessage());
        }
        
        // Falls keine Daten geparst wurden, füge Platzhalter hinzu
        if (result.isEmpty()) {
            logger.warn("Keine Datenpunkte aus MMS-Nachricht extrahiert - möglicherweise unbekanntes Format");
            result.put("raw_data_length", data.length);
        }
        
        return result;
    }
    
    /**
     * Parst einen einfachen MMS-Wert
     */
    private Object parseSimpleValue(byte[] data, int offset, byte tag, int len) {
        try {
            switch (tag) {
                case 0x03: // Boolean
                    return data[offset + 2] != 0;
                case 0x05: // Float
                    if (len == 4) {
                        int bits = ((data[offset + 2] & 0xFF) << 24) |
                                  ((data[offset + 3] & 0xFF) << 16) |
                                  ((data[offset + 4] & 0xFF) << 8) |
                                  (data[offset + 5] & 0xFF);
                        return Float.intBitsToFloat(bits);
                    }
                    break;
                case 0x06: // Integer
                    if (len <= 4) {
                        int value = 0;
                        for (int i = 0; i < len; i++) {
                            value = (value << 8) | (data[offset + 2 + i] & 0xFF);
                        }
                        return value;
                    }
                    break;
            }
        } catch (Exception e) {
            logger.debug("Fehler beim Parsen des Wertes: {}", e.getMessage());
        }
        return null;
    }
    
    
    /**
     * Prüft ob der Client verbunden ist
     * 
     * @return true wenn verbunden, sonst false
     */
    public boolean isConnected() {
        return connected;
    }
    
    /**
     * Konsolen Message Listener - gibt Nachrichten formatiert aus
     */
    private static class ConsoleMessageListener implements MMSMessageListener {
        private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        
        @Override
        public void onMessageReceived(MMSMessage message) {
            System.out.println("\n╔════════════════════════════════════════════════╗");
            System.out.println("║    Neue MMS-Nachricht empfangen               ║");
            System.out.println("╠════════════════════════════════════════════════╣");
            System.out.println("║ Report ID:    " + padRight(message.getReportId(), 32) + "║");
            System.out.println("║ Dataset:      " + padRight(message.getDataSet(), 32) + "║");
            System.out.println("║ Zeitstempel:  " + padRight(message.getTimestamp().format(formatter), 32) + "║");
            System.out.println("╠════════════════════════════════════════════════╣");
            System.out.println("║ Daten:                                         ║");
            
            for (Map.Entry<String, Object> entry : message.getData().entrySet()) {
                String key = entry.getKey();
                String value = String.valueOf(entry.getValue());
                
                // Kürze zu lange Strings
                if (key.length() > 25) {
                    key = key.substring(0, 22) + "...";
                }
                if (value.length() > 20) {
                    value = value.substring(0, 17) + "...";
                }
                
                System.out.println("║   " + padRight(key, 26) + " = " + padRight(value, 17) + "║");
            }
            
            System.out.println("╚════════════════════════════════════════════════╝\n");
        }
        
        private static String padRight(String s, int n) {
            if (s.length() >= n) {
                return s.substring(0, n);
            }
            return String.format("%-" + n + "s", s);
        }
    }
}
