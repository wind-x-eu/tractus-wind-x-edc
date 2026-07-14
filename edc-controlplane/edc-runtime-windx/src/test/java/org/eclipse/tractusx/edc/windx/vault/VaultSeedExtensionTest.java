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

import org.eclipse.edc.boot.system.injection.ObjectFactory;
import org.eclipse.edc.junit.extensions.DependencyInjectionExtension;
import org.eclipse.edc.participantcontext.single.spi.SingleParticipantContextSupplier;
import org.eclipse.edc.participantcontext.spi.types.ParticipantContext;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.ServiceResult;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.spi.system.configuration.ConfigFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(DependencyInjectionExtension.class)
class VaultSeedExtensionTest {

    private static final String PARTICIPANT_CONTEXT_ID = "participantContextId";

    private final Monitor monitor = mock();
    private final Vault vault = mock();
    private final SingleParticipantContextSupplier participantContextSupplier = () -> ServiceResult.success(
            ParticipantContext.Builder.newInstance().participantContextId(PARTICIPANT_CONTEXT_ID).identity("identity").build()
    );

    @BeforeEach
    void setup(ServiceExtensionContext context) {
        context.registerService(Monitor.class, monitor);
        context.registerService(Vault.class, vault);
        context.registerService(SingleParticipantContextSupplier.class, participantContextSupplier);
    }

    @Test
    void name(VaultSeedExtension extension) {
        assertThat(extension.name()).isEqualTo("Wind-X Vault Seed Extension");
    }

    @Test
    void initialize_seedsAllPairs(ServiceExtensionContext context, ObjectFactory factory) {
        var extension = extensionWith("alias1:secret1;alias2:secret2", context, factory);

        extension.initialize(context);

        verify(vault).storeSecret(PARTICIPANT_CONTEXT_ID, "alias1", "secret1");
        verify(vault).storeSecret(PARTICIPANT_CONTEXT_ID, "alias2", "secret2");
        verifyNoMoreInteractions(vault);
    }

    @Test
    void initialize_keepsColonsInsideTheValue(ServiceExtensionContext context, ObjectFactory factory) {
        // relevant fuer Werte mit ':' (z.B. DIDs) -- es wird nur am ERSTEN Doppelpunkt getrennt
        var extension = extensionWith("client-secret:did:web:windx-identityhub.tx.test:provider", context, factory);

        extension.initialize(context);

        verify(vault).storeSecret(PARTICIPANT_CONTEXT_ID, "client-secret", "did:web:windx-identityhub.tx.test:provider");
    }

    @Test
    void initialize_skipsMalformedPairs(ServiceExtensionContext context, ObjectFactory factory) {
        var extension = extensionWith("no-colon-here;;alias1:secret1", context, factory);

        extension.initialize(context);

        verify(vault).storeSecret(PARTICIPANT_CONTEXT_ID, "alias1", "secret1");
        verifyNoMoreInteractions(vault);
    }

    @Test
    void initialize_noConfig_storesNothing(ServiceExtensionContext context, ObjectFactory factory) {
        var extension = factory.constructInstance(VaultSeedExtension.class);

        extension.initialize(context);

        verify(vault, never()).storeSecret(anyString(), anyString(), anyString());
    }

    private VaultSeedExtension extensionWith(String secrets, ServiceExtensionContext context, ObjectFactory factory) {
        when(context.getConfig()).thenReturn(ConfigFactory.fromMap(
                Map.of(VaultSeedExtension.VAULT_MEMORY_SECRETS_PROPERTY, secrets)));
        return factory.constructInstance(VaultSeedExtension.class);
    }
}
