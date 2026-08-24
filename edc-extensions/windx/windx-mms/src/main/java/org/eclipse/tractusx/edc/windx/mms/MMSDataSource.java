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

package org.eclipse.tractusx.edc.windx.mms;

import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSource;
import org.eclipse.edc.connector.dataplane.spi.pipeline.StreamResult;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.eclipse.tractusx.edc.windx.mms.client.MMSAggregationCrawler;
import org.eclipse.tractusx.edc.windx.mms.client.MMSClientConfig;
import org.eclipse.tractusx.edc.windx.mms.client.MMSClientManager;
import org.eclipse.tractusx.edc.windx.mms.client.MMSMessageQueue;
import org.eclipse.tractusx.edc.windx.mms.client.MMSQueueListener;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.eclipse.edc.spi.constants.CoreConstants.EDC_NAMESPACE;

/**
 * MMS-{@link DataSource} mit zwei Betriebsarten (gesteuert über die Source-DataAddress):
 *
 * <ul>
 *   <li><b>Finite</b> (Default, für {@code Mms-PULL} und push-once): {@link #openPartStream()} startet die
 *       MMS-Engine, wartet (max 120 s) auf <b>eine</b> Aggregation und gibt sie als einen JSON-{@code Part} zurück.</li>
 *   <li><b>Non-finite</b> (kontinuierlicher Push, wenn {@code edc:isNonFinite=="true"}): {@link #openPartStream()}
 *       liefert einen <b>unendlichen, blockierenden</b> Stream — je eine JSON-Aggregation pro Element —, den die
 *       {@code NonFiniteCapablePipelineService} zusammen mit dem HttpData-Sink laufend an die Ziel-URL POSTet.
 *       {@link #close()} (Transfer-Terminate) legt eine Poison-Pill in die Queue → der Stream endet sauber.</li>
 * </ul>
 */
public class MMSDataSource implements DataSource {

    private static final String PROP_HOST = "mms:serverHost";
    private static final String PROP_PORT = "mms:serverPort";
    private static final String PROP_LOGICAL_DEVICE = "mms:logicalDevice";
    private static final String PROP_SENSORS = "mms:sensors";
    private static final String PROP_UPDATE_INTERVAL = "mms:updateInterval";
    private static final String PROP_RECONNECT_INTERVAL = "mms:reconnectInterval";
    /** Gleiche Property wie {@code FinitenessEvaluatorImpl} liest → Pipeline-Gate und Source-Modus stimmen überein. */
    private static final String PROP_NON_FINITE = EDC_NAMESPACE + "isNonFinite";

    /** Sentinel-JSON, das {@link #close()} in die Queue legt, um den unendlichen Stream zu beenden. */
    private static final String POISON = "__WINDX_MMS_POISON__";
    /** Sentinel-Part, an dem {@code takeWhile} den Stream terminiert. */
    private static final Part POISON_PART = new JsonPart(new byte[0]);

    private final MMSClientConfig config;
    private final boolean nonFinite;

    // Lebenszyklus des non-finite-Streaming-Modus (über close() gesteuert):
    private volatile boolean closed = false;
    private MMSClientManager streamingManager;
    private MMSAggregationCrawler streamingCrawler;
    private LinkedBlockingQueue<String> streamingQueue;

    public MMSDataSource(DataFlowStartMessage request) {
        var props = request.getSourceDataAddress().getProperties();
        this.config = buildConfig(props);
        this.nonFinite = Boolean.parseBoolean(request.getSourceDataAddress().getStringProperty(PROP_NON_FINITE));
    }

    @Override
    public StreamResult<Stream<Part>> openPartStream() {
        return nonFinite ? openContinuousStream() : openSingleAggregation();
    }

    // -------------------------------------------------------------------------
    // Finite: eine Aggregation (Pull / push-once) — unverändertes Verhalten
    // -------------------------------------------------------------------------
    private StreamResult<Stream<Part>> openSingleAggregation() {
        LinkedBlockingQueue<String> resultQueue = new LinkedBlockingQueue<>(1);

        MMSMessageQueue messageQueue = new MMSMessageQueue(1000);
        new MMSQueueListener(messageQueue);
        MMSAggregationCrawler crawler = new MMSAggregationCrawler(messageQueue, resultQueue::offer);
        crawler.start();

        MMSClientManager manager = new MMSClientManager(config, messageQueue);
        manager.start();

        try {
            String json = resultQueue.poll(120, TimeUnit.SECONDS);
            if (json == null) {
                return StreamResult.error("Timeout: keine MMS-Daten innerhalb von 120 Sekunden erhalten");
            }
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            return StreamResult.success(Stream.of(new JsonPart(bytes)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StreamResult.error("Unterbrochen beim Warten auf MMS-Daten");
        } finally {
            crawler.stop();
            manager.stop();
        }
    }

    // -------------------------------------------------------------------------
    // Non-finite: unendlicher Strom von Aggregationen (kontinuierlicher Push)
    // -------------------------------------------------------------------------
    private StreamResult<Stream<Part>> openContinuousStream() {
        streamingQueue = new LinkedBlockingQueue<>(1000);

        MMSMessageQueue messageQueue = new MMSMessageQueue(1000);
        new MMSQueueListener(messageQueue);
        // Crawler-Callback puffert jede Aggregation; bei voller Queue wird verworfen (Backpressure, BIBA-nah).
        streamingCrawler = new MMSAggregationCrawler(messageQueue, streamingQueue::offer);
        streamingCrawler.start();

        streamingManager = new MMSClientManager(config, messageQueue);
        streamingManager.start();

        Stream<Part> stream = Stream.generate(this::takeNextOrPoison)
                .takeWhile(part -> part != POISON_PART);
        return StreamResult.success(stream);
    }

    /** Blockiert bis zur nächsten Aggregation; liefert {@link #POISON_PART} sobald {@link #close()} lief. */
    private Part takeNextOrPoison() {
        while (!closed) {
            try {
                String json = streamingQueue.poll(500, TimeUnit.MILLISECONDS);
                if (POISON.equals(json)) {
                    return POISON_PART;
                }
                if (json != null) {
                    return new JsonPart(json.getBytes(StandardCharsets.UTF_8));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return POISON_PART;
            }
        }
        return POISON_PART;
    }

    @Override
    public void close() {
        closed = true;
        if (streamingQueue != null) {
            streamingQueue.offer(POISON); // blockierendes takeNextOrPoison entsperren
        }
        if (streamingCrawler != null) {
            streamingCrawler.stop();
        }
        if (streamingManager != null) {
            streamingManager.stop();
        }
    }

    private MMSClientConfig buildConfig(Map<String, Object> props) {
        MMSClientConfig cfg = new MMSClientConfig();
        if (props.containsKey(PROP_HOST)) {
            cfg.setServerHost((String) props.get(PROP_HOST));
        }
        if (props.containsKey(PROP_PORT)) {
            cfg.setServerPort(Integer.parseInt((String) props.get(PROP_PORT)));
        }
        if (props.containsKey(PROP_LOGICAL_DEVICE)) {
            cfg.setLogicalDevice((String) props.get(PROP_LOGICAL_DEVICE));
        }
        if (props.containsKey(PROP_UPDATE_INTERVAL)) {
            cfg.setUpdateInterval(Integer.parseInt((String) props.get(PROP_UPDATE_INTERVAL)));
        }
        if (props.containsKey(PROP_RECONNECT_INTERVAL)) {
            cfg.setReconnectInterval(Integer.parseInt((String) props.get(PROP_RECONNECT_INTERVAL)));
        }
        if (props.containsKey(PROP_SENSORS)) {
            var sensors = new ArrayList<String>(
                    Arrays.asList(((String) props.get(PROP_SENSORS)).split(","))
            );
            sensors.replaceAll(String::trim);
            cfg.setObservedSensors(sensors);
        }
        return cfg;
    }

    private static class JsonPart implements Part {
        private final byte[] data;

        JsonPart(byte[] data) {
            this.data = data;
        }

        @Override
        public String name() {
            return "mms-aggregation.json";
        }

        @Override
        public InputStream openStream() {
            return new ByteArrayInputStream(data);
        }

        @Override
        public String mediaType() {
            return "application/json";
        }

        @Override
        public long size() {
            return data.length;
        }
    }
}
