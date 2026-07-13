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

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.logging.Logger;

/**
 * AAS IEC 61850 MMS Connector
 *
 * Bildet den vollständigen Lifecycle ab:
 *   1. TCP-Verbindung (RFC1006/COTP Connect Request)
 *   2. MMS-Association (ACSE/MMS Associate Request)
 *   3. Logical Devices abfragen (MMS GetNameList / "model")
 *   4. Werte lesen (MMS Read Request)
 *   5. Verbindung trennen
 */
public class AasIecConnector2 {

    private static final Logger LOG = Logger.getLogger(AasIecConnector2.class.getName());

    // -------------------------------------------------------------------------
    // Konstante PDUs (identisch mit dem JS-Original)
    // -------------------------------------------------------------------------

    /** RFC1006 TPKT-Header + COTP Connection Request (CR) */
    private static final byte[] RFC1006_COTP_CONNECT_REQUEST = hexToBytes(
        "030000160011E0000000010000C1020000C202000100C2010A00"
            // kompaktere Schreibweise der Original-Bytes
    );

    // Aus Capture: src-ref=0x0008, Param-Reihenfolge C0, C2, C1
    private static final byte[] RFC1006_COTP_CONNECT_REQUEST_BYTES = new byte[]{
        (byte)0x03, (byte)0x00, (byte)0x00, (byte)0x16,
        (byte)0x11, (byte)0xE0, (byte)0x00, (byte)0x00,
        (byte)0x00, (byte)0x08, (byte)0x00, (byte)0xC0,
        (byte)0x01, (byte)0x0A, (byte)0xC2, (byte)0x02,
        (byte)0x00, (byte)0x01, (byte)0xC1, (byte)0x02,
        (byte)0x00, (byte)0x00
    };

    /** MMS Associate Request (Default – aus dem JS-Original) */
    private static final byte[] MMS_ASSOCIATE_REQUEST_DEFAULT = hexToBytes(
        "030000bb02f0800db20506130100160102140200023302000134020001c19c318199" +
        "a003800101a28191810400000001820400000001a423300f0201010604520100013004" +
        "060251013010020103060528ca220201300406025101615e305c020101a0576055a107" +
        "060528ca220203a20706052901876701a30302010ca606060429018767a70302010cbe" +
        "2f282d020103a028a826800300fde881010a82010a830105a416800101810305f10082" +
        "0c03ee1c00000408000079ef18"
    );

    /** MMS Report / Subscribe Request Präfix */
    private static final byte[] MMS_REPORT_REQUEST_PREFIX = new byte[]{
        (byte)0x03, (byte)0x00, (byte)0x00, (byte)0x01
    };

    /** MMS GetNameList (Model) Request */
    private static final byte[] MMS_MODEL_REQUEST = new byte[]{
        (byte)0x04, (byte)0x00, (byte)0x00, (byte)0x01
    };

    // -------------------------------------------------------------------------
    // Zustandsfelder
    // -------------------------------------------------------------------------

    public Socket socket = null;
    private boolean connected = false;
    private boolean associated = false;

    private final String host;
    private final int port;
    private final int timeoutMs;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public AasIecConnector2(String host, int port, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    // -------------------------------------------------------------------------
    // main – vollständiger Ablauf: connect → associate → devices → read → disconnect
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        // Konfiguration – ggf. als CLI-Argumente übergeben
        String host      = args.length > 0 ? args[0] : "localhost";
        int    port      = args.length > 1 ? Integer.parseInt(args[1]) : 102;
        int    timeoutMs = args.length > 2 ? Integer.parseInt(args[2]) : 50000;

        // Zu lesende Variable (domainId / itemId)
        String domainId = args.length > 3 ? args[3] : "WPPS";
        String itemId   = args.length > 4 ? args[4] : "LCCS1$CF";
       // String itemId   = args.length > 4 ? args[4] : "LCCS1";
        String logicalDevice = args.length > 5 ? args[5] : "ld0";

        AasIecConnector2 connector = new AasIecConnector2(host, port, timeoutMs);

        // --- 1. TCP + COTP Connect ---
        System.out.println("=== Schritt 1: Verbindung aufbauen ===");
        connector.connect();
        System.out.printf("Verbunden mit %s:%d%n", host, port);

//        
//     // --- 2. MMS Associate ---
//        System.out.println("\n=== Schritt 2: MMS Association ===");
//        byte[] assocResponse = connector.associate(null);
//        System.out.println("Associate Response (hex): " + bytesToHex(assocResponse));
//
//        // --- DIAGNOSE: Was liegt noch im Buffer? ---
//        InputStream diagIn = connector.socket.getInputStream();
//
//        System.out.println("\n[Diagnose] available() sofort nach Associate: " 
//            + diagIn.available());
//
//        Thread.sleep(300);
//        System.out.println("[Diagnose] available() nach 300ms: " 
//            + diagIn.available());
//
//        Thread.sleep(700);
//        System.out.println("[Diagnose] available() nach weiteren 700ms: " 
//            + diagIn.available());
//
//        if (diagIn.available() > 0) {
//            byte[] extra = connector.readBytesFirstChunk(diagIn, 2000);
//            System.out.println("[Diagnose] Spontaner Frame vom Server: " + bytesToHex(extra));
//        } else {
//            System.out.println("[Diagnose] Kein spontaner Frame - Server wartet.");
//        }

        
        
        // --- 2. MMS Associate ---
        System.out.println("\n=== Schritt 2: MMS Association ===");
        byte[] assocResponse = connector.associate(null);
        System.out.println("Associate Response (hex): " + bytesToHex(assocResponse));

        
//     // Schritt 3: genau wie JS — rohe 4 Bytes, kein TPKT
//        byte[] modelResponse = connector.sendRawRequest("model",
//            new byte[]{(byte)0x04, (byte)0x00, (byte)0x00, (byte)0x01});
//
//        List<String> devices = decodeGetNameListResponse(modelResponse);
//        System.out.println("Logical Devices (" + devices.size() + "):");
//        for (String d : devices) {
//            System.out.println("  - " + d);
//        }

        
        
     // Schritt 3
        System.out.println("\n=== Schritt 3: Decices ===");
        byte[] modelResponse = connector.sendRequest("model", buildMmsGetNameListRequest2());
        System.out.println("Model Response (hex): " + bytesToHex(modelResponse));
        
        List<String> devices = decodeGetNameListResponse(modelResponse);
        System.out.println("Logical Devices (" + devices.size() + "):");
        for (String d : devices) {
            System.out.println("  - " + d);
        }
        
        
        ReadResult readResult2 = decodeMmsReadResponse(modelResponse);
        if (readResult2.ok) {
            System.out.println("Lesen erfolgreich.");
        } else {
            System.out.printf("Lesen fehlgeschlagen – Code %d: %s%n",
                readResult2.errorCode, readResult2.errorName);
        }


        
        
        // 3 newu
     // --- 3. Logical Devices abfragen (proprietärer model-Befehl) ---
//        System.out.println("\n=== Schritt 3: Logical Devices abfragen ===");
//        byte[] rawModelCmd = new byte[]{(byte)0x04, (byte)0x00, (byte)0x00, (byte)0x01};
//        byte[] modelResponse = connector.sendRawRequest("model", rawModelCmd);
//        System.out.println("Model Response (hex): " + bytesToHex(modelResponse));

        
       //  --- 3. Logical Devices abfragen (model) ---
//        System.out.println("\n=== Schritt 3: Logical Devices abfragen ===");
//       // byte[] modelResponse = connector.sendRequest("model", MMS_MODEL_REQUEST);
//        byte[] modelResponse2 = connector.sendRequest("model", buildMmsGetNameListRequest());
//        
//        System.out.println("Model Response (hex): " + bytesToHex(modelResponse2));

        // --- 4. Wert lesen ---
        
         domainId = "WPPS";
         itemId   = "LCCS1$ST$Mod$stVal";
         
          domainId = "WPPS";
          itemId   = "LCCS1$DC$NamPlt$vendor";
        
        System.out.printf("%n=== Schritt 4: Wert lesen [%s / %s] ===%n", domainId, itemId);
        byte[] readRequest = buildMmsReadRequest3(domainId, itemId, 0x20);
        byte[] readResponse = connector.sendRequest("read", readRequest);
        System.out.println("Read Response (hex): " + bytesToHex(readResponse));

        ReadResult readResult = decodeMmsReadResponse(readResponse);
        if (readResult.ok) {
            System.out.println("Lesen erfolgreich.");
        } else {
            System.out.printf("Lesen fehlgeschlagen – Code %d: %s%n",
                readResult.errorCode, readResult.errorName);
        }

        // --- 5. Verbindung trennen ---
        System.out.println("\n=== Schritt 5: Verbindung trennen ===");
        connector.disconnect();
        System.out.println("Verbindung getrennt.");
    }

    // -------------------------------------------------------------------------
    // Öffentliche Operationen
    // -------------------------------------------------------------------------

    /**
     * Baut TCP-Verbindung auf und sendet den RFC1006/COTP Connection Request.
     * Erwartet als Antwort ein COTP Connection Confirm (CC, PDU-Typ 0xD0).
     */
    public void connect() throws IOException {
        if (connected && socket != null && !socket.isClosed()) {
            LOG.info("Bereits verbunden – überspringe connect.");
            return;
        }

        LOG.info(String.format("Verbinde mit %s:%d (Timeout %d ms)...", host, port, timeoutMs));

        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setSoTimeout(timeoutMs);

        // COTP Connect Request senden
        OutputStream out = socket.getOutputStream();
        out.write(RFC1006_COTP_CONNECT_REQUEST_BYTES);
        out.flush();
        LOG.info("RFC1006/COTP Connect Request gesendet: " +
            bytesToHex(RFC1006_COTP_CONNECT_REQUEST_BYTES));

        // Antwort lesen
        byte[] response = readBytes(socket.getInputStream(), timeoutMs);
        LOG.info("COTP-Antwort erhalten: " + bytesToHex(response));

        if (!isCotpConnectionConfirm(response)) {
            socket.close();
            throw new IOException(
                "Ungültige COTP Connection Confirm vom Server: " + bytesToHex(response));
        }

        // Timeout nach erfolgreichem Verbindungsaufbau zurücksetzen
        socket.setSoTimeout(0);
        connected = true;
        associated = false;
        LOG.info(String.format("Verbindung hergestellt: %s:%d", host, port));
    }

    /**
     * Sendet den MMS Associate Request und gibt die rohe Antwort zurück.
     * Optionaler Parameter {@code customAssociateHex}: wenn nicht null/leer,
     * wird dieser Hex-String anstelle des Default-Requests verwendet.
     */
    public byte[] associate(String customAssociateHex) throws IOException {
        if (associated) {
            LOG.info("Bereits assoziiert – überspringe associate.");
            return new byte[0];
        }

        ensureConnected();

        byte[] request = MMS_ASSOCIATE_REQUEST_DEFAULT;
        if (customAssociateHex != null && !customAssociateHex.isBlank()) {
            byte[] raw = parseHexToBytes(customAssociateHex, "associateHex");
            request = extractTpktFrame(raw, "associateHex");
        }

        socket.setSoTimeout(timeoutMs);
        OutputStream out = socket.getOutputStream();
        out.write(request);
        out.flush();
        LOG.info("MMS Associate Request gesendet: " + bytesToHex(request));

        byte[] response = readBytes(socket.getInputStream(), timeoutMs);
        LOG.info("MMS Associate Response erhalten: " + bytesToHex(response));

        socket.setSoTimeout(0);
        associated = true;
        return response;
    }

    /**
     * Sendet einen beliebigen vorbereiteten MMS-Request und gibt die Antwort zurück.
     * Stellt sicher, dass vorher eine Association besteht.
     */
    public byte[] sendRequest(String operationLabel, byte[] request) throws IOException {
        ensureAssociated(null);

        socket.setSoTimeout(timeoutMs);
        OutputStream out = socket.getOutputStream();
        out.write(request);
        out.flush();
        LOG.info(String.format("MMS %s Request gesendet: %s", operationLabel, bytesToHex(request)));

        byte[] response = readBytes(socket.getInputStream(), timeoutMs);
        LOG.info(String.format("MMS %s Response: %s", operationLabel, bytesToHex(response)));

        socket.setSoTimeout(0);
        return response;
    }

    /**
     * Trennt die TCP-Verbindung und setzt den Zustand zurück.
     */
    public void disconnect() {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                LOG.warning("Fehler beim Schließen des Sockets: " + e.getMessage());
            }
        }
        socket = null;
        connected = false;
        associated = false;
        LOG.info("Verbindung getrennt.");
    }

    // -------------------------------------------------------------------------
    // Hilfsmethoden – Verbindungssicherung
    // -------------------------------------------------------------------------

    private void ensureConnected() throws IOException {
        if (!connected || socket == null || socket.isClosed()) {
            connect();
        }
    }

    private void ensureAssociated(String customAssociateHex) throws IOException {
        ensureConnected();
        if (!associated) {
            associate(customAssociateHex);
        }
    }

    // -------------------------------------------------------------------------
    // TPKT / COTP Parsing
    // -------------------------------------------------------------------------

    private static boolean isTpktPacket(byte[] data) {
        return data != null && data.length >= 4 && data[0] == 0x03;
    }

    private static int readTpktLength(byte[] data) {
        if (!isTpktPacket(data)) return 0;
        return ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
    }

    /**
     * COTP Connection Confirm: TPKT-Paket, COTP PDU-Typ 0xD0 an Byte 5.
     */
    private static boolean isCotpConnectionConfirm(byte[] data) {
        if (!isTpktPacket(data)) return false;
        int tpktLen = readTpktLength(data);
        if (tpktLen < 7 || data.length < tpktLen) return false;
        return (data[5] & 0xFF) == 0xD0;
    }

    /**
     * Extrahiert den ersten TPKT-Frame aus einem Puffer.
     * Sucht ab Byte 0; falls das Paket nicht direkt mit 0x03 0x00 beginnt,
     * wird der Puffer gescannt.
     */
    private static byte[] extractTpktFrame(byte[] buffer, String fieldName) throws IOException {
        if (buffer == null || buffer.length < 4) {
            throw new IOException("Ungültiges " + fieldName + ": zu kurz.");
        }

        if ((buffer[0] & 0xFF) == 0x03 && (buffer[1] & 0xFF) == 0x00) {
            int tpktLen = readTpktLength(buffer);
            if (tpktLen < 7 || tpktLen > buffer.length) {
                throw new IOException("Ungültiges " + fieldName +
                    ": fehlerhafte TPKT-Länge " + tpktLen);
            }
            return Arrays.copyOfRange(buffer, 0, tpktLen);
        }

        for (int i = 0; i <= buffer.length - 4; i++) {
            if ((buffer[i] & 0xFF) == 0x03 && (buffer[i + 1] & 0xFF) == 0x00) {
                int tpktLen = ((buffer[i + 2] & 0xFF) << 8) | (buffer[i + 3] & 0xFF);
                if (tpktLen >= 7 && i + tpktLen <= buffer.length) {
                    return Arrays.copyOfRange(buffer, i, i + tpktLen);
                }
            }
        }

        throw new IOException("Ungültiges " + fieldName +
            ": kein TPKT-Frame gefunden (erwartet: 03 00 ...).");
    }

    // -------------------------------------------------------------------------
    // BER Encoding
    // -------------------------------------------------------------------------

    private static byte[] encodeBerLength(int length) throws IOException {
        if (length < 0) throw new IOException("Ungültige BER-Länge: " + length);
        if (length < 0x80) return new byte[]{(byte) length};
        if (length <= 0xFF) return new byte[]{(byte) 0x81, (byte) length};
        if (length <= 0xFFFF) return new byte[]{
            (byte) 0x82, (byte) ((length >> 8) & 0xFF), (byte) (length & 0xFF)
        };
        throw new IOException("BER-Länge zu groß: " + length);
    }

    private static byte[] encodeBerTlv(int tag, byte[] value) throws IOException {
        byte[] lenBytes = encodeBerLength(value.length);
        byte[] result = new byte[1 + lenBytes.length + value.length];
        result[0] = (byte) tag;
        System.arraycopy(lenBytes, 0, result, 1, lenBytes.length);
        System.arraycopy(value, 0, result, 1 + lenBytes.length, value.length);
        return result;
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, offset, a.length);
            offset += a.length;
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // MMS Read Request Builder
    // -------------------------------------------------------------------------

    /**
     * Baut einen MMS-Read-Request für eine einzelne Variable (domainId / itemId).
     * Die Struktur entspricht dem JS-Original 1:1.
     */
    public static byte[] buildMmsReadRequest(String domainId, String itemId, int invokeId)
        throws IOException {

        if (invokeId < 0 || invokeId > 255) invokeId = 0x20;

        byte[] domainBytes = domainId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] itemBytes   = itemId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        // domainSpecific ::= [UNIVERSAL 26] domainId, [UNIVERSAL 26] itemId
        byte[] domainSpecific = concat(
            encodeBerTlv(0x1A, domainBytes),
            encodeBerTlv(0x1A, itemBytes)
        );

        // variableSpecification ::= [0] { [1] domainSpecific }
        byte[] variableSpecification = encodeBerTlv(0xA0, encodeBerTlv(0xA1, domainSpecific));
        byte[] listOfVariableItem    = encodeBerTlv(0x30, variableSpecification);
        byte[] listOfVariable        = encodeBerTlv(0xA0, listOfVariableItem);
        byte[] variableAccessSpec    = encodeBerTlv(0xA1, listOfVariable);

        // readRequest ::= [4] { specWithResult = 0xFF, variableAccessSpec }
//        byte[] readRequestPayload = concat(
//            new byte[]{(byte)0x80, 0x01, (byte)0xFF},
//            variableAccessSpec
//        );
        
        byte[] readRequestPayload = concat(
                new byte[]{(byte)0x80, 0x01, (byte)0x00},
                variableAccessSpec
            );
        
        
        byte[] readRequest = encodeBerTlv(0xA4, readRequestPayload);

        // confirmedServiceRequest ::= [0] { invokeId, readRequest }
        byte[] confirmedServiceRequestPayload = concat(
            new byte[]{0x02, 0x01, (byte) invokeId},
            readRequest
        );
        byte[] confirmedServiceRequest = encodeBerTlv(0xA0, confirmedServiceRequestPayload);

        // äußerer invokeID = 0x03 (wie im JS-Original fest codiert)
        byte[] confirmedRequestBody = concat(
            new byte[]{0x02, 0x01, 0x03},
            encodeBerTlv(0xA0, confirmedServiceRequest)
        );

        byte[] confirmedRequestPdu = encodeBerTlv(0x30, confirmedRequestBody);
        byte[] mmsPayload          = encodeBerTlv(0x61, confirmedRequestPdu);

        // Session + Presentation Header
        byte[] sessionAndPresentation = new byte[]{0x01, 0x00, 0x01, 0x00};
        byte[] cotpDataTpdu           = new byte[]{0x02, (byte)0xF0, (byte)0x80};

        byte[] payload = concat(cotpDataTpdu, sessionAndPresentation, mmsPayload);

        // TPKT Header
        int tpktLength = 4 + payload.length;
        byte[] tpktHeader = new byte[]{
            0x03, 0x00,
            (byte)((tpktLength >> 8) & 0xFF),
            (byte)(tpktLength & 0xFF)
        };

        return concat(tpktHeader, payload);
    }

    // -------------------------------------------------------------------------
    // MMS Read Response Decoder
    // -------------------------------------------------------------------------

    public static class ReadResult {
        public boolean ok;
        public int     errorCode;
        public String  errorName;

        ReadResult(boolean ok, int errorCode, String errorName) {
            this.ok        = ok;
            this.errorCode = errorCode;
            this.errorName = errorName;
        }
    }

    private static final Map<Integer, String> READ_ACCESS_ERROR_MAP = new HashMap<>();
    static {
        READ_ACCESS_ERROR_MAP.put(0,  "object-invalidated");
        READ_ACCESS_ERROR_MAP.put(1,  "hardware-fault");
        READ_ACCESS_ERROR_MAP.put(2,  "temporarily-unavailable");
        READ_ACCESS_ERROR_MAP.put(3,  "object-access-denied");
        READ_ACCESS_ERROR_MAP.put(4,  "object-undefined");
        READ_ACCESS_ERROR_MAP.put(5,  "invalid-address");
        READ_ACCESS_ERROR_MAP.put(6,  "type-unsupported");
        READ_ACCESS_ERROR_MAP.put(7,  "type-inconsistent");
        READ_ACCESS_ERROR_MAP.put(8,  "object-attribute-inconsistent");
        READ_ACCESS_ERROR_MAP.put(9,  "object-access-unsupported");
        READ_ACCESS_ERROR_MAP.put(10, "object-non-existent");
        READ_ACCESS_ERROR_MAP.put(11, "object-value-invalid");
    }

    /**
     * Analysiert die MMS-Read-Antwort auf BER DataAccessError (85 01 <code>).
     */
    public static ReadResult decodeMmsReadResponse(byte[] response) {
        if (response == null || response.length == 0) {
            return new ReadResult(false, -1, "empty-response");
        }
        for (int i = 0; i <= response.length - 3; i++) {
            if ((response[i] & 0xFF) == 0x85 && (response[i + 1] & 0xFF) == 0x01) {
                int code = response[i + 2] & 0xFF;
                String name = READ_ACCESS_ERROR_MAP.getOrDefault(code, "unknown");
                return new ReadResult(false, code, name);
            }
        }
        return new ReadResult(true, -1, null);
    }

    
    public static byte[] buildMmsGetNameListRequest() throws IOException {
        // MMS GetNameList – objectClass=domain(0), objectScope=vmdSpecific
        // Exakt nach ISO 9506-1 / libiec61850
        byte[] objectClass  = new byte[]{(byte)0xa0, 0x03, (byte)0x82, 0x01, 0x00};
        byte[] objectScope  = new byte[]{(byte)0xa1, 0x02, (byte)0xa0, 0x00};
        byte[] getNameList  = encodeBerTlv(0xa1, concat(objectClass, objectScope));
        byte[] invokeId     = new byte[]{0x02, 0x01, 0x01};
        byte[] mmsSequence  = encodeBerTlv(0x30, concat(invokeId, getNameList));
        byte[] mmsPdu       = encodeBerTlv(0x61, mmsSequence);

        byte[] cotpData     = new byte[]{0x02, (byte)0xF0, (byte)0x80};
        byte[] sessionPres  = new byte[]{0x01, 0x00, 0x01, 0x00};
        byte[] payload      = concat(cotpData, sessionPres, mmsPdu);

        int tpktLen = 4 + payload.length;
        byte[] tpktHeader = new byte[]{
            0x03, 0x00,
            (byte)((tpktLen >> 8) & 0xFF),
            (byte)(tpktLen & 0xFF)
        };
        return concat(tpktHeader, payload);
    }

    
 // Schritt 3: Model-Request mit korrektem TPKT-Wrapper bauen
    public static byte[] buildMmsModelRequest() throws IOException {
        // MMS GetNameList VMD-specific (Standard-PDU)
        // Payload: 04 00 00 01 ist ein proprietärer/herstellerspezifischer Shortcut –
        // muss als COTP Data TPDU verpackt werden
        byte[] modelPayloadRaw = new byte[]{(byte)0x04, (byte)0x00, (byte)0x00, (byte)0x01};

        byte[] cotpDataTpdu           = new byte[]{0x02, (byte)0xF0, (byte)0x80};
        byte[] sessionAndPresentation = new byte[]{0x01, 0x00, 0x01, 0x00};

        byte[] payload = concat(cotpDataTpdu, sessionAndPresentation, modelPayloadRaw);

        int tpktLength = 4 + payload.length;
        byte[] tpktHeader = new byte[]{
            0x03, 0x00,
            (byte)((tpktLength >> 8) & 0xFF),
            (byte)(tpktLength & 0xFF)
        };
        return concat(tpktHeader, payload);
    }

    // -------------------------------------------------------------------------
    // Socket-Lesen
    // -------------------------------------------------------------------------

    /**
     * Liest alle verfügbaren Bytes vom InputStream (blockiert bis Timeout).
     * Liest zunächst den 4-Byte TPKT-Header, dann den Rest gemäß Längenbyte.
     */
    private static byte[] readBytes(InputStream in, int timeoutMs) throws IOException {
        // TPKT-Header (4 Bytes) lesen
        byte[] header = new byte[4];
        int read = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (read < 4) {
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("Timeout beim Lesen des TPKT-Headers.");
            }
            int r = in.read(header, read, 4 - read);
            if (r == -1) throw new IOException("Verbindung vom Peer geschlossen.");
            read += r;
        }

        if ((header[0] & 0xFF) != 0x03) {
            // Kein TPKT – Fallback: rohen Puffer lesen
            byte[] rest = in.readNBytes(Math.min(in.available(), 4096));
            return concat(header, rest);
        }

        int tpktLen = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        byte[] full  = new byte[tpktLen];
        System.arraycopy(header, 0, full, 0, 4);

        int remaining = tpktLen - 4;
        int offset    = 4;
        while (remaining > 0) {
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("Timeout beim Lesen der TPKT-Nutzlast.");
            }
            int r = in.read(full, offset, remaining);
            if (r == -1) throw new IOException("Verbindung vom Peer während Lesen geschlossen.");
            offset    += r;
            remaining -= r;
        }

        return full;
    }

    // -------------------------------------------------------------------------
    // Hex-Utilities
    // -------------------------------------------------------------------------

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    public static byte[] hexToBytes(String hex) {
        String clean = hex.replaceAll("\\s+", "").toLowerCase();
        if (clean.length() % 2 != 0) {
            throw new IllegalArgumentException("Ungültige Hex-Länge: " + clean.length());
        }
        byte[] result = new byte[clean.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static byte[] parseHexToBytes(String rawHex, String fieldName) throws IOException {
        String hex = rawHex.replaceAll("\\s+", "").toLowerCase();
        if (hex.isEmpty()) throw new IOException("Fehlendes " + fieldName + ".");
        if (!hex.matches("[0-9a-f]+") || hex.length() % 2 != 0) {
            throw new IOException("Ungültiges " + fieldName +
                ": erwartet gerade Hex-String, erhalten: " + rawHex);
        }
        return hexToBytes(hex);
    }
    
    //von mir
    /**
     * Liest rohe Bytes ohne TPKT-Erwartung.
     * Nimmt alles was innerhalb des Timeouts kommt (wie JS socket.once('data')).
     */
    private static byte[] readBytesRaw(InputStream in, int timeoutMs) throws IOException {
        byte[] buf = new byte[4096];
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("Timeout beim Lesen der Rohantwort.");
            }
            int available = in.available();
            if (available > 0) {
                int r = in.read(buf, 0, Math.min(available, buf.length));
                if (r == -1) throw new IOException("Verbindung vom Peer geschlossen.");
                return Arrays.copyOfRange(buf, 0, r);
            }
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    /**
     * Sendet rohe Bytes ohne TPKT-Wrapper und liest mit readBytesRaw().
     * Für proprietäre Server-Befehle wie 04 00 00 01.
     */
    public byte[] sendRawRequest(String operationLabel, byte[] rawBytes) throws IOException {
        ensureAssociated(null);

        socket.setSoTimeout(timeoutMs);
        OutputStream out = socket.getOutputStream();
        out.write(rawBytes);
        out.flush();
        LOG.info(String.format("MMS %s Raw-Request gesendet: %s",
            operationLabel, bytesToHex(rawBytes)));

        byte[] response = readBytesRaw(socket.getInputStream(), timeoutMs);
        LOG.info(String.format("MMS %s Raw-Response: %s",
            operationLabel, bytesToHex(response)));

        socket.setSoTimeout(0);
        return response;
    }
    /**
     * Fragt die Logical Devices ab (MMS "model").
     *
     * Verhält sich exakt wie JS sendMmsRequest() + socket.once('data'):
     *   1. Listener (readBytesFirstChunk) VOR dem Senden bereit
     *   2. Rohe 4 Bytes [04 00 00 01] ohne TPKT-Wrapper senden
     *   3. Ersten TCP-Chunk nehmen – kein Format-Check
     *
     * @return roher Response-Buffer (proprietäres Format des Servers)
     */
    public byte[] getLogicalDevices() throws IOException {
        return sendRequest("getLogicalDevices", buildMmsGetNameListRequest2());
    }

    /**
     * Liest den ersten verfügbaren TCP-Chunk vom InputStream.
     *
     * Analog zu JS socket.once('data', onData):
     *   - Kein TPKT-Format erwartet
     *   - Nimmt alles was zuerst kommt (polling mit 5ms Schlaf)
     *   - Bricht bei Timeout oder EOF ab
     */
    public static byte[] readBytesFirstChunk(InputStream in, int timeoutMs)
            throws IOException {

        byte[] buf = new byte[4096];
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            int available = in.available();
            if (available > 0) {
                int r = in.read(buf, 0, Math.min(available, buf.length));
                if (r == -1) throw new IOException("Verbindung vom Peer geschlossen.");
                return Arrays.copyOfRange(buf, 0, r);
            }
            try { Thread.sleep(5); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Lesen unterbrochen.");
            }
        }
        throw new IOException("Timeout: Keine Antwort innerhalb von " + timeoutMs + " ms.");
    }
    public static byte[] buildMmsGetNameListRequest2() throws IOException {
        // Exakt aus Capture: objectClass domain(9), objectScope vmdSpecific,
        // outer invokeId=3, inner invokeId=1, doppelter a0-Wrapper
        byte[] innerInvokeId = new byte[]{0x02, 0x01, 0x01};
        byte[] objectClass   = new byte[]{(byte)0xa0, 0x03, (byte)0x80, 0x01, 0x09};  // domain=9
        byte[] objectScope   = new byte[]{(byte)0xa1, 0x02, (byte)0x80, 0x00};        // vmdSpecific
        byte[] getNameList   = encodeBerTlv(0xa1, concat(objectClass, objectScope));
        byte[] innerReq      = encodeBerTlv(0xa0, concat(innerInvokeId, getNameList));
        byte[] outerReq      = encodeBerTlv(0xa0, innerReq);
        byte[] outerInvokeId = new byte[]{0x02, 0x01, 0x03};
        byte[] mmsPdu        = encodeBerTlv(0x61, encodeBerTlv(0x30, concat(outerInvokeId, outerReq)));

        byte[] cotp    = new byte[]{0x02, (byte)0xF0, (byte)0x80};
        byte[] sesPres = new byte[]{0x01, 0x00, 0x01, 0x00};
        byte[] payload = concat(cotp, sesPres, mmsPdu);

        int tpktLen = 4 + payload.length;
        return concat(
            new byte[]{0x03, 0x00, (byte)((tpktLen >> 8) & 0xFF), (byte)(tpktLen & 0xFF)},
            payload
        );
        // Expected frame: 0300002402f0800100010061173015020103a010a00e020101a109a003800109a1028000
    }
    /**
     * Dekodiert einen MMS GetNameList Response → Liste der Logical Device Namen.
     * MMS getNameList-Response: tag 0xa2, enthält SEQUENCE OF VisibleString (0x1a)
     */
    public static List<String> decodeGetNameListResponse(byte[] response) {
        List<String> names = new ArrayList<>();
        if (response == null || response.length == 0) return names;

        // GetNameList-Response: listOfIdentifier ist [0] = 0xa0 (nicht 0xa2)
        // Suche nach a0 und starte collectVisibleStrings innerhalb davon.
        // Fallback: ab Byte 11 (nach TPKT(4)+COTP(3)+Session(4)).
        int a0offset = findTag(response, 0, (byte) 0xa0);
        if (a0offset < 0) {
            int mmsStart = Math.min(11, response.length);
            collectVisibleStrings(response, mmsStart, names);
            return names;
        }
        int a2dataStart = a0offset + 1 + berLengthBytes(response, a0offset + 1);
        collectVisibleStrings(response, a2dataStart, names);
        return names;
    }

    private static void collectVisibleStrings(byte[] data, int start, List<String> out) {
        int i = start;
        while (i < data.length - 1) {
            int tag = data[i] & 0xFF;
            int[] lr = readBerLengthWithSize(data, i + 1);
            int length = lr[0], headerSize = 1 + lr[1];
            if (tag == 0x1a || tag == 0x04) {
                if (i + headerSize + length <= data.length) {
                    String name = new String(data, i + headerSize, length,
                        java.nio.charset.StandardCharsets.US_ASCII).trim();
                    if (!name.isEmpty()) out.add(name);
                }
            } else if ((tag & 0x20) != 0 || tag == 0x30 || tag == 0x31
                    || (tag & 0xE0) == 0xA0) {
                if (i + headerSize + length <= data.length)
                    collectVisibleStrings(data, i + headerSize, out);
            }
            if (headerSize + length == 0) break;
            i += headerSize + length;
        }
    }

    private static int findTag(byte[] data, int start, byte tag) {
        for (int i = start; i < data.length; i++)
            if (data[i] == tag) return i;
        return -1;
    }

    private static int berLengthBytes(byte[] data, int offset) {
        if ((data[offset] & 0xFF) < 0x80) return 1;
        return 1 + (data[offset] & 0x7F);
    }

    private static int[] readBerLengthWithSize(byte[] data, int offset) {
        if (offset >= data.length) return new int[]{0, 1};
        int b = data[offset] & 0xFF;
        if (b < 0x80) return new int[]{b, 1};
        int nbytes = b & 0x7F;
        int len = 0;
        for (int i = 1; i <= nbytes && offset + i < data.length; i++)
            len = (len << 8) | (data[offset + i] & 0xFF);
        return new int[]{len, 1 + nbytes};
    }

    public static byte[] buildMmsGetNameListRequest3() throws IOException {
        // Exakt der libiec61850-kompatible Frame — kein extra Presentation-Wrapper
        byte[] invokeId    = new byte[]{0x02, 0x01, 0x01};
        byte[] objectClass = new byte[]{(byte)0xa0, 0x03, (byte)0x82, 0x01, 0x00};
        byte[] objectScope = new byte[]{(byte)0xa1, 0x02, (byte)0xa0, 0x00};
        byte[] getNameList = encodeBerTlv(0xa1, concat(objectClass, objectScope));
        byte[] mmsPdu      = encodeBerTlv(0x61, encodeBerTlv(0x30, concat(invokeId, getNameList)));

        byte[] cotp    = new byte[]{0x02, (byte)0xF0, (byte)0x80};
        byte[] sesPres = new byte[]{0x01, 0x00, 0x01, 0x00};
        byte[] payload = concat(cotp, sesPres, mmsPdu);

        int tlen = 4 + payload.length;
        return concat(
            new byte[]{0x03, 0x00, (byte)((tlen >> 8) & 0xFF), (byte)(tlen & 0xFF)},
            payload
        );
    }
    public static byte[] buildMmsGetNameListRequest4() throws IOException {
        byte[] invokeId    = new byte[]{0x02, 0x01, 0x01};
        byte[] objectClass = new byte[]{(byte)0xa0, 0x03, (byte)0x82, 0x01, 0x00};
        byte[] objectScope = new byte[]{(byte)0xa1, 0x02, (byte)0xa0, 0x00};
        byte[] getNameList = encodeBerTlv(0xa1, concat(objectClass, objectScope));

        // 0xa0 statt 0x30 — confirmed-RequestPDU [0] nach ISO 9506-1
        byte[] confirmedReq = encodeBerTlv(0xa0, concat(invokeId, getNameList));
        byte[] mmsPdu       = encodeBerTlv(0x61, confirmedReq);

        byte[] payload = concat(
            new byte[]{0x02, (byte)0xF0, (byte)0x80},  // COTP
            new byte[]{0x01, 0x00, 0x01, 0x00},          // Session
            mmsPdu
        );
        int tlen = 4 + payload.length;
        return concat(
            new byte[]{0x03, 0x00, (byte)((tlen >> 8) & 0xFF), (byte)(tlen & 0xFF)},
            payload
        );
    }

    
    public static byte[] buildMmsGetNameListRequest5() throws IOException {
        // Basiert auf dem PDV-Wrapper-Frame der eine Server-Antwort provoziert hat.
        // Äußere Struktur (0x30 + invokeId=3 + a0) beibehalten,
        // aber inneren Content direkt als a1-getNameList statt doppeltem 0x61-Wrapper.
        byte[] invokeOuter = new byte[]{0x02, 0x01, 0x03};
        byte[] invokeInner = new byte[]{0x02, 0x01, 0x01};
        byte[] objectClass = new byte[]{(byte)0xa0, 0x03, (byte)0x82, 0x01, 0x00};
        byte[] objectScope = new byte[]{(byte)0xa1, 0x02, (byte)0xa0, 0x00};
        byte[] getNameList = encodeBerTlv(0xa1, concat(objectClass, objectScope));

        byte[] inner    = encodeBerTlv(0xa0, concat(invokeInner, getNameList));
        byte[] outer    = encodeBerTlv(0x30, concat(invokeOuter, inner));
        byte[] mmsPdu   = encodeBerTlv(0x61, outer);

        byte[] payload  = concat(
            new byte[]{0x02, (byte)0xF0, (byte)0x80},
            new byte[]{0x01, 0x00, 0x01, 0x00},
            mmsPdu
        );
        int tlen = 4 + payload.length;
        return concat(
            new byte[]{0x03, 0x00, (byte)((tlen >> 8) & 0xFF), (byte)(tlen & 0xFF)},
            payload
        );
        // Gesendeter Frame: 0300002202f0800100010061153013020103a00e020101a109a003820100a102a000
    }
    public static byte[] buildMmsGetNameListRequest6() throws IOException {
    	byte[] invokeId = new byte[]{0x02, 0x01, 0x01};
        byte[] objectClass = new byte[]{(byte)0xa0, 0x03, (byte)0x82, 0x01, 0x00};
        byte[] objectScope = new byte[]{(byte)0xa1, 0x02, (byte)0xa0, 0x00};
        // a1 direkt in SEQUENCE — kein a0-Wrapper dazwischen
        byte[] mmsPdu  = encodeBerTlv(0x61,
                           encodeBerTlv(0x30,
                             concat(invokeId, encodeBerTlv(0xa1, concat(objectClass, objectScope)))));
        // Frame: 0300001d02f080010001006110300e020103a109a003820100a102a000
        byte[] payload = concat(new byte[]{0x02,(byte)0xF0,(byte)0x80, 0x01,0x00,0x01,0x00}, mmsPdu);
        int tlen = 4 + payload.length;
        return concat(new byte[]{0x03,0x00,(byte)((tlen>>8)&0xFF),(byte)(tlen&0xFF)}, payload);
    }
    /**
     * Sendet rohe Bytes (kein TPKT) und liest die Antwort blockierend —
     * analog zu JS socket.write() + socket.once('data').
     */
    public byte[] sendRawRequest2(String label, byte[] rawBytes) throws IOException {
        ensureAssociated(null);

        socket.setSoTimeout(timeoutMs);
        OutputStream out = socket.getOutputStream();
        out.write(rawBytes);
        out.flush();
        LOG.info(String.format("MMS %s Raw-Request gesendet: %s", label, bytesToHex(rawBytes)));

        // in.read() blockiert bis Bytes ankommen — wie JS socket.once('data')
        // in.available() NICHT verwenden — unzuverlässig bei TCP!
        byte[] buf = new byte[4096];
        int r = socket.getInputStream().read(buf, 0, buf.length);
        if (r == -1) throw new IOException("Verbindung vom Peer geschlossen.");

        byte[] response = Arrays.copyOfRange(buf, 0, r);
        LOG.info(String.format("MMS %s Raw-Response: %s", label, bytesToHex(response)));
        socket.setSoTimeout(0);
        return response;
    }
    public static byte[] buildMmsReadRequest2(String domainId, String itemId, int invokeId)
    	    throws IOException {

    	    if (invokeId < 0 || invokeId > 255) invokeId = 0x20;

    	    byte[] domainBytes = domainId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    	    byte[] itemBytes   = itemId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    	    // domainSpecific: zwei VisibleStrings
    	    byte[] domainSpecific      = concat(encodeBerTlv(0x1A, domainBytes),
    	                                        encodeBerTlv(0x1A, itemBytes));
    	    // variableAccessSpecification [1] listOfVariable [0] SEQUENCE [0] [1] domainSpecific
    	    byte[] variableSpec        = encodeBerTlv(0xA0, encodeBerTlv(0xA1, domainSpecific));
    	    byte[] listOfVariableItem  = encodeBerTlv(0x30, variableSpec);
    	    byte[] listOfVariable      = encodeBerTlv(0xA0, listOfVariableItem);
    	    byte[] variableAccessSpec  = encodeBerTlv(0xA1, listOfVariable);

    	    // readRequest [4]: specWithResult=FALSE + variableAccessSpec
    	    byte[] readRequest = encodeBerTlv(0xA4,
    	        concat(new byte[]{(byte)0x80, 0x01, (byte)0x00}, variableAccessSpec));

    	    // confirmed-RequestPDU: SEQUENCE { invokeId, a4 readRequest }
    	    // KEIN doppelter a0-Wrapper, NUR ein invokeId!
    	    byte[] confirmedReqPdu = encodeBerTlv(0x30,
    	        concat(new byte[]{0x02, 0x01, (byte) invokeId}, readRequest));

    	    byte[] mmsPdu  = encodeBerTlv(0x61, confirmedReqPdu);
    	    byte[] payload = concat(
    	        new byte[]{0x02, (byte)0xF0, (byte)0x80},  // COTP
    	        new byte[]{0x01, 0x00, 0x01, 0x00},          // Session
    	        mmsPdu
    	    );
    	    int tlen = 4 + payload.length;
    	    return concat(
    	        new byte[]{0x03, 0x00, (byte)((tlen >> 8) & 0xFF), (byte)(tlen & 0xFF)},
    	        payload
    	    );
    	}

    public static byte[] buildMmsReadRequest3(String domainId, String itemId, int invokeId)
    	    throws IOException {

    	    if (invokeId < 0 || invokeId > 255) invokeId = 0x20;

    	    byte[] domainBytes = domainId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    	    byte[] itemBytes   = itemId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    	    // Aus Capture: a1 { a0 { 30 { a0 { a1 { 1a domain, 1a item } } } } }
    	    byte[] domainSpecific  = concat(encodeBerTlv(0x1A, domainBytes),
    	                                    encodeBerTlv(0x1A, itemBytes));
    	    byte[] objectName      = encodeBerTlv(0xA1, domainSpecific);    // [1] name (domainSpecific)
    	    byte[] variableSpec    = encodeBerTlv(0xA0, objectName);         // [0] variableSpecification
    	    byte[] listItem        = encodeBerTlv(0x30, variableSpec);       // SEQUENCE { variableSpec }
    	    byte[] listOfVariable  = encodeBerTlv(0xA0, listItem);           // [0] listOfVariable
    	    byte[] variableAccess  = encodeBerTlv(0xA1, listOfVariable);     // [1] variableAccessSpec

    	    // specWithResult=TRUE (0x01), aus Capture: 80 01 01
    	    byte[] readRequest = encodeBerTlv(0xA4,
    	        concat(new byte[]{(byte)0x80, 0x01, (byte)0x01}, variableAccess));

    	    // Doppelter a0-Wrapper: outer invokeId=3, inner invokeId=invokeId
    	    byte[] csp     = encodeBerTlv(0xA0,
    	                       concat(new byte[]{0x02, 0x01, (byte) invokeId}, readRequest));
    	    byte[] crBody  = concat(new byte[]{0x02, 0x01, 0x03},
    	                            encodeBerTlv(0xA0, csp));
    	    byte[] mmsPdu  = encodeBerTlv(0x61, encodeBerTlv(0x30, crBody));

    	    byte[] payload = concat(
    	        new byte[]{0x02, (byte)0xF0, (byte)0x80},
    	        new byte[]{0x01, 0x00, 0x01, 0x00},
    	        mmsPdu
    	    );
    	    int tlen = 4 + payload.length;
    	    return concat(
    	        new byte[]{0x03, 0x00, (byte)((tlen>>8)&0xFF), (byte)(tlen&0xFF)},
    	        payload
    	    );
    	}

}
