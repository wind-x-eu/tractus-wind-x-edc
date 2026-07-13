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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Crawl Job der alle 100 MMS-Nachrichten eine Aggregation durchführt
 * und an den semantischen Mediator sendet
 */
public class MMSAggregationCrawler implements Runnable {
    
    private static final Logger logger = LoggerFactory.getLogger(MMSAggregationCrawler.class);
    private static final int AGGREGATION_SIZE = 10;
    
    private final MMSMessageQueue messageQueue;
    private final Consumer<String> onAggregation;
    private final Gson gson;
    private boolean running;

    private long totalMessagesProcessed = 0;
    private long totalAggregationsSent = 0;

    public MMSAggregationCrawler(MMSMessageQueue messageQueue, Consumer<String> onAggregation) {
        this.messageQueue = messageQueue;
        this.onAggregation = onAggregation;
        this.running = false;
        
        // DateTimeFormatter für LocalDateTime
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
        
        // Gson mit LocalDateTime Support konfigurieren
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(LocalDateTime.class, 
                    (JsonSerializer<LocalDateTime>) (src, typeOfSrc, context) -> 
                        context.serialize(src.format(formatter)))
                .registerTypeAdapter(LocalDateTime.class,
                    (JsonDeserializer<LocalDateTime>) (json, typeOfT, context) ->
                        LocalDateTime.parse(json.getAsString(), formatter))
                .create();
        
        logger.info("MMSAggregationCrawler erstellt");
    }
    
    /**
     * Startet den Crawler
     */
    public void start() {
        if (!running) {
            running = true;
            Thread crawlerThread = new Thread(this, "MMS-Aggregation-Crawler");
            crawlerThread.setDaemon(false);
            crawlerThread.start();
            logger.info("Aggregation Crawler gestartet");
        }
    }
    
    /**
     * Stoppt den Crawler
     */
    public void stop() {
        running = false;
        logger.info("Aggregation Crawler wird gestoppt...");
    }
    
    @Override
    public void run() {
        logger.info("Crawler-Thread läuft...");
        
        List<MMSMessage> messageBuffer = new ArrayList<>();
        
        while (running) {
            try {
                // Lese Nachricht aus Queue (mit Timeout, um stoppen zu können)
                String jsonMessage = messageQueue.dequeue(1, TimeUnit.SECONDS);
                
                if (jsonMessage != null) {
                    // Parse JSON zurück zu MMSMessage Objekt
                    MMSMessage message = gson.fromJson(jsonMessage, MMSMessage.class);
                    messageBuffer.add(message);
                    totalMessagesProcessed++;
                    
                    logger.debug("Nachricht {} von {} für Aggregation gesammelt", 
                            messageBuffer.size(), AGGREGATION_SIZE);
                    
                    // Wenn 100 Nachrichten erreicht sind, aggregiere und sende
                    if (messageBuffer.size() >= AGGREGATION_SIZE) {
                        processAggregation(messageBuffer);
                        messageBuffer.clear();
                    }
                }
                
            } catch (InterruptedException e) {
                // Timeout oder Unterbrechung - normal beim Stoppen
                if (!running) {
                    break;
                }
            } catch (Exception e) {
                logger.error("Fehler im Crawler: {}", e.getMessage(), e);
            }
        }
        
        // Verarbeite verbleibende Nachrichten beim Beenden
        if (!messageBuffer.isEmpty()) {
            logger.info("Verarbeite verbleibende {} Nachrichten beim Beenden", messageBuffer.size());
            processAggregation(messageBuffer);
        }
        
        logger.info("Crawler-Thread beendet");
    }
    
    /**
     * Verarbeitet eine Aggregation von Nachrichten
     */
    private void processAggregation(List<MMSMessage> messages) {
        try {
            // Erstelle Aggregations-Objekt
            MMSAggregation aggregation = createAggregation(messages);
            
            // Konvertiere zu JSON
            String json = gson.toJson(aggregation);
            
            // Gebe auf Bildschirm aus
            displayAggregation(aggregation, json);

            // Leite JSON an Callback weiter (z.B. EDC HTTP-Push Pipeline)
            onAggregation.accept(json);
            totalAggregationsSent++;
            logger.info("Aggregation #{} an Pipeline weitergeleitet", totalAggregationsSent);
            
        } catch (Exception e) {
            logger.error("Fehler bei Aggregation: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Erstellt ein Aggregations-Objekt aus einer Liste von Nachrichten
     * Konvertiert in Array-Schema: ein Array für Timestamps, ein Array pro MMS-Label
     */
    private MMSAggregation createAggregation(List<MMSMessage> messages) {
        MMSAggregation aggregation = new MMSAggregation();
        
        aggregation.setAggregationId("AGG-" + totalAggregationsSent);
        aggregation.setAggregationTimestamp(LocalDateTime.now());
        aggregation.setMessageCount(messages.size());
        
        // Erstelle Timestamp-Array
        List<String> timestamps = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
        
        // Map für Data-Arrays: Key = MMS-Label, Value = DataArrayEntry mit keys und values Arrays
        Map<String, DataArrayEntry> dataArrays = new HashMap<>();
        
        // Temporäre Maps zum Sammeln der Daten
        Map<String, List<String>> keysMap = new HashMap<>();
        Map<String, List<Object>> valuesMap = new HashMap<>();
        
        // Iteriere über alle Nachrichten
        for (MMSMessage message : messages) {
            // Füge Timestamp hinzu
            timestamps.add(message.getTimestamp().format(formatter));
            
            // Iteriere über alle Datenpunkte in der Nachricht
            Map<String, Object> data = message.getData();
            if (data != null) {
                for (Map.Entry<String, Object> entry : data.entrySet()) {
                    // Ersetze "/" durch "-" im Label
                    String label = entry.getKey().replace("/", "-");
                    Object value = entry.getValue();
                    
                    // Erstelle Arrays für dieses Label, falls noch nicht vorhanden
                    keysMap.putIfAbsent(label, new ArrayList<>());
                    valuesMap.putIfAbsent(label, new ArrayList<>());
                    
                    // Füge Key und Value zu den jeweiligen Arrays hinzu
                    keysMap.get(label).add(label);
                    valuesMap.get(label).add(value);
                }
            }
        }
        
        // Erstelle DataArrayEntry-Objekte aus den gesammelten Daten
        for (String label : keysMap.keySet()) {
            DataArrayEntry entry = new DataArrayEntry(keysMap.get(label), valuesMap.get(label));
            dataArrays.put(label, entry);
        }
        
        aggregation.setTimestamps(timestamps);
        aggregation.setDataArrays(dataArrays);
        
        // Berechne Statistiken
        Map<String, Object> statistics = calculateStatistics(messages, dataArrays);
        aggregation.setStatistics(statistics);
        
        return aggregation;
    }
    
    /**
     * Berechnet Statistiken über die aggregierten Nachrichten
     */
    private Map<String, Object> calculateStatistics(List<MMSMessage> messages, Map<String, DataArrayEntry> dataArrays) {
        Map<String, Object> stats = new HashMap<>();
        
        // Zähle verschiedene Report IDs
        Map<String, Integer> reportIdCounts = new HashMap<>();
        Map<String, Integer> dataSetCounts = new HashMap<>();
        
        for (MMSMessage msg : messages) {
            // Report IDs
            String reportId = msg.getReportId();
            reportIdCounts.put(reportId, reportIdCounts.getOrDefault(reportId, 0) + 1);
            
            // DataSets
            String dataSet = msg.getDataSet();
            dataSetCounts.put(dataSet, dataSetCounts.getOrDefault(dataSet, 0) + 1);
        }
        
        stats.put("uniqueReportIds", reportIdCounts.size());
        stats.put("uniqueDataSets", dataSetCounts.size());
        stats.put("reportIdDistribution", reportIdCounts);
        stats.put("dataSetDistribution", dataSetCounts);
        
        // Anzahl verschiedener MMS-Labels
        stats.put("numberOfDataLabels", dataArrays.size());
        stats.put("dataLabels", new ArrayList<>(dataArrays.keySet()));
        
        // Zeitbereich
        if (!messages.isEmpty()) {
            LocalDateTime firstTimestamp = messages.get(0).getTimestamp();
            LocalDateTime lastTimestamp = messages.get(messages.size() - 1).getTimestamp();
            
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
            stats.put("firstMessageTimestamp", firstTimestamp.format(formatter));
            stats.put("lastMessageTimestamp", lastTimestamp.format(formatter));
        }
        
        return stats;
    }
    
    /**
     * Zeigt die Aggregation formatiert auf dem Bildschirm an
     */
    private void displayAggregation(MMSAggregation aggregation, String json) {
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║          MMS-Nachrichten Aggregation erstellt               ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║ Aggregation ID:      " + padRight(aggregation.getAggregationId(), 38) + "║");
        System.out.println("║ Zeitstempel:         " + padRight(
                aggregation.getAggregationTimestamp().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), 38) + "║");
        System.out.println("║ Anzahl Nachrichten:  " + padRight(String.valueOf(aggregation.getMessageCount()), 38) + "║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║ Array-Struktur:                                              ║");
        
        System.out.println("║   Timestamps Array:    " + padRight(String.valueOf(aggregation.getTimestamps().size()) + " Einträge", 36) + "║");
        System.out.println("║   Anzahl Data-Labels:  " + padRight(String.valueOf(aggregation.getDataArrays().size()), 36) + "║");
        
        // Zeige erste paar Data-Labels
        int labelCount = 0;
        for (String label : aggregation.getDataArrays().keySet()) {
            if (labelCount >= 3) {
                int remaining = aggregation.getDataArrays().size() - labelCount;
                if (remaining > 0) {
                    System.out.println("║     ... und " + padRight(remaining + " weitere Labels", 49) + "║");
                }
                break;
            }
            DataArrayEntry entry = aggregation.getDataArrays().get(label);
            int arraySize = entry.getValues() != null ? entry.getValues().size() : 0;
            String labelDisplay = label.length() > 35 ? label.substring(0, 32) + "..." : label;
            System.out.println("║     " + padRight(labelDisplay + ": " + arraySize + " Werte", 56) + "║");
            labelCount++;
        }
        
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║ Statistiken:                                                 ║");
        
        Map<String, Object> stats = aggregation.getStatistics();
        System.out.println("║   Unique Report IDs:   " + padRight(String.valueOf(stats.get("uniqueReportIds")), 36) + "║");
        System.out.println("║   Unique DataSets:     " + padRight(String.valueOf(stats.get("uniqueDataSets")), 36) + "║");
        
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║ JSON (gekürzt):                                              ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        
        // Zeige ersten Teil des JSON (max 10 Zeilen)
        String[] jsonLines = json.split("\n");
        int linesToShow = Math.min(10, jsonLines.length);
        for (int i = 0; i < linesToShow; i++) {
            String line = jsonLines[i];
            if (line.length() > 60) {
                line = line.substring(0, 57) + "...";
            }
            System.out.println("║ " + padRight(line, 60) + "║");
        }
        if (jsonLines.length > linesToShow) {
            System.out.println("║ " + padRight("... (" + (jsonLines.length - linesToShow) + " weitere Zeilen)", 60) + "║");
        }
        
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
    }
    
    /**
     * Gibt die Crawler-Statistiken aus
     */
    public void printStatistics() {
        System.out.println("\n=== Aggregation Crawler Statistik ===");
        System.out.println("Verarbeitete Nachrichten:  " + totalMessagesProcessed);
        System.out.println("Gesendete Aggregationen:   " + totalAggregationsSent);
        System.out.println("======================================\n");
    }

    public void resetStatistics() {
        totalMessagesProcessed = 0;
        totalAggregationsSent = 0;
        logger.info("Crawler-Statistiken zurückgesetzt");
    }
    
    // Helper method
    private String padRight(String s, int n) {
        if (s.length() >= n) {
            return s.substring(0, n);
        }
        return String.format("%-" + n + "s", s);
    }
    
    // Getter
    public boolean isRunning() {
        return running;
    }
    
    public long getTotalMessagesProcessed() {
        return totalMessagesProcessed;
    }
    
    public long getTotalAggregationsSent() {
        return totalAggregationsSent;
    }
}
