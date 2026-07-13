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

import org.eclipse.edc.connector.dataplane.spi.DataFlow;
import org.eclipse.edc.connector.dataplane.spi.edr.EndpointDataReferenceService;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.result.ServiceResult;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.tractusx.edc.windx.mms.client.MMSTcpProxy;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EDR-Service für {@code MmsLight-PULL} — portierter BIBA-{@code MMSLightDataFlowController} als 0.17-Data-Plane-Hook.
 *
 * <p>BIBA öffnete den TCP-Tunnel in einem Control-Plane-{@code DataFlowController}; in EDC 0.17 gibt es dafür
 * keinen {@code DataFlowManager} mehr (s. {@code docs/phase-3-recherche.md}). Der korrekte Ersatz ist eine eigene
 * {@link EndpointDataReferenceService}-Implementierung, registriert für den Destination-Typ {@code MmsLight}:
 * {@link org.eclipse.edc.connector.dataplane.framework.manager.DataPlaneManagerImpl} ruft bei einem PULL-Flow
 * {@code create()} (→ {@link #createEndpointDataReference}) und bei terminate/suspend {@code revoke()}
 * (→ {@link #revokeEndpointDataReference}) auf — genau die start/stop-Lifecycle-Punkte, die BIBAs Controller hatte.</p>
 *
 * <p>{@code createEndpointDataReference} startet pro Transfer einen {@link MMSTcpProxy} (der sich über den schon
 * hardware-verifizierten {@link org.eclipse.tractusx.edc.windx.mms.client.MMSClient} zum echten MMS-Server verbindet,
 * kontinuierlich pollt und selbst als MMS-Server gegenüber dem Consumer auftritt) und liefert dessen dynamischen
 * TCP-Port als {@code MmsTcp}-EDR ({@code tcp://host:port}). Der Consumer öffnet damit eine rohe TCP-Verbindung und
 * spricht nativ MMS/IEC-61850.</p>
 */
public class MmsLightEndpointDataReferenceService implements EndpointDataReferenceService {

    private static final String PROP_HOST = "mms:serverHost";
    private static final String PROP_PORT = "mms:serverPort";
    private static final String PROP_LOGICAL_DEVICE = "mms:logicalDevice";
    private static final String PROP_SENSORS = "mms:sensors";
    private static final String PROP_POLLING_INTERVAL = "mms:pollingInterval";

    private static final int DEFAULT_MMS_PORT = 102;
    private static final long DEFAULT_POLLING_INTERVAL_MS = 5000L;
    /** 0 → das Betriebssystem vergibt einen freien Port pro Transfer (BIBA hatte einen festen Port ⇒ nur 1 Flow). */
    private static final int EPHEMERAL_PROXY_PORT = 0;

    private final String connectorHost;
    private final Monitor monitor;
    private final Map<String, MMSTcpProxy> activeProxies = new ConcurrentHashMap<>();

    public MmsLightEndpointDataReferenceService(String connectorHost, Monitor monitor) {
        this.connectorHost = connectorHost;
        this.monitor = monitor;
    }

    @Override
    public Result<DataAddress> createEndpointDataReference(DataFlow dataFlow) {
        var source = dataFlow.getSource();
        var mmsHost = orDefault(source.getStringProperty(PROP_HOST), "localhost");
        var mmsPort = parseInt(source.getStringProperty(PROP_PORT), DEFAULT_MMS_PORT);
        var logicalDevice = source.getStringProperty(PROP_LOGICAL_DEVICE);
        var sensors = source.getStringProperty(PROP_SENSORS);
        var pollingInterval = parseLong(source.getStringProperty(PROP_POLLING_INTERVAL), DEFAULT_POLLING_INTERVAL_MS);

        if (logicalDevice == null || logicalDevice.isBlank()) {
            return Result.failure("Property '%s' fehlt in der Source-DataAddress".formatted(PROP_LOGICAL_DEVICE));
        }
        if (sensors == null || sensors.isBlank()) {
            return Result.failure("Property '%s' fehlt in der Source-DataAddress".formatted(PROP_SENSORS));
        }

        try {
            var proxy = new MMSTcpProxy(mmsHost, mmsPort, EPHEMERAL_PROXY_PORT, logicalDevice, sensors, pollingInterval);
            proxy.start();
            activeProxies.put(dataFlow.getId(), proxy);

            var port = proxy.getLocalPort();
            var url = "tcp://%s:%d".formatted(connectorHost, port);
            monitor.info("MmsLight TCP-Proxy gestartet: %s → %s:%d [Device=%s, Sensors=%s, Polling=%dms, flowId=%s]"
                    .formatted(url, mmsHost, mmsPort, logicalDevice, sensors, pollingInterval, dataFlow.getId()));

            return Result.success(DataAddress.Builder.newInstance()
                    .type("MmsTcp")
                    .property("host", connectorHost)
                    .property("port", String.valueOf(port))
                    .property("url", url)
                    .property(PROP_LOGICAL_DEVICE, logicalDevice)
                    .property(PROP_SENSORS, sensors)
                    .build());
        } catch (IOException e) {
            monitor.severe("MmsLight TCP-Proxy konnte nicht gestartet werden (flowId=%s): %s"
                    .formatted(dataFlow.getId(), e.getMessage()), e);
            return Result.failure("MmsLight TCP-Proxy konnte nicht gestartet werden: " + e.getMessage());
        }
    }

    @Override
    public ServiceResult<Void> revokeEndpointDataReference(String flowId, String reason) {
        var proxy = activeProxies.remove(flowId);
        if (proxy != null) {
            proxy.stop();
            monitor.info("MmsLight TCP-Proxy gestoppt (flowId=%s, reason=%s)".formatted(flowId, reason));
        }
        return ServiceResult.success();
    }

    /** Stoppt alle laufenden Proxies (für Extension-Shutdown). */
    public void stopAll() {
        activeProxies.values().forEach(MMSTcpProxy::stop);
        activeProxies.clear();
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            monitor.warning("MmsLight: '%s' ist keine gültige Zahl, nutze Default %d".formatted(raw, fallback));
            return fallback;
        }
    }

    private long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            monitor.warning("MmsLight: '%s' ist keine gültige Zahl, nutze Default %d".formatted(raw, fallback));
            return fallback;
        }
    }
}
