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

import org.eclipse.edc.spi.EdcException;
import org.eclipse.tractusx.edc.spi.identity.mapper.BdrsClient;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.eclipse.tractusx.edc.spi.identity.mapper.BdrsConstants.DID_PREFIX;

/**
 * {@link BdrsClient}, der BPN↔DID rein aus der Konfiguration aufloest — ohne BDRS-Server.
 *
 * <p>Der reale {@code BdrsClientImpl} holt sein Directory per HTTP von einem BDRS-Server, der seinerseits
 * Verifiable Presentations der Anfragenden validiert. Fuer einen Datenraum mit einer festen, kleinen Menge
 * bekannter Teilnehmer (Provider + Consumer) ist eine statische Abbildung funktional aequivalent und spart
 * die komplette BDRS-Infrastruktur.</p>
 *
 * <p>Fachlicher Zwilling des Test-Fixtures {@code MockBdrsClient} — mit dem Unterschied, dass die Mappings
 * aus der Config kommen statt aus Lambdas.</p>
 *
 * <p><b>Config-Format:</b> {@code BPN=DID;BPN=DID}. Getrennt wird an {@code ;} (Paare) und am <i>ersten</i>
 * {@code =} (Key/Value) — bewusst {@code =} statt {@code :}, weil DIDs selbst Doppelpunkte enthalten
 * ({@code did:web:host:name}).</p>
 */
public class StaticBdrsClient implements BdrsClient {

    private final Map<String, String> bpnToDid;
    private final Map<String, String> didToBpn;

    public StaticBdrsClient(Map<String, String> bpnToDid) {
        this.bpnToDid = Map.copyOf(bpnToDid);
        var reversed = new LinkedHashMap<String, String>();
        bpnToDid.forEach((bpn, did) -> reversed.put(did, bpn));
        this.didToBpn = Map.copyOf(reversed);
    }

    /**
     * Parst die Mapping-Konfiguration. Fehlerhafte Eintraege fuehren zu einem harten Fehler beim Boot —
     * eine stille Fehlkonfiguration wuerde sich sonst erst beim ersten DSP-Call als Auflösungsfehler zeigen.
     *
     * @param raw Konfigurationswert, z.B. {@code BPNL1=did:web:host:provider;BPNL2=did:web:host:consumer};
     *            {@code null} oder leer ergibt einen leeren Client.
     */
    public static StaticBdrsClient parse(String raw) {
        var mappings = new LinkedHashMap<String, String>();
        if (raw == null || raw.isBlank()) {
            return new StaticBdrsClient(mappings);
        }

        for (var entry : raw.split(";")) {
            var trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            var parts = trimmed.split("=", 2);
            if (parts.length < 2) {
                throw new EdcException("Malformed BPN/DID mapping '%s': expected format 'BPN=DID'".formatted(trimmed));
            }

            var bpn = parts[0].trim();
            var did = parts[1].trim();
            if (bpn.isEmpty() || did.isEmpty()) {
                throw new EdcException("Malformed BPN/DID mapping '%s': BPN and DID must both be non-empty".formatted(trimmed));
            }
            if (!did.startsWith(DID_PREFIX)) {
                throw new EdcException("Malformed BPN/DID mapping '%s': '%s' is not a DID (expected prefix '%s')"
                        .formatted(trimmed, did, DID_PREFIX));
            }
            if (mappings.containsKey(bpn)) {
                throw new EdcException("Duplicate BPN '%s' in BPN/DID mappings".formatted(bpn));
            }
            mappings.put(bpn, did);
        }
        return new StaticBdrsClient(mappings);
    }

    @Override
    public String resolveDid(String bpn) {
        return bpnToDid.get(bpn);
    }

    @Override
    public String resolveBpn(String did) {
        return didToBpn.get(did);
    }

    /**
     * Anzahl der konfigurierten Mappings — fuer das Boot-Logging.
     */
    public int size() {
        return bpnToDid.size();
    }
}
