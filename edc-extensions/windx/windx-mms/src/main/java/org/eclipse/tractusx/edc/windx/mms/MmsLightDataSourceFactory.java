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

package org.eclipse.tractusx.edc.windx.mms;

import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSource;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSourceFactory;
import org.eclipse.edc.connector.dataplane.spi.pipeline.StreamResult;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.jetbrains.annotations.NotNull;

/**
 * Minimale Source-Factory nur zur <b>Typbewerbung</b> von {@code MmsLight} als {@code allowedSourceType}.
 *
 * <p>{@code MmsLight-PULL} ist ein reiner Consumer-Pull über eine rohe TCP-EDR (s.
 * {@link MmsLightEndpointDataReferenceService}); der eigentliche Datenfluss läuft NICHT über die
 * {@link org.eclipse.edc.connector.dataplane.spi.pipeline.PipelineService}-Pipeline, sondern über den
 * TCP-Proxy. Bei PULL ruft die Data Plane {@link #createSource} daher nie auf. Die Factory existiert nur,
 * damit {@code LegacyDataPlaneSignalingFlowController.transferTypesFor(asset)} den Typ für ein
 * {@code MmsLight}-Asset überhaupt anbietet (Erkenntnis aus Weg A: ohne registrierten Source-Typ verwirft
 * der Controller den Transfer-Typ → Provider 400).</p>
 */
public class MmsLightDataSourceFactory implements DataSourceFactory {

    @Override
    public String supportedType() {
        return WindxMmsLightDataPlaneExtension.MMS_LIGHT_TYPE;
    }

    @Override
    public DataSource createSource(DataFlowStartMessage request) {
        // Wird bei MmsLight-PULL nie aufgerufen (Daten fließen über den TCP-Proxy, nicht die Pipeline).
        return new DataSource() {
            @Override
            public StreamResult<java.util.stream.Stream<Part>> openPartStream() {
                return StreamResult.error("MmsLight ist ein reiner TCP-Pull-Typ; die Pipeline-Source wird nicht genutzt.");
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public @NotNull Result<Void> validateRequest(DataFlowStartMessage request) {
        return Result.success();
    }
}
