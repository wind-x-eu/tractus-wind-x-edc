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

import org.eclipse.edc.spi.iam.AudienceResolver;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.domain.message.ProtocolRemoteMessage;
import org.eclipse.tractusx.edc.spi.identity.mapper.BdrsClient;

import static org.eclipse.tractusx.edc.spi.identity.mapper.BdrsConstants.DID_PREFIX;

/**
 * Loest die Audience eines ausgehenden DSP-Requests auf: ist die {@code counterPartyId} bereits eine DID,
 * wird sie durchgereicht; ist sie ein BPN, wird sie ueber den {@link BdrsClient} aufgeloest.
 *
 * <p>Verhaltensgleich zum {@code BdrsClientAudienceMapper} aus dem Modul {@code bdrs-client}. Dieser Ersatz
 * ist noetig, weil {@code bdrs-client} <b>zwei</b> Services liefert — {@code BdrsClient} <i>und</i> diesen
 * {@link AudienceResolver}. Wer das Modul ausschliesst, muss beide ersetzen; ein fehlender AudienceResolver
 * faellt sonst erst beim ersten DSP-Call auf.</p>
 */
public class StaticAudienceResolver implements AudienceResolver {

    private final BdrsClient client;

    public StaticAudienceResolver(BdrsClient client) {
        this.client = client;
    }

    @Override
    public Result<String> resolve(ProtocolRemoteMessage remoteMessage) {
        var counterPartyId = remoteMessage.getCounterPartyId();
        if (counterPartyId == null || counterPartyId.isBlank()) {
            return Result.failure("Cannot resolve audience: counterPartyId is missing");
        }
        if (counterPartyId.startsWith(DID_PREFIX)) {
            return Result.success(counterPartyId);
        }

        var did = client.resolveDid(counterPartyId);
        if (did == null) {
            return Result.failure("Cannot resolve audience: no DID configured for BPN '%s'".formatted(counterPartyId));
        }
        return Result.success(did);
    }
}
