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

import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSink;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSinkFactory;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.jetbrains.annotations.NotNull;

import java.net.URI;

/**
 * Factory für den {@code Mms-PUSH}-Sink. Der Destination-Typ {@code Mms} hat zwei Wirkungen:
 * die Self-Registration berechnet die beworbenen PUSH-Transfertypen aus {@code supportedSinkTypes()}
 * (→ {@code Mms-PUSH} im Katalog), und die PipelineService dispatcht zur Laufzeit über
 * {@code dataDestination.type} hierher. Der Consumer sendet also
 * {@code transferType="Mms-PUSH", dataDestination={type:"Mms", baseUrl:"http://…"}}.
 */
public class MmsPushSinkFactory implements DataSinkFactory {

    /**
     * Ziel-URL in der Destination-DataAddress. {@code getStringProperty} löst sowohl den
     * JSON-LD-expandierten Key ({@code edc:baseUrl}, Management-API) als auch den rohen Key auf.
     */
    private static final String PROP_BASE_URL = "baseUrl";

    private final Monitor monitor;

    public MmsPushSinkFactory(Monitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public String supportedType() {
        return WindxMmsDataPlaneExtension.MMS_TRANSFER_DESTINATION_TYPE;
    }

    @Override
    public DataSink createSink(DataFlowStartMessage request) {
        var baseUrl = request.getDestinationDataAddress().getStringProperty(PROP_BASE_URL);
        return new MmsPushSink(baseUrl, request.getProcessId(), monitor);
    }

    @Override
    public @NotNull Result<Void> validateRequest(DataFlowStartMessage request) {
        var baseUrl = request.getDestinationDataAddress().getStringProperty(PROP_BASE_URL);
        if (baseUrl == null || baseUrl.isBlank()) {
            return Result.failure("Mms-PUSH: 'baseUrl' fehlt in der dataDestination");
        }
        try {
            if (URI.create(baseUrl).getScheme() == null) {
                return Result.failure("Mms-PUSH: 'baseUrl' hat kein Schema (http/https): " + baseUrl);
            }
        } catch (IllegalArgumentException e) {
            return Result.failure("Mms-PUSH: 'baseUrl' ist keine gültige URI: " + e.getMessage());
        }
        return Result.success();
    }
}
