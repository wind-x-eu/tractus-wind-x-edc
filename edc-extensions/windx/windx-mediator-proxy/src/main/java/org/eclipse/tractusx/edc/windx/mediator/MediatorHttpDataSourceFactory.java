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

package org.eclipse.tractusx.edc.windx.mediator;

import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSource;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSourceFactory;
import org.eclipse.edc.connector.dataplane.spi.pipeline.StreamResult;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;

import java.util.stream.Stream;

/**
 * Schlanke {@link DataSourceFactory}, die ausschliesslich den Source-Typ
 * {@link MediatorDataPlaneExtension#MEDIATOR_HTTP_DATA} bei der Data Plane bewirbt.
 *
 * <p>Hintergrund: Der Provider meldet einen PULL-Transfer-Typ nur dann als unterstuetzt, wenn eine
 * registrierte Data Plane den Source-Typ des Assets in {@code allowedSourceTypes} fuehrt. Diese Menge
 * stammt aus {@code PipelineService.supportedSourceTypes()}, also aus den registrierten
 * {@code DataSourceFactory}s. Ohne diese Factory verwirft der
 * {@code LegacyDataPlaneSignalingFlowController.transferTypesFor(asset)} den Typ
 * {@code MediatorHttpData-PULL} ("TransferType ... is not supported").</p>
 *
 * <p>Der eigentliche Datenabruf laeuft <b>nicht</b> ueber den PipelineService, sondern ueber den
 * {@link MediatorController} (autorisierter GET → Semantic-Mediator-POST). Diese Source wird im
 * Consumer-Pull-Pfad daher nie geoeffnet; {@link #createSource} liefert nur eine inaktive Source.</p>
 */
public class MediatorHttpDataSourceFactory implements DataSourceFactory {

    @Override
    public String supportedType() {
        return MediatorDataPlaneExtension.MEDIATOR_HTTP_DATA;
    }

    @Override
    public Result<Void> validateRequest(DataFlowStartMessage request) {
        return Result.success();
    }

    @Override
    public DataSource createSource(DataFlowStartMessage request) {
        return new MediatorHttpDataSource();
    }

    /**
     * Inaktive Source: der Pull wird vom {@link MediatorController} bedient, nicht ueber die Pipeline.
     */
    private static final class MediatorHttpDataSource implements DataSource {
        @Override
        public StreamResult<Stream<Part>> openPartStream() {
            return StreamResult.error("MediatorHttpData is served by the med controller, not via the data-plane pipeline");
        }

        @Override
        public void close() {
        }
    }
}
