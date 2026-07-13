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

import org.eclipse.edc.connector.dataplane.spi.edr.EndpointDataReferenceServiceRegistry;
import org.eclipse.edc.connector.dataplane.spi.pipeline.PipelineService;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.system.Hostname;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import static org.eclipse.tractusx.edc.windx.mms.WindxMmsLightDataPlaneExtension.NAME;

/**
 * Wind-X MMS-Light data plane extension (portierter BIBA-{@code mms-light}-TCP-Tunnel).
 *
 * <p>{@code mms-light} liefert dem Consumer einen rohen {@code tcp://host:port}-Endpunkt, über den er
 * <b>nativ MMS/IEC-61850</b> spricht (im Gegensatz zu {@code mms} = Aggregation→JSON über HTTP). BIBA baute
 * das als Control-Plane-{@code DataFlowController}; in EDC 0.17 gibt es dafür keinen {@code DataFlowManager}
 * mehr (s. {@code docs/phase-mms-light-recherche.md}). Der korrekte Ort ist die Data Plane.</p>
 *
 * <p>Zwei Registrierungen (durchgängiger Typ-String {@code MmsLight}, weil die EDR-Registry {@code create()}
 * nach Source-Typ und {@code revoke()} nach {@code transferType.destinationType()} dispatcht — beide müssen
 * gleich sein):</p>
 * <ol>
 *   <li>{@link EndpointDataReferenceServiceRegistry#register} mit Typ {@code MmsLight} und unserer
 *       {@link MmsLightEndpointDataReferenceService} → bewirbt {@code MmsLight-PULL} und liefert den
 *       Proxy-Lifecycle (create=Socket öffnen+Proxy starten, revoke=Proxy stoppen).</li>
 *   <li>{@link PipelineService#registerFactory} mit einer minimalen {@link MmsLightDataSourceFactory}
 *       (Source-Typ {@code MmsLight}) → nur zur Typbewerbung als {@code allowedSourceType}.</li>
 * </ol>
 */
@Extension(value = NAME)
public class WindxMmsLightDataPlaneExtension implements ServiceExtension {

    public static final String NAME = "Wind-X MMS-Light Data Plane Extension";

    /** Durchgängiger Typ: Asset-{@code dataAddress.type} == Registry-Key == Transfer-destinationType → {@code MmsLight-PULL}. */
    public static final String MMS_LIGHT_TYPE = "MmsLight";

    @Inject
    private EndpointDataReferenceServiceRegistry edrServiceRegistry;
    @Inject
    private PipelineService pipelineService;
    @Inject
    private Hostname hostname;

    private MmsLightEndpointDataReferenceService edrService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor();

        // 1) Eigener EDR-Service für Destination-Typ 'MmsLight' -> bewirbt 'MmsLight-PULL' und kapselt den
        //    kompletten TCP-Proxy-Lifecycle (create/revoke an den 0.17-DataPlaneManager-Punkten).
        edrService = new MmsLightEndpointDataReferenceService(hostname.get(), monitor);
        edrServiceRegistry.register(MMS_LIGHT_TYPE, edrService);

        // 2) Source-Typ 'MmsLight' bewerben (allowedSourceType), sonst verwirft der DataPlaneSignalingFlowController
        //    den Transfer-Typ für ein MmsLight-Asset. Die Factory wird bei PULL nie geöffnet.
        pipelineService.registerFactory(new MmsLightDataSourceFactory());

        monitor.info("%s initialized: type '%s', transfer type '%s-PULL' (roher MMS-TCP-Tunnel, Endpoint tcp://%s:<dyn>)"
                .formatted(NAME, MMS_LIGHT_TYPE, MMS_LIGHT_TYPE, hostname.get()));
    }

    @Override
    public void shutdown() {
        if (edrService != null) {
            edrService.stopAll();
        }
    }
}
