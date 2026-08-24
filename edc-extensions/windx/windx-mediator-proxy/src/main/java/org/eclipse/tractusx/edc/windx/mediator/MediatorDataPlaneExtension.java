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

package org.eclipse.tractusx.edc.windx.mediator;

import org.eclipse.edc.connector.dataplane.spi.Endpoint;
import org.eclipse.edc.connector.dataplane.spi.edr.EndpointDataReferenceService;
import org.eclipse.edc.connector.dataplane.spi.edr.EndpointDataReferenceServiceRegistry;
import org.eclipse.edc.connector.dataplane.spi.iam.DataPlaneAuthorizationService;
import org.eclipse.edc.connector.dataplane.spi.iam.PublicEndpointGeneratorService;
import org.eclipse.edc.connector.dataplane.spi.pipeline.PipelineService;
import org.eclipse.edc.runtime.metamodel.annotation.Configuration;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.runtime.metamodel.annotation.Settings;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.system.Hostname;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.web.spi.WebService;
import org.eclipse.edc.web.spi.configuration.PortMapping;
import org.eclipse.edc.web.spi.configuration.PortMappingRegistry;

import static org.eclipse.tractusx.edc.windx.mediator.MediatorDataPlaneExtension.NAME;

/**
 * Wind-X Semantic-Mediator data plane extension (portierte BIBA-{@code MediatorDataPlaneExtension}).
 *
 * <p>Registriert einen eigenen Web-Kontext {@code med} (eigener Port, umgeht die Kollisionen K2/K3
 * mit dem Standard-{@code public}-Kontext) und eine Endpoint-Generator-Funktion unter dem
 * <b>eigenen</b> Transfer-Typ {@link #MEDIATOR_HTTP_DATA} (umgeht die stille {@code HttpData}-Kollision
 * K1). Der {@link MediatorController} transformiert einen autorisierten GET in einen POST an den
 * Semantic Mediator ({@code .../forwardRealDataSource}).</p>
 *
 * <p>Abweichungen ggü. BIBA: die Mediator-Basis-URL kommt aus <b>einem</b> zentralen {@link Setting}
 * ({@link #MEDIATOR_BASE_URL}) statt aus einer per-Asset-Property mit hartkodiertem Fallback; nur der
 * GET-Pfad ist migriert (PUT/{@code createVirtualDataSource} folgt später).</p>
 */
@Extension(value = NAME)
public class MediatorDataPlaneExtension implements ServiceExtension {

    public static final String NAME = "Wind-X Mediator Data Plane Extension";

    /** Eigener Transfer-/Destination-Typ statt {@code HttpData} (umgeht Kollision K1). */
    public static final String MEDIATOR_HTTP_DATA = "MediatorHttpData";
    public static final String MEDIATOR_BASE_URL = "tx.edc.windx.mediator.base.url";

    private static final String MED = "med";
    private static final int DEFAULT_MED_PORT = 8087;
    private static final String DEFAULT_MED_PATH = "/api/med";

    @Setting(description = "Base url of the Semantic Mediator without trailing slash, e.g. http://semanticmediator:3051",
            key = MEDIATOR_BASE_URL)
    private String mediatorBaseUrl;

    @Setting(description = "Public endpoint of the med API context that is written into the EDR (consumer pull address). " +
            "Defaults to http://<hostname>:<med-port><med-path> if not set.",
            key = "edc.dataplane.windx.med.endpoint", required = false)
    private String medPublicEndpoint;

    @Configuration
    private MedApiConfiguration apiConfiguration;

    @Inject
    private PortMappingRegistry portMappingRegistry;
    @Inject
    private PublicEndpointGeneratorService generatorService;
    @Inject
    private WebService webService;
    @Inject
    private DataPlaneAuthorizationService authorizationService;
    @Inject
    private EndpointDataReferenceServiceRegistry edrServiceRegistry;
    @Inject
    private PipelineService pipelineService;
    @Inject
    private Hostname hostname;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor();

        // 1) eigener Web-Kontext auf eigenem Port -> umgeht Port-/Kontext-Kollision (K2/K3)
        var portMapping = new PortMapping(MED, apiConfiguration.port(), apiConfiguration.path());
        portMappingRegistry.register(portMapping);

        // 2) Pflicht-URL klar validieren (Entscheidung 3: Fehler statt stiller Default)
        if (mediatorBaseUrl == null || mediatorBaseUrl.isBlank()) {
            throw new EdcException("Setting '%s' must be configured with the Semantic Mediator base url".formatted(MEDIATOR_BASE_URL));
        }

        // 3) EDR-Public-Endpoint fuer den med-Kontext (Pull-Adresse fuer den Consumer)
        if (medPublicEndpoint == null || medPublicEndpoint.isBlank()) {
            medPublicEndpoint = "http://%s:%d%s".formatted(hostname.get(), portMapping.port(), portMapping.path());
            monitor.warning("The med API public endpoint was not configured, defaulting to '%s'.".formatted(medPublicEndpoint));
        }

        // 4) eigener Transfer-Typ -> umgeht die stille HttpData-Kollision (K1)
        var endpoint = Endpoint.url(medPublicEndpoint);
        generatorService.addGeneratorFunction(MEDIATOR_HTTP_DATA, dataAddress -> endpoint);

        // 4b) Typ bei der EDR-Service-Registry bewerben -> erst dadurch meldet die
        //     DataplaneSelfRegistration 'MediatorHttpData-PULL' als allowedTransferType und
        //     der Standard-DataPlaneSignalingFlowController kann den Transfer hierher routen.
        edrServiceRegistry.register(MEDIATOR_HTTP_DATA, (EndpointDataReferenceService) authorizationService);

        // 4c) Source-Typ bewerben: erst dadurch fuehrt die DataPlaneInstance 'MediatorHttpData' in
        //     allowedSourceTypes und der Provider meldet 'MediatorHttpData-PULL' als unterstuetzt.
        //     Die Factory wird im Consumer-Pull nie geoeffnet (der MediatorController holt die Daten).
        pipelineService.registerFactory(new MediatorHttpDataSourceFactory());

        // 5) Controller registrieren (nur GET)
        webService.registerResource(MED, new MediatorController(authorizationService, monitor, mediatorBaseUrl));

        monitor.info("%s initialized: context '%s' on %s, transfer type '%s', mediator base url '%s'"
                .formatted(NAME, MED, medPublicEndpoint, MEDIATOR_HTTP_DATA, mediatorBaseUrl));
    }

    @Settings
    record MedApiConfiguration(
            @Setting(key = "web.http." + MED + ".port", description = "Port for " + MED + " api context", defaultValue = DEFAULT_MED_PORT + "")
            int port,
            @Setting(key = "web.http." + MED + ".path", description = "Path for " + MED + " api context", defaultValue = DEFAULT_MED_PATH)
            String path
    ) {
    }
}
