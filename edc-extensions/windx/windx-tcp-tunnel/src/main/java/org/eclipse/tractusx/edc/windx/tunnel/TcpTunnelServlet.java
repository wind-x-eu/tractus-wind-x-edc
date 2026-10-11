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

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.edc.connector.dataplane.spi.iam.DataPlaneAuthorizationService;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.websocket.core.server.WebSocketServerComponents;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_GATEWAY;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;
import static org.eclipse.tractusx.edc.windx.tunnel.TcpTunnelEndpointDataReferenceService.FLOW_ID_PROPERTY;

/**
 * Nimmt den WebSocket-Upgrade fuer einen Tunnel entgegen.
 *
 * <p>Ablauf: {@code Authorization}-Header -&gt; {@link DataPlaneAuthorizationService#authorize} (gleiches Token wie bei
 * {@code HttpData-PULL}, inkl. Ablauf und Widerruf) -&gt; Source-DataAddress liefert Ziel ({@code host}/{@code port})
 * und Flow-ID -&gt; Flow aktiv? Verbindungslimit frei? -&gt; Upgrade auf eine {@link TcpTunnelSession}.</p>
 *
 * <p>Der WebSocket-Container wird hier im {@link #init()} angelegt, weil der {@code JettyService} von EDC nur Servlets
 * annimmt und keinen Zugriff auf den {@link ServletContextHandler} gibt. {@code init()} laeuft beim Start des
 * Kontexts (init-order 1), also bevor Requests ankommen.</p>
 */
public class TcpTunnelServlet extends HttpServlet {

    private static final String HOST = "host";
    private static final String PORT = "port";
    private static final String MAX_CONNECTIONS = "maxConnections";
    private static final String BEARER = "Bearer ";

    private final transient DataPlaneAuthorizationService authorizationService;
    private final transient TcpTunnelRegistry registry;
    private final transient TunnelSettings settings;
    private final transient Monitor monitor;
    private transient JettyWebSocketServerContainer container;

    public TcpTunnelServlet(DataPlaneAuthorizationService authorizationService, TcpTunnelRegistry registry,
                            TunnelSettings settings, Monitor monitor) {
        this.authorizationService = authorizationService;
        this.registry = registry;
        this.settings = settings;
        this.monitor = monitor;
    }

    @Override
    public void init() {
        var contextHandler = ServletContextHandler.getServletContextHandler(getServletContext(), "TcpTunnel");
        WebSocketServerComponents.ensureWebSocketComponents(contextHandler.getServer(), contextHandler);
        container = JettyWebSocketServerContainer.ensureContainer(getServletContext());
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            handleUpgrade(request, response);
        } catch (RuntimeException | LinkageError e) {
            // Jetty loggt ueber SLF4J, das in der Runtime meist nicht verdrahtet ist -> selbst ins EDC-Log.
            // LinkageError: falsch gemischte Jetty-Versionen zeigen sich erst hier (z.B. IncompatibleClassChangeError).
            monitor.severe("Tunnel upgrade failed", e);
            if (!response.isCommitted()) {
                response.sendError(SC_INTERNAL_SERVER_ERROR, "Tunnel upgrade failed");
            }
        }
    }

    private void handleUpgrade(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!"websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) {
            response.sendError(SC_BAD_REQUEST, "WebSocket upgrade required");
            return;
        }

        var token = extractToken(request.getHeader("Authorization"));
        if (token == null) {
            response.sendError(SC_UNAUTHORIZED, "Authorization header missing");
            return;
        }

        var authorization = authorizationService.authorize(token, Map.of());
        if (authorization.failed()) {
            response.sendError(SC_FORBIDDEN, "Access token invalid");
            return;
        }

        var target = Target.from(authorization.getContent(), settings.defaultMaxConnections());
        if (target == null) {
            monitor.warning("Tunnel asset has no valid '%s'/'%s'/'%s' in its data address".formatted(HOST, PORT, FLOW_ID_PROPERTY));
            response.sendError(SC_BAD_GATEWAY, "Tunnel target not configured");
            return;
        }

        if (!registry.isFlowActive(target.flowId())) {
            response.sendError(SC_FORBIDDEN, "Transfer is not active");
            return;
        }

        if (registry.connectionCount(target.flowId()) >= target.maxConnections()) {
            response.sendError(429, "Connection limit reached");
            return;
        }

        var upgraded = container.upgrade((upgradeRequest, upgradeResponse) -> new TcpTunnelSession(
                target.flowId(), target.host(), target.port(), target.maxConnections(),
                settings.connectTimeoutMs(), settings.idleTimeout(), registry, monitor), request, response);
        if (!upgraded && !response.isCommitted()) {
            response.sendError(SC_BAD_REQUEST, "WebSocket upgrade failed");
        }
    }

    private static String extractToken(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        return header.regionMatches(true, 0, BEARER, 0, BEARER.length()) ? header.substring(BEARER.length()).trim() : header.trim();
    }

    /**
     * Konfiguration aus den Data-Plane-Settings.
     */
    public record TunnelSettings(int defaultMaxConnections, int connectTimeoutMs, Duration idleTimeout) {
    }

    private record Target(String flowId, String host, int port, int maxConnections) {

        static Target from(DataAddress address, int defaultMaxConnections) {
            var flowId = address.getStringProperty(FLOW_ID_PROPERTY);
            var host = address.getStringProperty(HOST);
            var port = parsePositive(address.getStringProperty(PORT), -1);
            if (flowId == null || host == null || host.isBlank() || port <= 0 || port > 65535) {
                return null;
            }
            var maxConnections = parsePositive(address.getStringProperty(MAX_CONNECTIONS), defaultMaxConnections);
            return new Target(flowId, host, port, maxConnections);
        }

        private static int parsePositive(String raw, int fallback) {
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                var value = Integer.parseInt(raw.trim());
                return value > 0 ? value : fallback;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
