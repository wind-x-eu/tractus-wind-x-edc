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
import org.eclipse.edc.spi.types.domain.transfer.FlowType;
import org.eclipse.edc.spi.types.domain.transfer.TransferType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.tractusx.edc.windx.tunnel.TcpTunnelEndpointDataReferenceService.FLOW_ID_PROPERTY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TcpTunnelEndpointDataReferenceServiceTest {

    private final EndpointDataReferenceService delegate = mock();
    private final TcpTunnelRegistry registry = new TcpTunnelRegistry(id -> true, Duration.ofMinutes(1), mock(Monitor.class));
    private final TcpTunnelEndpointDataReferenceService service = new TcpTunnelEndpointDataReferenceService(delegate, registry, mock(Monitor.class));

    @Test
    void writesFlowIdIntoSourceBeforeDelegating() {
        var edr = DataAddress.Builder.newInstance().type("https://w3id.org/idsa/v4.1/HTTP").build();
        when(delegate.createEndpointDataReference(any())).thenReturn(Result.success(edr));
        var source = DataAddress.Builder.newInstance()
                .type(TcpTunnelDataPlaneExtension.TCP_TUNNEL_TYPE)
                .property("host", "opcua.internal")
                .property("port", "4840")
                .build();
        var flow = DataFlow.Builder.newInstance()
                .id("flow-42")
                .source(source)
                .transferType(new TransferType(TcpTunnelDataPlaneExtension.TCP_TUNNEL_TYPE, FlowType.PULL))
                .participantId("did:web:consumer")
                .assetId("asset-1")
                .agreementId("agreement-1")
                .build();

        var result = service.createEndpointDataReference(flow);

        assertThat(result.getContent()).isSameAs(edr);
        var captor = ArgumentCaptor.forClass(DataFlow.class);
        verify(delegate).createEndpointDataReference(captor.capture());
        var marked = captor.getValue();
        assertThat(marked.getId()).isEqualTo("flow-42");
        assertThat(marked.getSource().getStringProperty(FLOW_ID_PROPERTY)).isEqualTo("flow-42");
        assertThat(marked.getSource().getStringProperty("host")).isEqualTo("opcua.internal");
        assertThat(marked.getSource().getType()).isEqualTo(TcpTunnelDataPlaneExtension.TCP_TUNNEL_TYPE);
        assertThat(marked.getParticipantId()).isEqualTo("did:web:consumer");
        assertThat(marked.getAgreementId()).isEqualTo("agreement-1");
        assertThat(flow.getSource().getStringProperty(FLOW_ID_PROPERTY)).as("original source unchanged").isNull();
    }

    @Test
    void revokeDelegates() {
        when(delegate.revokeEndpointDataReference("flow-42", "terminated")).thenReturn(ServiceResult.success());

        assertThat(service.revokeEndpointDataReference("flow-42", "terminated").succeeded()).isTrue();
        verify(delegate).revokeEndpointDataReference("flow-42", "terminated");
    }
}
