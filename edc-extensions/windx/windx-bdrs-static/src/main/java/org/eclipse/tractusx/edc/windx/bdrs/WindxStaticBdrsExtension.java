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

package org.eclipse.tractusx.edc.windx.bdrs;

import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.iam.AudienceResolver;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.tractusx.edc.spi.identity.mapper.BdrsClient;

import static org.eclipse.tractusx.edc.windx.bdrs.WindxStaticBdrsExtension.NAME;

/**
 * Ersetzt den BDRS-Server durch eine statische BPN↔DID-Abbildung aus der Konfiguration.
 *
 * <p>Stellt <b>beide</b> Services bereit, die sonst das Modul {@code bdrs-client} liefert:
 * {@link BdrsClient} (via {@code BdrsClientExtension}) und {@link AudienceResolver} (via
 * {@code BdrsClientMapperExtension}). Damit dieses Modul greift, muss {@code bdrs-client} in der Runtime
 * ausgeschlossen werden — sonst kollidieren die {@code @Provider}.</p>
 *
 * <p>Das Pflicht-Setting {@code tx.edc.iam.dcp.bdrs.server.url} des Original-Moduls entfaellt damit
 * ersatzlos.</p>
 */
@Extension(value = NAME)
public class WindxStaticBdrsExtension implements ServiceExtension {

    public static final String NAME = "Wind-X Static BDRS Extension";

    static final String MAPPINGS = "tx.edc.windx.bdrs.mappings";

    @Setting(
            key = MAPPINGS,
            description = "Static BPN-to-DID mappings, replacing the BDRS server. Format: 'BPN=DID;BPN=DID'.",
            required = false)
    private String mappings;

    @Inject
    private Monitor monitor;

    private StaticBdrsClient client;

    @Override
    public String name() {
        return NAME;
    }

    @Provider
    public BdrsClient bdrsClient() {
        return client();
    }

    @Provider
    public AudienceResolver audienceResolver() {
        return new StaticAudienceResolver(client());
    }

    private StaticBdrsClient client() {
        if (client == null) {
            client = StaticBdrsClient.parse(mappings);
            if (client.size() == 0) {
                monitor.warning(("%s: no BPN/DID mappings configured ('%s' is empty). Every catalog request or "
                        + "negotiation against a counter-party addressed by BPN will fail.").formatted(NAME, MAPPINGS));
            } else {
                monitor.info("%s: %d BPN/DID mapping(s) configured".formatted(NAME, client.size()));
            }
        }
        return client;
    }
}
