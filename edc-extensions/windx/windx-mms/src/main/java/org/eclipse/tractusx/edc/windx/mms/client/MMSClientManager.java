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

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manager-Klasse für MMS-Client mit automatischer Wiederverbindung
 * und konfiguriertem Polling aller observedSensors.
 */
public class MMSClientManager {

    private static final Logger logger = LoggerFactory.getLogger(MMSClientManager.class);

    private MMSClient client;
    private MMSClientConfig config;
    private MMSMessageQueue messageQueue;
    private ScheduledExecutorService reconnectScheduler;
    private ScheduledExecutorService updateScheduler;
    private boolean running;

    /** Läuft als laufende Invoke-ID für MMS Read Requests (0x20..0xFF, dann Wrap). */
    private final AtomicInteger invokeIdCounter = new AtomicInteger(0x20);

    public MMSClientManager(MMSClientConfig config, MMSMessageQueue messageQueue) {
        this.config = config;
        this.messageQueue = messageQueue;
        this.client = new MMSClient(config.getServerHost(), config.getServerPort());
        this.running = false;
    }

    /**
     * Startet den MMS-Client: verbindet, abonniert Reports für die Domain
     * aus der Config und startet den periodischen Sensor-Poll.
     */
    public void start() {
        if (running) {
            logger.warn("Client läuft bereits");
            return;
        }

        running = true;
        logger.info("Starte MMS-Client mit Konfiguration: {}", config);

        try {
            connectAndSubscribe();

            if (config.isAutoReconnect()) {
                startReconnectScheduler();
            }

            startUpdateScheduler();

        } catch (IOException e) {
            logger.error("Fehler beim Starten: {}", e.getMessage());
            if (config.isAutoReconnect()) {
                logger.info("Auto-Reconnect aktiviert, versuche erneut...");
                startReconnectScheduler();
            }
        }
    }

    /**
     * Stoppt den MMS-Client und alle Hintergrund-Scheduler.
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        logger.info("Stoppe MMS-Client...");

        shutdownScheduler(updateScheduler, "Update-Scheduler");
        shutdownScheduler(reconnectScheduler, "Reconnect-Scheduler");

        client.disconnect();
        logger.info("MMS-Client gestoppt");
    }

    // -------------------------------------------------------------------------
    // Verbindung & Subscription
    // -------------------------------------------------------------------------

    /**
     * Verbindet zum Server und abonniert Reports für die konfigurierte Domain.
     */
    private void connectAndSubscribe() throws IOException {
        client.connect();
        client.readServerModel();
        client.subscribeToReports(config.getLogicalDevice());
        logger.info("Reports für Domain '{}' abonniert", config.getLogicalDevice());
    }

    // -------------------------------------------------------------------------
    // Scheduler: Reconnect
    // -------------------------------------------------------------------------

    private void startReconnectScheduler() {
        if (reconnectScheduler == null || reconnectScheduler.isShutdown()) {
            reconnectScheduler = Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, "mms-reconnect"));
        }

        reconnectScheduler.scheduleWithFixedDelay(() -> {
            if (running && !client.isConnected()) {
                logger.info("Verbindung verloren – versuche Wiederverbindung...");
                try {
                    connectAndSubscribe();
                    logger.info("Wiederverbindung erfolgreich");
                } catch (IOException e) {
                    logger.error("Wiederverbindung fehlgeschlagen: {}", e.getMessage());
                }
            }
        }, config.getReconnectInterval(), config.getReconnectInterval(), TimeUnit.MILLISECONDS);
    }

    // -------------------------------------------------------------------------
    // Scheduler: Sensor-Polling
    // -------------------------------------------------------------------------

    /**
     * Startet den periodischen Scheduler, der alle konfigurierten Sensoren
     * mit der in der Config eingestellten updateInterval-Rate abfragt.
     */
    private void startUpdateScheduler() {
        List<String> sensors = config.getObservedSensors();
        if (sensors == null || sensors.isEmpty()) {
            logger.warn("Keine Sensoren in der Konfiguration definiert – kein Polling gestartet");
            return;
        }

        logger.info("Starte Sensor-Polling: {} Sensor(en), Intervall: {} ms",
                sensors.size(), config.getUpdateInterval());

        if (updateScheduler == null || updateScheduler.isShutdown()) {
            updateScheduler = Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, "mms-update"));
        }

        updateScheduler.scheduleWithFixedDelay(
                this::pollAllSensors,
                0,
                config.getUpdateInterval(),
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Fragt alle konfigurierten Sensoren einmalig ab.
     * Wird vom updateScheduler periodisch aufgerufen.
     */
    private void pollAllSensors() {
        if (!client.isConnected()) {
            logger.debug("Polling übersprungen – nicht verbunden");
            return;
        }

        String domain = config.getLogicalDevice();
        List<String> sensors = config.getObservedSensors();

        logger.debug("Starte Polling-Zyklus: {} Sensor(en) für Domain '{}'", sensors.size(), domain);

        MMSMessage message = new MMSMessage();
        message.setReportId(domain);
        message.setDataSet(domain);

        for (String sensor : sensors) {
            int invokeId = nextInvokeId();
            try {
                String value = client.readVariable(domain, sensor, invokeId);
                if (value != null) {
                    message.addDataPoint(sensor, value);
                    logger.info("Sensor gelesen: {}/{} = {} (invokeId=0x{})",
                            domain, sensor, value, Integer.toHexString(invokeId));
                } else {
                    logger.warn("Kein Wert für {}/{}", domain, sensor);
                }
            } catch (IOException e) {
                logger.error("Kommunikationsfehler beim Lesen von {}/{}: {}", domain, sensor, e.getMessage());
            }
        }

        if (!message.getData().isEmpty()) {
            boolean enqueued = messageQueue.enqueue(message);
            if (enqueued) {
                logger.debug("MMSMessage mit {} Datenpunkt(en) in Queue eingereiht", message.getData().size());
            } else {
                logger.warn("Queue voll – MMSMessage konnte nicht eingereiht werden");
            }
        }
    }

    /**
     * Liefert die nächste Invoke-ID im Bereich 0x20–0xFF (danach Wrap auf 0x20).
     */
    private int nextInvokeId() {
        return invokeIdCounter.getAndUpdate(id -> id >= 0xFF ? 0x20 : id + 1);
    }

    // -------------------------------------------------------------------------
    // Hilfsmethoden
    // -------------------------------------------------------------------------

    private void shutdownScheduler(ScheduledExecutorService scheduler, String name) {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                    logger.warn("{} konnte nicht sauber beendet werden", name);
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public MMSClient getClient() {
        return client;
    }

    public boolean isRunning() {
        return running;
    }
}
