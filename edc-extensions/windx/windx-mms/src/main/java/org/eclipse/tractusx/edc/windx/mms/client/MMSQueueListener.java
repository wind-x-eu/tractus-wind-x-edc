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

/**
 * Message Listener der MMS-Nachrichten in JSON umwandelt und in die Queue einträgt
 */
public class MMSQueueListener implements MMSClient.MMSMessageListener {
    
    private static final Logger logger = LoggerFactory.getLogger(MMSQueueListener.class);
    
    private final MMSMessageQueue queue;
    private long receivedCount = 0;
    private long enqueuedCount = 0;
    private long droppedCount = 0;
    
    /**
     * Erstellt einen neuen Queue Listener
     * 
     * @param queue Die Message Queue für JSON-Nachrichten
     */
    public MMSQueueListener(MMSMessageQueue queue) {
        this.queue = queue;
        logger.info("MMS Queue Listener erstellt");
    }
    
    @Override
    public void onMessageReceived(MMSMessage message) {
        receivedCount++;
        
        try {
            // Konvertiere Nachricht in JSON und füge zur Queue hinzu
            boolean success = queue.enqueue(message);
            
            if (success) {
                enqueuedCount++;
                logger.debug("Nachricht #{} in Queue eingetragen: {}", enqueuedCount, message.getReportId());
                
                // Optional: Zeige JSON auf Konsole
                if (logger.isDebugEnabled()) {
                    logger.debug("JSON:\n{}", message.toJson());
                }
            } else {
                droppedCount++;
                logger.warn("Nachricht konnte nicht in Queue eingetragen werden (Queue voll). Dropped: {}", droppedCount);
            }
            
        } catch (Exception e) {
            droppedCount++;
            logger.error("Fehler beim Verarbeiten der Nachricht: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Gibt Statistiken des Listeners aus
     */
    public void printStatistics() {
        System.out.println("\n=== Queue Listener Statistik ===");
        System.out.println("Empfangen:   " + receivedCount);
        System.out.println("In Queue:    " + enqueuedCount);
        System.out.println("Verworfen:   " + droppedCount);
        System.out.println("Erfolgsrate: " + String.format("%.1f%%", 
                receivedCount > 0 ? (enqueuedCount * 100.0 / receivedCount) : 0));
        System.out.println("================================\n");
    }
    
    /**
     * Setzt die Statistiken zurück
     */
    public void resetStatistics() {
        receivedCount = 0;
        enqueuedCount = 0;
        droppedCount = 0;
        logger.info("Statistiken zurückgesetzt");
    }
    
    public long getReceivedCount() {
        return receivedCount;
    }
    
    public long getEnqueuedCount() {
        return enqueuedCount;
    }
    
    public long getDroppedCount() {
        return droppedCount;
    }
}
