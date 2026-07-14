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

package org.eclipse.tractusx.edc.windx.vault;

import org.eclipse.edc.participantcontext.single.spi.SingleParticipantContextSupplier;
import org.eclipse.edc.participantcontext.spi.types.ParticipantContext;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import java.util.stream.Stream;

/**
 * Befuellt den In-Memory-Vault beim Start mit Secrets aus der Konfiguration.
 *
 * <p>Der von {@code single-participant-vault} bereitgestellte {@code InMemorySingleParticipantVault} startet
 * <b>leer</b>. Ohne Seeding sind STS-Client-Secret und Signer-/Verifier-Keys nicht aufloesbar und jede
 * Contract-Negotiation scheitert. Die Klasse ist die windx-Kopie der gleichnamigen Extension aus
 * {@code edc-runtime-memory} — dort liegt sie im Runtime-Modul selbst und ist deshalb nicht
 * wiederverwendbar.</p>
 *
 * <p><b>Format:</b> Paare werden mit {@code ;} getrennt, Key und Value mit dem <i>ersten</i> {@code :} —
 * also {@code alias1:secret1;alias2:secret2}. Unvollstaendige Paare werden still uebersprungen (Verhalten
 * wie im Original).</p>
 */
@Extension(value = "Wind-X Vault seed extension: adds secrets to the vault", categories = { "vault", "security" })
public class VaultSeedExtension implements ServiceExtension {

    public static final String NAME = "Wind-X Vault Seed Extension";

    static final String VAULT_MEMORY_SECRETS_PROPERTY = "tx.edc.vault.secrets";

    @Setting(
            key = VAULT_MEMORY_SECRETS_PROPERTY,
            description = "Secrets with which the vault gets initially populated. Specify as semicolon-separated list of key:secret pairs.",
            required = false)
    private String seedSecrets;

    @Inject
    private Vault vault;
    @Inject
    private SingleParticipantContextSupplier singleParticipantContextSupplier;
    @Inject
    private Monitor monitor;

    @Override
    public String name() {
        return NAME;
    }

    /**
     * Seeding bewusst in {@code initialize()} — anders als das Original in {@code edc-runtime-memory},
     * das dafuer einen zweiten {@code @Provider} fuer {@link Vault} registriert. Zum Zeitpunkt von
     * {@code initialize()} ist der Vault im Service-Registry final aufgeloest; der Provider-Weg haengt
     * dagegen von der Registrierungsreihenfolge ab: registriert eine spaetere Extension eine neue
     * Vault-Instanz, waeren die geseedeten Secrets still verloren — und der Fehler tauchte erst als
     * kryptischer Token-/Signatur-Fehler bei der ersten Negotiation auf.
     */
    @Override
    public void initialize(ServiceExtensionContext context) {
        if (seedSecrets == null) {
            return;
        }

        singleParticipantContextSupplier.get().map(ParticipantContext::getParticipantContextId)
                .onSuccess(participantContextId -> {
                    // bewusst kein peek()/count(): count() darf die Pipeline ueberspringen, wenn die Groesse
                    // direkt bestimmbar ist -- die Seiteneffekte wuerden dann ausfallen.
                    var pairs = Stream.of(seedSecrets.split(";"))
                            .filter(pair -> pair.contains(":"))
                            .map(kvp -> kvp.split(":", 2))
                            .filter(kvp -> kvp.length >= 2)
                            .toList();
                    pairs.forEach(pair -> vault.storeSecret(participantContextId, pair[0], pair[1]));
                    monitor.info("%s: seeded %d secret(s) into the vault of participant context '%s'"
                            .formatted(NAME, pairs.size(), participantContextId));
                })
                .orElseThrow(f -> new EdcException("Cannot get the participant context: " + f.getFailureDetail()));
    }
}
