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

package org.eclipse.tractusx.edc.windx.tunnel;

import org.eclipse.edc.connector.dataplane.spi.DataFlow;
import org.eclipse.edc.connector.dataplane.spi.edr.EndpointDataReferenceService;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.result.ServiceResult;
import org.eclipse.edc.spi.types.domain.DataAddress;

/**
 * EDR-Service fuer {@code TcpTunnel-PULL}: delegiert an den Standard-Token-Service der Data Plane und haengt den
 * Tunnel-Lebenszyklus an den Flow.
 *
 * <p>{@code DataPlaneAuthorizationService.authorize(token)} liefert spaeter nur die Source-DataAddress zurueck, nicht
 * die Flow-ID. Deshalb wird die Flow-ID vor der Token-Ausgabe als {@link #FLOW_ID_PROPERTY} in eine Kopie der
 * Source geschrieben; das {@link TcpTunnelServlet} ordnet eine Verbindung darueber ihrem Flow zu.</p>
 *
 * <p>{@code revoke} (Terminate/Suspend des Transfers) widerruft das Token und schliesst alle offenen Tunnel des Flows.</p>
 */
public class TcpTunnelEndpointDataReferenceService implements EndpointDataReferenceService {

    public static final String FLOW_ID_PROPERTY = "windxTunnelFlowId";

    private final EndpointDataReferenceService delegate;
    private final TcpTunnelRegistry registry;
    private final Monitor monitor;

    public TcpTunnelEndpointDataReferenceService(EndpointDataReferenceService delegate, TcpTunnelRegistry registry, Monitor monitor) {
        this.delegate = delegate;
        this.registry = registry;
        this.monitor = monitor;
    }

    @Override
    public Result<DataAddress> createEndpointDataReference(DataFlow dataFlow) {
        var source = dataFlow.getActualSource();
        var markedSource = DataAddress.Builder.newInstance()
                .properties(source.getProperties())
                .property(FLOW_ID_PROPERTY, dataFlow.getId())
                .build();

        var markedFlow = DataFlow.Builder.newInstance()
                .id(dataFlow.getId())
                .state(dataFlow.getState())
                .source(markedSource)
                .destination(dataFlow.getDestination())
                .callbackAddress(dataFlow.getCallbackAddress())
                .transferType(dataFlow.getTransferType())
                .properties(dataFlow.getProperties())
                .runtimeId(dataFlow.getRuntimeId())
                .participantId(dataFlow.getParticipantId())
                .assetId(dataFlow.getAssetId())
                .agreementId(dataFlow.getAgreementId())
                .build();

        return delegate.createEndpointDataReference(markedFlow);
    }

    @Override
    public ServiceResult<Void> revokeEndpointDataReference(String flowId, String reason) {
        // die Data Plane reicht beim Terminate ueber DSP haeufig keinen Grund durch
        var effectiveReason = reason == null || reason.isBlank() ? "transfer terminated or suspended" : reason;
        var closed = registry.closeFlow(flowId, "transfer revoked: " + effectiveReason);
        if (closed > 0) {
            monitor.info("Closed %d tunnel connection(s) of flow %s (reason: %s)".formatted(closed, flowId, effectiveReason));
        }
        return delegate.revokeEndpointDataReference(flowId, reason);
    }
}
