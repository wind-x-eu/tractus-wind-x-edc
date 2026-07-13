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

import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSink;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSinkFactory;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSource;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSourceFactory;
import org.eclipse.edc.connector.dataplane.spi.pipeline.StreamResult;
import org.eclipse.edc.connector.dataplane.util.sink.ParallelSink;
import org.eclipse.edc.spi.monitor.ConsoleMonitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.telemetry.Telemetry;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.eclipse.edc.tractusx.non.finite.provider.push.spi.FinitenessEvaluator;
import org.eclipse.tractusx.edc.nonfiniteproviderpush.core.evaluator.FinitenessEvaluatorImpl;
import org.eclipse.tractusx.edc.nonfiniteproviderpush.core.pipeline.NonFiniteCapablePipelineService;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.spi.constants.CoreConstants.EDC_NAMESPACE;

/**
 * Deterministischer Beweis (kein Netzwerk) für den Option-A-Kern: der Standard-{@link ParallelSink}-Mechanismus
 * (den der HttpDataSink erbt: {@code PartitionIterator.streamOf(...).map(post).collect(asyncAllOf())}) konsumiert
 * einen <b>unendlichen</b> Source-Stream <b>laufend</b> — postet also fortlaufend pro Part — und die
 * {@link NonFiniteCapablePipelineService} hält das transfer-Future offen (non-finite).
 *
 * <p>Widerlegt die Sorge „asyncAllOf blockiert und postet nie": {@code collect()} traversiert lazy, jeder gezogene
 * Part löst sofort {@code transferParts} aus.</p>
 */
class NonFiniteSinkConsumptionTest {

    private static final DataSource.Part POISON = part("__POISON__");

    @Test
    void parallelSinkConsumesInfiniteStreamContinuously_andFutureStaysOpen() {
        var executor = Executors.newFixedThreadPool(4);
        try {
            var pipelineService = new NonFiniteCapablePipelineService(new ConsoleMonitor(), realEvaluator());

            var source = new InfiniteSource();
            var sink = new CapturingSink(executor);

            pipelineService.registerFactory(sourceFactory(source));
            pipelineService.registerFactory(sinkFactory(sink));

            var request = DataFlowStartMessage.Builder.newInstance()
                    .processId(UUID.randomUUID().toString())
                    .sourceDataAddress(DataAddress.Builder.newInstance()
                            .type("any")
                            .property(EDC_NAMESPACE + "isNonFinite", "true")
                            .build())
                    .destinationDataAddress(DataAddress.Builder.newInstance().type("any").build())
                    .build();

            var future = pipelineService.transfer(request);

            // Der ParallelSink postet fortlaufend, während der Stream traversiert wird → mehrere Parts zeitnah.
            pollUntil(() -> sink.captured.size() >= 3, 10_000);
            assertThat(sink.captured.size())
                    .withFailMessage("ParallelSink hat den unendlichen Stream nicht laufend konsumiert (captured=%d)",
                            sink.captured.size())
                    .isGreaterThanOrEqualTo(3);

            // Non-finite: das Future komplettiert NICHT (nur bei Fehler).
            assertThat(future.isDone()).isFalse();
            assertThat(sink.captured).anyMatch(s -> s.startsWith("agg-"));

            // Terminate schließt die Source → unendlicher Stream endet sauber.
            source.close();
            pollUntil(() -> !source.running(), 5_000);
            assertThat(source.running()).isFalse();
        } finally {
            executor.shutdownNow();
        }
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

    private static FinitenessEvaluator realEvaluator() {
        return new FinitenessEvaluatorImpl();
    }

    // --- synthetische, unendliche Source (blockt langsam, bis close()) -----------------------------------------

    private static final class InfiniteSource implements DataSource {
        private final AtomicInteger counter = new AtomicInteger();
        private volatile boolean closed = false;

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
                Thread.sleep(200); // simuliert die Aggregationsrate
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return POISON;
            }
            return closed ? POISON : part("agg-" + counter.incrementAndGet());
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    // --- ParallelSink-Subtyp: erfasst die geposteten Parts (partitionSize=1) -----------------------------------

    private static final class CapturingSink extends ParallelSink {
        private final List<String> captured = new CopyOnWriteArrayList<>();

        CapturingSink(ExecutorService executor) {
            this.requestId = "test";
            this.partitionSize = 1;
            this.executorService = executor;
            this.monitor = new ConsoleMonitor();
            this.telemetry = new Telemetry();
        }

        @Override
        protected StreamResult<Object> transferParts(List<DataSource.Part> parts) {
            for (var p : parts) {
                try {
                    captured.add(new String(p.openStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (Exception e) {
                    return StreamResult.error(e.getMessage());
                }
            }
            return StreamResult.success();
        }
    }

    // --- Factories ---------------------------------------------------------------------------------------------

    private static DataSourceFactory sourceFactory(DataSource source) {
        return new DataSourceFactory() {
            @Override
            public String supportedType() {
                return "any";
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

    private static DataSinkFactory sinkFactory(DataSink sink) {
        return new DataSinkFactory() {
            @Override
            public String supportedType() {
                return "any";
            }

            @Override
            public DataSink createSink(DataFlowStartMessage request) {
                return sink;
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
        };
    }
}
