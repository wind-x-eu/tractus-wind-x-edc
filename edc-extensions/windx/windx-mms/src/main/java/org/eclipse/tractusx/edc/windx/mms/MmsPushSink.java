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
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSource;
import org.eclipse.edc.connector.dataplane.spi.pipeline.StreamResult;
import org.eclipse.edc.spi.monitor.Monitor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sink für den Transfertyp {@code Mms-PUSH} (portierter BIBA-{@code MMSPushTask}): POSTet jede
 * JSON-Aggregation der Source einzeln an die {@code baseUrl} der Destination-DataAddress.
 *
 * <p>Bewusst <b>kein</b> {@code ParallelSink}/{@code asyncAllOf}: der kontinuierliche (non-finite) Strom
 * liefe dort auf einem <b>geteilten</b> Executor, dessen Thread in {@code collect()} dauerhaft blockiert,
 * während die Partition-Futures unbegrenzt akkumulieren (s. {@code docs/windx/phase-mms-push-continuous-plan.md}
 * §2). Stattdessen: <b>ein eigener</b> Daemon-Thread pro Sink-Instanz, der den blockierenden Part-Stream
 * synchron traversiert — kein Batching, keine Future-Akkumulation, keine Starvation anderer Transfers.</p>
 *
 * <p>Fehlerpolitik (folgt aus {@code NonFiniteCapablePipelineService}): ein <b>failed</b>-Result macht den
 * non-finiten DataFlow terminal FAILED. Einzelne POST-Fehler (Logger kurz weg, non-2xx) werden deshalb nur
 * geloggt und der Strom läuft weiter (BIBA-treu). Nur wenn schon das Öffnen der Source scheitert, ist das
 * Ergebnis failed. Das Stream-Ende (Poison-Pill nach Terminate → {@code MMSDataSource.close()}) komplettiert
 * mit success — bei non-finite wird das von der Pipeline geschluckt, der Flow ist dann bereits terminiert.</p>
 */
public class MmsPushSink implements DataSink {

    /** Geteilter HTTP-Client (Verbindungsaufbau max. 10 s); gesendet wird synchron auf dem Sink-Thread. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final URI target;
    private final String processId;
    private final Monitor monitor;
    private final ExecutorService executor;
    private final AtomicLong posted = new AtomicLong();

    public MmsPushSink(String baseUrl, String processId, Monitor monitor) {
        this.target = URI.create(baseUrl); // von der Factory validiert
        this.processId = processId;
        this.monitor = monitor;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "windx-mms-push-" + processId);
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public CompletableFuture<StreamResult<Object>> transfer(DataSource source) {
        return CompletableFuture.supplyAsync(() -> pump(source), executor)
                .whenComplete((result, throwable) -> executor.shutdown());
    }

    private StreamResult<Object> pump(DataSource source) {
        var streamResult = source.openPartStream();
        if (streamResult.failed()) {
            // Einziger failed-Pfad: die Source kam gar nicht erst hoch -> DataFlow darf FAILED werden.
            return StreamResult.error("Mms-PUSH %s: Source nicht zu öffnen: %s"
                    .formatted(processId, streamResult.getFailureDetail()));
        }
        try (var parts = streamResult.getContent()) {
            parts.forEach(this::postPart);
        } catch (Exception e) {
            return StreamResult.error("Mms-PUSH %s: Part-Stream abgebrochen: %s".formatted(processId, e.getMessage()));
        }
        monitor.info("Mms-PUSH %s beendet: %d Aggregation(en) an '%s' gePOSTet".formatted(processId, posted.get(), target));
        return StreamResult.success();
    }

    private void postPart(DataSource.Part part) {
        try {
            byte[] body;
            try (var in = part.openStream()) {
                body = in.readAllBytes();
            }
            var request = HttpRequest.newBuilder(target)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            var response = HTTP.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 == 2) {
                posted.incrementAndGet();
            } else {
                monitor.warning("Mms-PUSH %s: Ziel '%s' antwortete HTTP %d — Aggregation verworfen, Strom läuft weiter"
                        .formatted(processId, target, response.statusCode()));
            }
        } catch (InterruptedException e) {
            // Interrupt-Flag wiederherstellen; die Source beendet den Stream über ihre Poison-Pill.
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            monitor.warning("Mms-PUSH %s: POST an '%s' fehlgeschlagen (%s) — Strom läuft weiter"
                    .formatted(processId, target, e.getMessage()));
        }
    }
}
