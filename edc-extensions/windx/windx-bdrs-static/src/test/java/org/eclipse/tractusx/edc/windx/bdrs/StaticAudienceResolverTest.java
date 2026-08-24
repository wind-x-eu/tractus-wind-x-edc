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

import org.eclipse.edc.spi.types.domain.message.ProtocolRemoteMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StaticAudienceResolverTest {

    private static final String BPN = "BPNL000000000PROV";
    private static final String DID = "did:web:windx-identityhub.tx.test:provider";

    private final StaticBdrsClient client = StaticBdrsClient.parse(BPN + "=" + DID);
    private final StaticAudienceResolver resolver = new StaticAudienceResolver(client);

    @Test
    void bpn_isResolvedToDid() {
        var result = resolver.resolve(messageWith(BPN));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.getContent()).isEqualTo(DID);
    }

    @Test
    void did_isPassedThroughUnresolved() {
        var emptyClient = mock(org.eclipse.tractusx.edc.spi.identity.mapper.BdrsClient.class);
        var passthrough = new StaticAudienceResolver(emptyClient);

        var result = passthrough.resolve(messageWith(DID));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.getContent()).isEqualTo(DID);
        verifyNoInteractions(emptyClient); // DIDs duerfen den Client gar nicht erst befragen
    }

    @Test
    void unknownBpn_failsWithClearMessage() {
        var result = resolver.resolve(messageWith("BPNL999999999XXXX"));

        assertThat(result.failed()).isTrue();
        assertThat(result.getFailureDetail()).contains("no DID configured for BPN");
    }

    @Test
    void missingCounterPartyId_fails() {
        assertThat(resolver.resolve(messageWith(null)).failed()).isTrue();
        assertThat(resolver.resolve(messageWith("  ")).failed()).isTrue();
    }

    private ProtocolRemoteMessage messageWith(String counterPartyId) {
        var message = mock(ProtocolRemoteMessage.class);
        when(message.getCounterPartyId()).thenReturn(counterPartyId);
        return message;
    }
}
