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

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifiziert den portierten {@code mms.client}-Aggregations-Pfad (Queue → Crawler → gson-JSON) ohne
 * Socket/MMS-Server. Deckt den fragilsten Teil des Ports ab: die gson-{@code LocalDateTime}-Adapter und
 * die Aggregations-Logik von {@link MMSAggregationCrawler} (die auch {@code MMSDataSource} beim Pull antreibt).
 */
class MMSAggregationCrawlerTest {

    private static final String DOMAIN = "WPPS";
    private static final String SENSOR = "LCCS1$ST$Mod$stVal";
    private static final int AGGREGATION_SIZE = 10; // MMSAggregationCrawler.AGGREGATION_SIZE

    @Test
    void aggregatesTenMessagesIntoOneJsonDocument() throws InterruptedException {
        var queue = new MMSMessageQueue(1000);
        var latch = new CountDownLatch(1);
        var received = new AtomicReference<String>();

        var crawler = new MMSAggregationCrawler(queue, json -> {
            received.set(json);
            latch.countDown();
        });
        crawler.start();

        try {
            for (var i = 0; i < AGGREGATION_SIZE; i++) {
                var message = new MMSMessage();
                message.setReportId(DOMAIN);
                message.setDataSet(DOMAIN);
                message.addDataPoint(SENSOR, i);
                assertThat(queue.enqueue(message)).isTrue();
            }

            assertThat(latch.await(15, TimeUnit.SECONDS))
                    .withFailMessage("Crawler emitted no aggregation within 15s")
                    .isTrue();
        } finally {
            crawler.stop();
        }

        var json = received.get();
        assertThat(json).isNotNull();
        // Aggregations-Grundgeruest
        assertThat(json).contains("aggregationId");
        assertThat(json).contains("\"messageCount\": " + AGGREGATION_SIZE);
        // Sensor-Label landet als Data-Array (Slash→Dash-Ersetzung lassen wir hier unberuehrt, kein Slash)
        assertThat(json).contains(SENSOR);
        // Statistik-Block vorhanden
        assertThat(json).contains("statistics");
        assertThat(json).contains("uniqueReportIds");
    }
}
