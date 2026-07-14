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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaticBdrsClientTest {

    private static final String PROVIDER_BPN = "BPNL000000000PROV";
    private static final String PROVIDER_DID = "did:web:windx-identityhub.tx.test:provider";
    private static final String CONSUMER_BPN = "BPNL000000000CONS";
    private static final String CONSUMER_DID = "did:web:windx-identityhub.tx.test:consumer";

    private static final String CONFIG = PROVIDER_BPN + "=" + PROVIDER_DID + ";" + CONSUMER_BPN + "=" + CONSUMER_DID;

    @Test
    void resolvesBothDirections() {
        var client = StaticBdrsClient.parse(CONFIG);

        assertThat(client.size()).isEqualTo(2);
        assertThat(client.resolveDid(PROVIDER_BPN)).isEqualTo(PROVIDER_DID);
        assertThat(client.resolveDid(CONSUMER_BPN)).isEqualTo(CONSUMER_DID);
        assertThat(client.resolveBpn(PROVIDER_DID)).isEqualTo(PROVIDER_BPN);
        assertThat(client.resolveBpn(CONSUMER_DID)).isEqualTo(CONSUMER_BPN);
    }

    @Test
    void unknownParticipant_returnsNull() {
        var client = StaticBdrsClient.parse(CONFIG);

        // Vertrag des BdrsClient-Interfaces: null, nicht Exception
        assertThat(client.resolveDid("BPNL999999999XXXX")).isNull();
        assertThat(client.resolveBpn("did:web:unknown")).isNull();
    }

    @Test
    void didWithEncodedPort_isKeptIntact() {
        // did:web mit Port ist prozentkodiert und enthaelt weitere ':' -- darf nicht zerschnitten werden
        var did = "did:web:localhost%3A8080:provider";
        var client = StaticBdrsClient.parse(PROVIDER_BPN + "=" + did);

        assertThat(client.resolveDid(PROVIDER_BPN)).isEqualTo(did);
        assertThat(client.resolveBpn(did)).isEqualTo(PROVIDER_BPN);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   " })
    void blankConfig_yieldsEmptyClient(String raw) {
        var client = StaticBdrsClient.parse(raw);

        assertThat(client.size()).isZero();
        assertThat(client.resolveDid(PROVIDER_BPN)).isNull();
    }

    @Test
    void nullConfig_yieldsEmptyClient() {
        assertThat(StaticBdrsClient.parse(null).size()).isZero();
    }

    @Test
    void toleratesWhitespaceAndEmptyEntries() {
        var client = StaticBdrsClient.parse("  " + PROVIDER_BPN + " = " + PROVIDER_DID + " ; ; " + CONSUMER_BPN + "=" + CONSUMER_DID + ";");

        assertThat(client.size()).isEqualTo(2);
        assertThat(client.resolveDid(PROVIDER_BPN)).isEqualTo(PROVIDER_DID);
        assertThat(client.resolveDid(CONSUMER_BPN)).isEqualTo(CONSUMER_DID);
    }

    @Test
    void missingSeparator_failsFast() {
        assertThatThrownBy(() -> StaticBdrsClient.parse("BPNL000000000PROV"))
                .isInstanceOf(EdcException.class)
                .hasMessageContaining("expected format 'BPN=DID'");
    }

    @Test
    void valueIsNotADid_failsFast() {
        assertThatThrownBy(() -> StaticBdrsClient.parse(PROVIDER_BPN + "=http://provider"))
                .isInstanceOf(EdcException.class)
                .hasMessageContaining("is not a DID");
    }

    @Test
    void emptyBpnOrDid_failsFast() {
        assertThatThrownBy(() -> StaticBdrsClient.parse("=" + PROVIDER_DID))
                .isInstanceOf(EdcException.class)
                .hasMessageContaining("must both be non-empty");
    }

    @Test
    void duplicateBpn_failsFast() {
        assertThatThrownBy(() -> StaticBdrsClient.parse(PROVIDER_BPN + "=" + PROVIDER_DID + ";" + PROVIDER_BPN + "=" + CONSUMER_DID))
                .isInstanceOf(EdcException.class)
                .hasMessageContaining("Duplicate BPN");
    }
}
