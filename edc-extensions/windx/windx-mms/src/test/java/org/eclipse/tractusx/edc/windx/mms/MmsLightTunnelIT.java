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

import org.eclipse.edc.connector.dataplane.spi.DataFlow;
import org.eclipse.edc.spi.monitor.ConsoleMonitor;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.spi.types.domain.transfer.FlowType;
import org.eclipse.edc.spi.types.domain.transfer.TransferType;
import org.eclipse.tractusx.edc.windx.mms.client.MMSClient;
import org.eclipse.tractusx.edc.windx.mms.client.MMSMessage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closed-Loop-E2E-Test für den {@code mms-light}-TCP-Tunnel gegen den echten IEC-61850/MMS-Testserver
 * ({@code agilevt-dev.ikap.biba.uni-bremen.de:102}, Logical Device {@code WPPS}).
 *
 * <p>Prüft den kompletten Tunnel über den Produktionspfad {@link MmsLightEndpointDataReferenceService}:</p>
 * <ol>
 *   <li>{@code createEndpointDataReference} startet den {@code MMSTcpProxy}, der sich zum echten Server verbindet
 *       und selbst als MMS-Server auf einem dynamischen TCP-Port lauscht → liefert eine {@code MmsTcp}-EDR.</li>
 *   <li>Als <b>Consumer</b> verbindet sich ein zweiter {@link MMSClient} nativ (COTP + MMS-Associate) mit dem
 *       Proxy-Port und empfängt die (aus echten Server-Daten umgepackten) InformationReports.</li>
 *   <li>{@code revokeEndpointDataReference} stoppt den Proxy.</li>
 * </ol>
 *
 * <p>Netzwerkabhängig → {@code @Tag("MmsHardwareIT")} (nur via {@code -DincludeTags="MmsHardwareIT"}).</p>
 */
@Tag("MmsHardwareIT")
class MmsLightTunnelIT {

    private static final String HOST = "agilevt-dev.ikap.biba.uni-bremen.de";

    @Test
    void tunnelsNativeMmsFromRealServerToConsumer() throws Exception {
        var monitor = new ConsoleMonitor();
        var edrService = new MmsLightEndpointDataReferenceService("localhost", monitor);

        var source = DataAddress.Builder.newInstance()
                .type(WindxMmsLightDataPlaneExtension.MMS_LIGHT_TYPE)
                .property("mms:serverHost", HOST)
                .property("mms:serverPort", "102")
                .property("mms:logicalDevice", "WPPS")
                .property("mms:sensors", "LCCS1$ST$Mod$stVal")
                .property("mms:pollingInterval", "1000")
                .build();

        var dataFlow = DataFlow.Builder.newInstance()
                .source(source)
                .transferType(new TransferType(WindxMmsLightDataPlaneExtension.MMS_LIGHT_TYPE, FlowType.PULL))
                .build();

        // 1) EDR erzeugen → Proxy startet, verbindet zum echten Server, öffnet dynamischen TCP-Port.
        var edrResult = edrService.createEndpointDataReference(dataFlow);
        assertThat(edrResult.succeeded())
                .withFailMessage("createEndpointDataReference fehlgeschlagen: %s",
                        edrResult.failed() ? edrResult.getFailureDetail() : "")
                .isTrue();

        var edr = edrResult.getContent();
        assertThat(edr.getType()).isEqualTo("MmsTcp");
        var proxyPort = Integer.parseInt(edr.getStringProperty("port"));
        System.out.println("=== MmsLight-EDR: " + edr.getStringProperty("url") + " ===");
        assertThat(proxyPort).isGreaterThan(0);

        // 2) Consumer verbindet sich nativ MMS mit dem Proxy und wartet auf einen (getunnelten) Report.
        var received = new AtomicReference<MMSMessage>();
        var latch = new CountDownLatch(1);
        var consumer = new MMSClient("localhost", proxyPort);
        consumer.addMessageListener(msg -> {
            // Jede über den Tunnel nativ empfangene MMS-Nachricht live ausgeben:
            System.out.println(">>> [mms-light Tunnel] nativ MMS empfangen: " + msg);
            if (received.compareAndSet(null, msg)) {
                latch.countDown();
            }
        });

        try {
            consumer.connect();
            var got = latch.await(30, TimeUnit.SECONDS);
            assertThat(got)
                    .withFailMessage("Consumer hat innerhalb von 30s keinen getunnelten MMS-Report erhalten")
                    .isTrue();

            var msg = received.get();
            System.out.println("=== Getunnelter Report vom Consumer empfangen ===");
            System.out.println(msg);
            assertThat(msg).isNotNull();
            assertThat(msg.getData()).isNotEmpty();
        } finally {
            consumer.disconnect();
            // 3) Teardown über den echten revoke-Pfad.
            var revoke = edrService.revokeEndpointDataReference(dataFlow.getId(), "test finished");
            assertThat(revoke.succeeded()).isTrue();
        }
    }
}
