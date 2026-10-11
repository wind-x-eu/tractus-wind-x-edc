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

import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSource;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSourceFactory;
import org.eclipse.edc.connector.dataplane.spi.pipeline.StreamResult;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;

import java.util.stream.Stream;

/**
 * Bewirbt nur den Source-Typ {@link TcpTunnelDataPlaneExtension#TCP_TUNNEL_TYPE} als {@code allowedSourceType}
 * (gleicher Grund wie bei {@code MediatorHttpDataSourceFactory}). Die Bytes fliessen ueber das
 * {@link TcpTunnelServlet}, nicht ueber die Pipeline; {@link #createSource} wird bei PULL nie geoeffnet.
 */
public class TcpTunnelDataSourceFactory implements DataSourceFactory {

    @Override
    public String supportedType() {
        return TcpTunnelDataPlaneExtension.TCP_TUNNEL_TYPE;
    }

    @Override
    public Result<Void> validateRequest(DataFlowStartMessage request) {
        return Result.success();
    }

    @Override
    public DataSource createSource(DataFlowStartMessage request) {
        return new DataSource() {
            @Override
            public StreamResult<Stream<Part>> openPartStream() {
                return StreamResult.error("TcpTunnel is served by the tunnel WebSocket endpoint, not via the data-plane pipeline");
            }

            @Override
            public void close() {
            }
        };
    }
}
