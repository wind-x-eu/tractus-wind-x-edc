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

import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.eclipse.edc.spi.types.domain.transfer.FlowType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.spi.constants.CoreConstants.EDC_NAMESPACE;

/**
 * Hardware-IT für den <b>kontinuierlichen</b> {@code Mms-PUSH} (non-finite): prüft, dass
 * {@link MMSDataSource} im non-finite-Modus ({@code edc:isNonFinite=true}) gegen den echten Server
 * ({@code agilevt-dev.ikap.biba.uni-bremen.de:102}, {@code WPPS}) einen <b>unendlichen</b> Strom von
 * Aggregationen liefert (mehr als eine) und dass {@link MMSDataSource#close()} den Strom sauber beendet.
 *
 * <p>Das ist der Kern des Option-A-Ansatzes (die {@code NonFiniteCapablePipelineService} + der Standard-
 * HttpDataSink konsumieren genau diesen Strom laufend). Netzwerkabhängig → {@code @Tag("MmsHardwareIT")}.</p>
 */
@Tag("MmsHardwareIT")
class MmsPushContinuousIT {

    private static final String HOST = "agilevt-dev.ikap.biba.uni-bremen.de";

    @Test
    void streamsMultipleAggregationsAndStopsOnClose() throws Exception {
        var source = DataAddress.Builder.newInstance()
                .type("Mms")
                .property("mms:serverHost", HOST)
                .property("mms:serverPort", "102")
//                .property("mms:logicalDevice", "WPPS")
                .property("mms:sensors", "WAVL1$ST$OpTmsRs$actTmVal$stVal")
                .property("mms:updateInterval", "1000")
                .property(EDC_NAMESPACE + "isNonFinite", "true")
                .build();

        var request = DataFlowStartMessage.Builder.newInstance()
                .processId(UUID.randomUUID().toString())
                .sourceDataAddress(source)
                .flowType(FlowType.PUSH)
                .build();

        var dataSource = new MMSDataSource(request);
        var result = dataSource.openPartStream();
        assertThat(result.succeeded())
                .withFailMessage("openPartStream() fehlgeschlagen: %s",
                        result.failed() ? result.getFailureDetail() : "")
                .isTrue();

        var received = new CopyOnWriteArrayList<String>();
        // Wir wollen mind. 2 Aggregationen sehen (⇒ der Strom endet nicht nach der ersten).
        var latch = new CountDownLatch(2);

        var consumer = new Thread(() -> result.getContent().forEach(part -> {
            try {
                var json = new String(part.openStream().readAllBytes(), StandardCharsets.UTF_8);
                received.add(json);
                // Jede kontinuierlich gestreamte Aggregation live auf der Konsole ausgeben:
                System.out.printf("%n========== [Mms-PUSH] Aggregation #%d empfangen ==========%n%s%n",
                        received.size(), json);
                latch.countDown();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }), "mms-push-consumer");
        consumer.setDaemon(true);
        consumer.start();

        // AGGREGATION_SIZE=10 × updateInterval 1000ms ⇒ ~10s pro Aggregation; 2 Stück in <120s.
        var gotTwo = latch.await(120, TimeUnit.SECONDS);
        System.out.printf("%n=== Kontinuierlicher Mms-PUSH: insgesamt %d Aggregation(en) empfangen ===%n", received.size());

        // Terminate-Pfad: close() beendet den unendlichen Strom (Poison-Pill) → consumer-Thread läuft aus.
        dataSource.close();
        consumer.join(10_000);

        assertThat(gotTwo)
                .withFailMessage("weniger als 2 Aggregationen in 120s erhalten (%d)", received.size())
                .isTrue();
        assertThat(received).hasSizeGreaterThanOrEqualTo(2);
        assertThat(received.get(0)).contains("LCCS1").contains("messageCount");
        assertThat(consumer.isAlive())
                .withFailMessage("Consumer-Thread läuft nach close() weiter — Strom wurde nicht beendet")
                .isFalse();
    }
}
