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

import org.eclipse.edc.connector.dataplane.spi.DataFlowStates;
import org.eclipse.edc.connector.dataplane.spi.Endpoint;
import org.eclipse.edc.connector.dataplane.spi.edr.EndpointDataReferenceService;
import org.eclipse.edc.connector.dataplane.spi.edr.EndpointDataReferenceServiceRegistry;
import org.eclipse.edc.connector.dataplane.spi.iam.DataPlaneAuthorizationService;
import org.eclipse.edc.connector.dataplane.spi.iam.PublicEndpointGeneratorService;
import org.eclipse.edc.connector.dataplane.spi.pipeline.PipelineService;
import org.eclipse.edc.connector.dataplane.spi.store.DataPlaneStore;
import org.eclipse.edc.runtime.metamodel.annotation.Configuration;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.runtime.metamodel.annotation.Settings;
import org.eclipse.edc.spi.system.Hostname;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.web.spi.WebServer;
import org.eclipse.edc.web.spi.configuration.PortMapping;
import org.eclipse.edc.web.spi.configuration.PortMappingRegistry;

import java.time.Duration;
import java.util.Set;

import static org.eclipse.tractusx.edc.windx.tunnel.TcpTunnelDataPlaneExtension.NAME;

/**
 * Wind-X TCP-Tunnel data plane extension: protokollneutraler TCP-über-WebSocket-Tunnel.
 *
 * <p>Ein Asset vom Typ {@link #TCP_TUNNEL_TYPE} beschreibt ein TCP-Ziel ({@code host}/{@code port}) hinter der
 * Provider-Data-Plane, z.B. einen OPC-UA-Server (4840), einen MQTT-Broker (1883) oder ein IED (MMS, 102). Ein
 * {@code TcpTunnel-PULL}-Transfer liefert dem Consumer einen EDR mit {@code wss://.../tunnel}-Endpoint und dem
 * Standard-Data-Plane-Token. Der Consumer baut damit eine WebSocket-Verbindung auf (Token im
 * {@code Authorization}-Header des Upgrade-Requests); die Data Plane oeffnet dann die TCP-Verbindung zum Ziel und
 * kopiert die Bytes in beide Richtungen, ohne das Protokoll zu kennen.</p>
 *
 * <p>Registrierungen (Muster wie {@code windx-mediator-proxy}):</p>
 * <ol>
 *   <li>eigener Web-Kontext {@value #TUNNEL} auf eigenem Port mit dem {@link TcpTunnelServlet} (WebSocket-Upgrade);</li>
 *   <li>Endpoint-Generator-Funktion fuer {@link #TCP_TUNNEL_TYPE} -> der EDR zeigt auf den Tunnel-Endpoint;</li>
 *   <li>{@link TcpTunnelEndpointDataReferenceService} fuer {@link #TCP_TUNNEL_TYPE}: delegiert die Token-Ausgabe an den
 *       {@link DataPlaneAuthorizationService} und schliesst bei {@code revoke} (Terminate/Suspend) alle offenen Tunnel
 *       des Flows;</li>
 *   <li>{@link TcpTunnelDataSourceFactory} nur zur Typbewerbung als {@code allowedSourceType}.</li>
 * </ol>
 *
 * <p>Zusaetzlich prueft ein Watchdog periodisch den Zustand der Flows im {@link DataPlaneStore}. Damit werden Tunnel
 * auch dann geschlossen, wenn der Revoke eine andere Data-Plane-Replik erreicht hat.</p>
 */
@Extension(value = NAME)
public class TcpTunnelDataPlaneExtension implements ServiceExtension {

    public static final String NAME = "Wind-X TCP Tunnel Data Plane Extension";

    /** Durchgaengiger Typ: Asset-{@code dataAddress.type} == Registry-Key == Transfer-destinationType -> {@code TcpTunnel-PULL}. */
    public static final String TCP_TUNNEL_TYPE = "TcpTunnel";

    private static final String TUNNEL = "tunnel";
    private static final int DEFAULT_TUNNEL_PORT = 8088;
    private static final String DEFAULT_TUNNEL_PATH = "/api/tunnel";

    /** Flow-Zustaende, in denen ein offener Tunnel geschlossen wird. */
    private static final Set<Integer> INACTIVE_STATES = Set.of(
            DataFlowStates.COMPLETED.code(),
            DataFlowStates.SUSPENDED.code(),
            DataFlowStates.TERMINATED.code(),
            DataFlowStates.FAILED.code(),
            DataFlowStates.DEPROVISIONING.code(),
            DataFlowStates.DEPROVISION_REQUESTED.code(),
            DataFlowStates.DEPROVISIONED.code(),
            DataFlowStates.DEPROVISION_FAILED.code());

    @Setting(description = "Public endpoint of the tunnel API context that is written into the EDR (consumer connect address), " +
            "e.g. wss://dataplane.example.com/api/tunnel. Defaults to ws://<hostname>:<tunnel-port><tunnel-path> if not set.",
            key = "edc.dataplane.windx.tunnel.endpoint", required = false)
    private String tunnelPublicEndpoint;

    @Setting(description = "Max. concurrent tunnel connections per transfer, unless the asset sets 'maxConnections'",
            key = "edc.dataplane.windx.tunnel.max.connections", defaultValue = "4")
    private int defaultMaxConnections;

    @Setting(description = "Timeout in milliseconds for connecting to the tunnel target",
            key = "edc.dataplane.windx.tunnel.connect.timeout.ms", defaultValue = "5000")
    private int connectTimeoutMs;

    @Setting(description = "Idle timeout of a tunnel connection in seconds (no traffic in either direction)",
            key = "edc.dataplane.windx.tunnel.idle.timeout.seconds", defaultValue = "600")
    private long idleTimeoutSeconds;

    @Setting(description = "Interval in seconds for keep-alive pings and the flow-state watchdog",
            key = "edc.dataplane.windx.tunnel.watchdog.interval.seconds", defaultValue = "30")
    private long watchdogIntervalSeconds;

    @Configuration
    private TunnelApiConfiguration apiConfiguration;

    @Inject
    private PortMappingRegistry portMappingRegistry;
    @Inject
    private PublicEndpointGeneratorService generatorService;
    @Inject
    private WebServer webServer;
    @Inject
    private DataPlaneAuthorizationService authorizationService;
    @Inject
    private EndpointDataReferenceServiceRegistry edrServiceRegistry;
    @Inject
    private PipelineService pipelineService;
    @Inject
    private DataPlaneStore dataPlaneStore;
    @Inject
    private Hostname hostname;

    private TcpTunnelRegistry registry;
    private TcpTunnelServlet servlet;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor().withPrefix("TcpTunnel");

        // 1) eigener Web-Kontext auf eigenem Port (wie 'med'), damit Ingress/Timeouts getrennt konfigurierbar sind
        var portMapping = new PortMapping(TUNNEL, apiConfiguration.port(), apiConfiguration.path());
        portMappingRegistry.register(portMapping);

        if (tunnelPublicEndpoint == null || tunnelPublicEndpoint.isBlank()) {
            tunnelPublicEndpoint = "ws://%s:%d%s".formatted(hostname.get(), portMapping.port(), portMapping.path());
            monitor.warning("The tunnel API public endpoint was not configured, defaulting to '%s'.".formatted(tunnelPublicEndpoint));
        }

        registry = new TcpTunnelRegistry(this::isFlowActive, Duration.ofSeconds(watchdogIntervalSeconds), monitor);

        // 2) EDR-Endpoint fuer den Tunnel-Typ
        var endpoint = Endpoint.url(tunnelPublicEndpoint);
        generatorService.addGeneratorFunction(TCP_TUNNEL_TYPE, dataAddress -> endpoint);

        // 3) Token-Ausgabe delegieren, Revoke -> offene Tunnel schliessen
        edrServiceRegistry.register(TCP_TUNNEL_TYPE,
                new TcpTunnelEndpointDataReferenceService((EndpointDataReferenceService) authorizationService, registry, monitor));

        // 4) Source-Typ bewerben (allowedSourceTypes), sonst bietet der Provider 'TcpTunnel-PULL' nicht an
        pipelineService.registerFactory(new TcpTunnelDataSourceFactory());

        // 5) WebSocket-Upgrade-Servlet (registriert erst in start(), s.u.)
        servlet = new TcpTunnelServlet(authorizationService, registry,
                new TcpTunnelServlet.TunnelSettings(defaultMaxConnections, connectTimeoutMs, Duration.ofSeconds(idleTimeoutSeconds)), monitor);

        monitor.info("%s initialized: context '%s' on %s, transfer type '%s-PULL'"
                .formatted(NAME, TUNNEL, tunnelPublicEndpoint, TCP_TUNNEL_TYPE));
    }

    /**
     * Registriert das Servlet erst hier: {@code JettyService.start()} legt die Kontext-Handler neu an und verwirft
     * dabei vorher registrierte Servlets. Da diese Extension den {@link WebServer} injiziert, laeuft ihr
     * {@code start()} nach dem Jetty-Start (wie bei Jersey).
     */
    @Override
    public void start() {
        webServer.registerServlet(TUNNEL, servlet);
        registry.start();
    }

    @Override
    public void shutdown() {
        if (registry != null) {
            registry.shutdown();
        }
    }

    private boolean isFlowActive(String flowId) {
        var flow = dataPlaneStore.findById(flowId);
        return flow != null && !INACTIVE_STATES.contains(flow.getState());
    }

    @Settings
    record TunnelApiConfiguration(
            @Setting(key = "web.http." + TUNNEL + ".port", description = "Port for " + TUNNEL + " api context", defaultValue = DEFAULT_TUNNEL_PORT + "")
            int port,
            @Setting(key = "web.http." + TUNNEL + ".path", description = "Path for " + TUNNEL + " api context", defaultValue = DEFAULT_TUNNEL_PATH)
            String path
    ) {
    }
}
