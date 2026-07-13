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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Echter Hardware-Konnektivitaetstest gegen den IEC-61850/MMS-Testserver der BIBA
 * ({@code agilevt-dev.ikap.biba.uni-bremen.de:102}, Logical Device {@code WPPS}).
 *
 * <p>Validiert den portierten MMS-Stack (COTP/TPKT/ASN.1, {@link MMSDataSource} → Manager → Crawler)
 * end-to-end gegen einen realen Server: verbindet, liest das Server-Modell, pollt den Sensor
 * {@code LCCS1$ST$Mod$stVal} und aggregiert 10 Nachrichten zu einer JSON-Aggregation.</p>
 *
 * <p>Netzwerkabhaengig, daher {@code @Tag("MmsHardwareIT")} — laeuft NICHT im normalen {@code test},
 * sondern nur explizit via {@code -DincludeTags="MmsHardwareIT"}.</p>
 */
@Tag("MmsHardwareIT")
class MmsHardwareConnectivityIT {

    private static final String HOST = "agilevt-dev.ikap.biba.uni-bremen.de";

    @Test
    void pullsOneAggregationFromRealServer() throws IOException {
        var source = DataAddress.Builder.newInstance()
                .type("Mms")
                .property("mms:serverHost", HOST)
                .property("mms:serverPort", "102")
                .property("mms:logicalDevice", "WPPS")
                .property("mms:sensors", "LCCS1$ST$Mod$stVal")
                .property("mms:updateInterval", "1000")
                .property("mms:reconnectInterval", "5000")
                .build();

        var request = DataFlowStartMessage.Builder.newInstance()
                .processId(UUID.randomUUID().toString())
                .sourceDataAddress(source)
                .flowType(FlowType.PULL)
                .build();

        var dataSource = new MMSDataSource(request);
        var result = dataSource.openPartStream();

        assertThat(result.succeeded())
                .withFailMessage("openPartStream() fehlgeschlagen: %s",
                        result.failed() ? result.getFailureDetail() : "")
                .isTrue();

        List<DataSourcePart> parts = result.getContent().map(DataSourcePart::of).toList();
        assertThat(parts).hasSize(1);

        var json = new String(parts.get(0).bytes(), StandardCharsets.UTF_8);
        System.out.println("=== MMS-Aggregation vom echten Server " + HOST + " ===");
        System.out.println(json);

        // Der gepollte Sensor muss in der Aggregation auftauchen.
        assertThat(json).contains("LCCS1");
        assertThat(json).contains("messageCount");
    }

    /** Hilfsrecord: liest den Part-Stream einmalig aus (Part#openStream wirft IOException). */
    private record DataSourcePart(byte[] bytes) {
        static DataSourcePart of(org.eclipse.edc.connector.dataplane.spi.pipeline.DataSource.Part part) {
            try {
                return new DataSourcePart(part.openStream().readAllBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
