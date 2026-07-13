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

import org.eclipse.edc.connector.dataplane.spi.Endpoint;
import org.eclipse.edc.connector.dataplane.spi.edr.EndpointDataReferenceService;
import org.eclipse.edc.connector.dataplane.spi.edr.EndpointDataReferenceServiceRegistry;
import org.eclipse.edc.connector.dataplane.spi.iam.DataPlaneAuthorizationService;
import org.eclipse.edc.connector.dataplane.spi.iam.PublicEndpointGeneratorService;
import org.eclipse.edc.connector.dataplane.spi.pipeline.PipelineService;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.system.Hostname;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import static org.eclipse.tractusx.edc.windx.mms.WindxMmsDataPlaneExtension.NAME;

/**
 * Wind-X MMS (IEC-61850) data plane extension (portierter BIBA-{@code mms}-Pull-Pfad).
 *
 * <p>BIBA bewegte MMS-Daten in einem Control-Plane-{@code DataFlowController} — in EDC 0.17 gibt es
 * dafuer keinen {@code DataFlowManager}/Registry mehr (s. {@code docs/phase-3-recherche.md}). Der
 * korrekte 0.17-Ort ist die Data Plane: BIBAs bereits DP-geformte, aber nie registrierte
 * {@link MMSDataSourceFactory}/{@code MMSDataSource} werden hier als echte Pull-Quelle registriert und
 * treiben den portierten {@code mms.client}-Stack (roher IEC-61850/MMS-Client), der eine JSON-Aggregation
 * liefert.</p>
 *
 * <p>Drei Registrierungen (analog {@code windx-mediator-proxy}), damit der Provider {@code Mms-PULL}
 * anbietet und der Consumer die Daten ueber die <b>Standard</b>-Data-Plane-Public-API zieht:</p>
 * <ol>
 *   <li>{@link PipelineService#registerFactory} mit Source-Typ {@code mms} (Asset-{@code dataAddress.type}).</li>
 *   <li>{@link EndpointDataReferenceServiceRegistry#register} fuer Destination-Typ {@code Mms}
 *       → bewirbt {@code Mms-PULL} als {@code allowedTransferType}.</li>
 *   <li>{@link PublicEndpointGeneratorService#addGeneratorFunction} fuer {@code Mms} → EDR-Endpoint zeigt
 *       auf die Standard-Public-API ({@code edc.dataplane.api.public.baseurl}); deren Controller ist
 *       source-typ-agnostisch und oeffnet die registrierte {@code mms}-Source.</li>
 * </ol>
 */
@Extension(value = NAME)
public class WindxMmsDataPlaneExtension implements ServiceExtension {

    public static final String NAME = "Wind-X MMS Data Plane Extension";

    /**
     * Durchgaengiger Typ-String {@code Mms}: Asset-{@code dataAddress.type} == EDR-Registry-Key ==
     * Transfer-{@code destinationType} → {@code Mms-PULL}.
     *
     * <p><b>Bug-Fix (2026-07-07):</b> zuvor war der Source-Typ {@code mms} (klein), der EDR-Key aber {@code Mms}.
     * {@code EndpointDataReferenceServiceRegistryImpl.create()} dispatcht nach <b>Source-Typ</b>, {@code revoke()}
     * nach {@code destinationType} — bei Mismatch scheiterte ein echter {@code Mms-PULL}-Transfer an der
     * EDR-Erzeugung („No EDR service with type mms found"). Der reine Advertising-Test deckte das nie auf. Fix:
     * ein einheitlicher Typ {@code Mms} (wie bei {@code MmsLight}). Property-Keys bleiben {@code mms:*}.</p>
     */
    public static final String MMS_SOURCE_TYPE = "Mms";
    /** Destination-/EDR-Typ → Transfer-Typ {@code Mms-PULL} (BIBA-treu); identisch zu {@link #MMS_SOURCE_TYPE}. */
    public static final String MMS_TRANSFER_DESTINATION_TYPE = "Mms";

    private static final String DEFAULT_PUBLIC_PORT = "8185";
    private static final String DEFAULT_PUBLIC_PATH = "/api/public";

    @Setting(description = "Base url of the standard data plane public API (without trailing slash) that serves the "
            + "consumer pull. Defaults to the value of the public api extension, or http://<hostname>:<public-port><public-path>.",
            key = "edc.dataplane.api.public.baseurl", required = false)
    private String publicBaseUrl;

    @Setting(description = "Port of the standard public api context (used only to derive the pull endpoint if "
            + "edc.dataplane.api.public.baseurl is not set).", key = "web.http.public.port", defaultValue = DEFAULT_PUBLIC_PORT)
    private int publicPort;

    @Setting(description = "Path of the standard public api context (used only to derive the pull endpoint if "
            + "edc.dataplane.api.public.baseurl is not set).", key = "web.http.public.path", defaultValue = DEFAULT_PUBLIC_PATH)
    private String publicPath;

    @Inject
    private PipelineService pipelineService;
    @Inject
    private PublicEndpointGeneratorService generatorService;
    @Inject
    private DataPlaneAuthorizationService authorizationService;
    @Inject
    private EndpointDataReferenceServiceRegistry edrServiceRegistry;
    @Inject
    private Hostname hostname;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor();

        // 1) Source-Typ 'Mms' bewerben -> ohne die Factory in allowedSourceTypes verwirft der
        //    Standard-DataPlaneSignalingFlowController 'Mms-PULL' fuer ein mms-Asset.
        pipelineService.registerFactory(new MMSDataSourceFactory());

        // 2) Destination-Typ 'Mms' bei der EDR-Service-Registry -> erst dadurch meldet die
        //    DataplaneSelfRegistration 'Mms-PULL' als allowedTransferType.
        edrServiceRegistry.register(MMS_TRANSFER_DESTINATION_TYPE, (EndpointDataReferenceService) authorizationService);

        // 3) EDR-Endpoint = Standard-Public-API (deren Controller ist source-typ-agnostisch und oeffnet
        //    beim Pull die registrierte mms-Source).
        var pullUrl = resolvePublicBaseUrl(monitor);
        var endpoint = Endpoint.url(pullUrl);
        generatorService.addGeneratorFunction(MMS_TRANSFER_DESTINATION_TYPE, dataAddress -> endpoint);

        monitor.info("%s initialized: source type '%s', transfer type '%s-PULL', pull endpoint '%s'"
                .formatted(NAME, MMS_SOURCE_TYPE, MMS_TRANSFER_DESTINATION_TYPE, pullUrl));
    }

    private String resolvePublicBaseUrl(org.eclipse.edc.spi.monitor.Monitor monitor) {
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            return publicBaseUrl;
        }
        var derived = "http://%s:%d%s".formatted(hostname.get(), publicPort, publicPath);
        monitor.warning("'edc.dataplane.api.public.baseurl' not set; deriving MMS pull endpoint as '%s'.".formatted(derived));
        return derived;
    }
}
