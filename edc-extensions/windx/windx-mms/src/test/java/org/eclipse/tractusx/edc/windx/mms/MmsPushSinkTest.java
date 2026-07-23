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

import com.sun.net.httpserver.HttpServer;
import org.eclipse.edc.connector.dataplane.spi.DataFlow;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSource;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSourceFactory;
import org.eclipse.edc.connector.dataplane.spi.pipeline.StreamResult;
import org.eclipse.edc.spi.monitor.ConsoleMonitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.eclipse.tractusx.edc.nonfiniteproviderpush.core.evaluator.FinitenessEvaluatorImpl;
import org.eclipse.tractusx.edc.nonfiniteproviderpush.core.pipeline.NonFiniteCapablePipelineService;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.spi.constants.CoreConstants.EDC_NAMESPACE;

/**
 * Beweist den {@code Mms-PUSH}-Sink ohne Netzwerk-Abhängigkeiten: ein lokaler JDK-{@code HttpServer}
 * empfängt die POSTs, eine synthetische unendliche Source (Muster {@code NonFiniteSinkConsumptionTest})
 * liefert die "Aggregationen". Abgedeckt: laufende Zustellung, sauberes Stream-Ende per {@code close()},
 * Fehlertoleranz (transienter 5xx bricht den Strom nicht) und die Factory-Validierung.
 */
class MmsPushSinkTest {

    private static final DataSource.Part POISON = part("__POISON__");

    private HttpServer server;
    private final BlockingQueue<Received> received = new LinkedBlockingQueue<>();
    /** Anzahl der Requests, die der Server mit HTTP 500 quittieren soll, bevor er 200 liefert. */
    private final AtomicInteger failFirst = new AtomicInteger(0);
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/log", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            received.add(new Received(body, contentType));
            var status = failFirst.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0 ? 500 : 200;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/log?signal=test";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void postsEveryAggregation_withJsonContentType() throws Exception {
        var source = new InfiniteSource(100);
        var sink = new MmsPushSink(baseUrl, "tp-1", new ConsoleMonitor());

        var future = sink.transfer(source);

        var first = received.poll(5, TimeUnit.SECONDS);
        var second = received.poll(5, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first.body).startsWith("{\"agg\":");
        assertThat(first.contentType).isEqualTo("application/json");
        assertThat(second.body).isNotEqualTo(first.body);

        source.close();
        var result = future.get(5, TimeUnit.SECONDS);
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void closeEndsStream_noFurtherPosts() throws Exception {
        var source = new InfiniteSource(50);
        var sink = new MmsPushSink(baseUrl, "tp-2", new ConsoleMonitor());

        var future = sink.transfer(source);
        assertThat(received.poll(5, TimeUnit.SECONDS)).isNotNull();

        source.close();
        assertThat(future.get(5, TimeUnit.SECONDS).succeeded()).isTrue();

        received.clear();
        Thread.sleep(400); // mehrere Emissions-Intervalle: ein weiterlaufender Sink würde hier posten
        assertThat(received).isEmpty();
    }

    @Test
    void transientServerErrorDoesNotBreakStream() throws Exception {
        failFirst.set(1); // erster POST wird mit HTTP 500 quittiert
        var source = new InfiniteSource(50);
        var sink = new MmsPushSink(baseUrl, "tp-3", new ConsoleMonitor());

        var future = sink.transfer(source);

        // Der 500er-Request kommt an UND danach weitere -> der Strom hat den Fehler überlebt.
        assertThat(received.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(received.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(received.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(future.isDone()).isFalse();

        source.close();
        assertThat(future.get(5, TimeUnit.SECONDS).succeeded()).isTrue();
    }

    @Test
    void factoryValidatesBaseUrl() {
        var factory = new MmsPushSinkFactory(new ConsoleMonitor());

        assertThat(factory.supportedType()).isEqualTo("Mms");
        assertThat(factory.validateRequest(pushRequest(null)).failed()).isTrue();
        assertThat(factory.validateRequest(pushRequest("kein-schema")).failed()).isTrue();
        assertThat(factory.validateRequest(pushRequest("http://ziel:4000/log?signal=x")).succeeded()).isTrue();
    }

    /**
     * Integrationsvariante mit der echten {@link NonFiniteCapablePipelineService}: bei
     * {@code edc:isNonFinite=true} bleibt das Pipeline-Future offen, während der Sink laufend POSTet;
     * {@code terminate} schließt die Source und stoppt den Strom.
     */
    @Test
    void nonFinitePipeline_futureStaysOpen_terminateStopsPosts() throws Exception {
        var pipelineService = new NonFiniteCapablePipelineService(new ConsoleMonitor(), new FinitenessEvaluatorImpl());
        var source = new InfiniteSource(100);
        pipelineService.registerFactory(sourceFactory(source));
        pipelineService.registerFactory(new MmsPushSinkFactory(new ConsoleMonitor()));

        var processId = UUID.randomUUID().toString();
        var request = DataFlowStartMessage.Builder.newInstance()
                .processId(processId)
                .sourceDataAddress(DataAddress.Builder.newInstance()
                        .type("Mms")
                        .property(EDC_NAMESPACE + "isNonFinite", "true")
                        .build())
                .destinationDataAddress(DataAddress.Builder.newInstance()
                        .type("Mms")
                        .property("baseUrl", baseUrl)
                        .build())
                .build();
        assertThat(pipelineService.validate(request).succeeded()).isTrue();

        var future = pipelineService.transfer(request);

        assertThat(received.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(received.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(future.isDone()).isFalse();

        pipelineService.terminate(DataFlow.Builder.newInstance().id(processId).build());
        pollUntil(() -> !source.running(), 5_000);
        assertThat(source.running()).isFalse();

        received.clear();
        Thread.sleep(400);
        assertThat(received).isEmpty();
    }

    private static void pollUntil(BooleanSupplier condition, long timeoutMs) {
        var deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static DataFlowStartMessage pushRequest(String baseUrl) {
        var destination = DataAddress.Builder.newInstance().type("Mms");
        if (baseUrl != null) {
            destination.property("baseUrl", baseUrl);
        }
        return DataFlowStartMessage.Builder.newInstance()
                .processId(UUID.randomUUID().toString())
                .sourceDataAddress(DataAddress.Builder.newInstance().type("Mms").build())
                .destinationDataAddress(destination.build())
                .build();
    }

    private record Received(String body, String contentType) {
    }

    // --- synthetische, unendliche Source (Muster NonFiniteSinkConsumptionTest) --------------------------------

    private static final class InfiniteSource implements DataSource {
        private final AtomicInteger counter = new AtomicInteger();
        private final long intervalMs;
        private volatile boolean closed = false;

        InfiniteSource(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        boolean running() {
            return !closed;
        }

        @Override
        public StreamResult<Stream<Part>> openPartStream() {
            Stream<Part> stream = Stream.generate(this::next).takeWhile(p -> p != POISON);
            return StreamResult.success(stream);
        }

        private Part next() {
            if (closed) {
                return POISON;
            }
            try {
                Thread.sleep(intervalMs); // simuliert die Aggregationsrate
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return POISON;
            }
            return closed ? POISON : part("{\"agg\":" + counter.incrementAndGet() + "}");
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static DataSourceFactory sourceFactory(DataSource source) {
        return new DataSourceFactory() {
            @Override
            public String supportedType() {
                return "Mms";
            }

            @Override
            public DataSource createSource(DataFlowStartMessage request) {
                return source;
            }

            @Override
            public @NotNull Result<Void> validateRequest(DataFlowStartMessage request) {
                return Result.success();
            }
        };
    }

    private static DataSource.Part part(String content) {
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        return new DataSource.Part() {
            @Override
            public String name() {
                return "part";
            }

            @Override
            public InputStream openStream() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public String mediaType() {
                return "application/json";
            }
        };
    }
}
