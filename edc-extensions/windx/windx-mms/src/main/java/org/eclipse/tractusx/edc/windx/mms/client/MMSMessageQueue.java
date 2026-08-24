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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Queue für MMS-Nachrichten in JSON-Format
 * Thread-sichere Queue für die Verarbeitung von MMS-Nachrichten
 */
public class MMSMessageQueue {
    
    private static final Logger logger = LoggerFactory.getLogger(MMSMessageQueue.class);
    
    private final BlockingQueue<String> queue;
    private final Gson gson;
    private final int capacity;
    
    /**
     * Erstellt eine neue Message Queue mit Standardkapazität (1000)
     */
    public MMSMessageQueue() {
        this(1000);
    }
    
    /**
     * Erstellt eine neue Message Queue mit angegebener Kapazität
     * 
     * @param capacity Maximale Anzahl von Nachrichten in der Queue
     */
    public MMSMessageQueue(int capacity) {
        this.capacity = capacity;
        this.queue = new LinkedBlockingQueue<>(capacity);
        
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
        
        logger.info("MMS Message Queue erstellt mit Kapazität: {}", capacity);
    }
    
    /**
     * Fügt eine MMS-Nachricht zur Queue hinzu (als JSON)
     * 
     * @param message Die MMS-Nachricht
     * @return true wenn erfolgreich hinzugefügt, false wenn Queue voll
     */
    public boolean enqueue(MMSMessage message) {
        try {
            String json = gson.toJson(message);
            boolean added = queue.offer(json);
            
            if (added) {
                logger.debug("Nachricht zur Queue hinzugefügt. Queue-Größe: {}", queue.size());
            } else {
                logger.warn("Queue ist voll! Nachricht konnte nicht hinzugefügt werden. Kapazität: {}", capacity);
            }
            
            return added;
        } catch (Exception e) {
            logger.error("Fehler beim Hinzufügen zur Queue: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Entnimmt eine Nachricht aus der Queue (blockierend)
     * 
     * @return JSON-String der Nachricht
     * @throws InterruptedException bei Unterbrechung
     */
    public String dequeue() throws InterruptedException {
        String json = queue.take();
        logger.debug("Nachricht aus Queue entnommen. Verbleibend: {}", queue.size());
        return json;
    }
    
    /**
     * Entnimmt eine Nachricht aus der Queue mit Timeout
     * 
     * @param timeout Timeout-Wert
     * @param unit Zeiteinheit
     * @return JSON-String der Nachricht oder null bei Timeout
     * @throws InterruptedException bei Unterbrechung
     */
    public String dequeue(long timeout, TimeUnit unit) throws InterruptedException {
        String json = queue.poll(timeout, unit);
        if (json != null) {
            logger.debug("Nachricht aus Queue entnommen. Verbleibend: {}", queue.size());
        }
        return json;
    }
    
    /**
     * Gibt die aktuelle Größe der Queue zurück
     * 
     * @return Anzahl der Nachrichten in der Queue
     */
    public int size() {
        return queue.size();
    }
    
    /**
     * Prüft ob die Queue leer ist
     * 
     * @return true wenn leer, sonst false
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }
    
    /**
     * Prüft ob die Queue voll ist
     * 
     * @return true wenn voll, sonst false
     */
    public boolean isFull() {
        return queue.remainingCapacity() == 0;
    }
    
    /**
     * Gibt die verbleibende Kapazität zurück
     * 
     * @return Anzahl der noch verfügbaren Plätze
     */
    public int remainingCapacity() {
        return queue.remainingCapacity();
    }
    
    /**
     * Leert die Queue
     */
    public void clear() {
        int size = queue.size();
        queue.clear();
        logger.info("Queue geleert. {} Nachrichten entfernt.", size);
    }
    
    /**
     * Gibt Statistiken der Queue aus
     */
    public void printStatistics() {
        System.out.println("\n=== Message Queue Statistik ===");
        System.out.println("Kapazität:           " + capacity);
        System.out.println("Aktuelle Größe:      " + queue.size());
        System.out.println("Verbleibend:         " + queue.remainingCapacity());
        System.out.println("Auslastung:          " + String.format("%.1f%%", (queue.size() * 100.0 / capacity)));
        System.out.println("================================\n");
    }
}
