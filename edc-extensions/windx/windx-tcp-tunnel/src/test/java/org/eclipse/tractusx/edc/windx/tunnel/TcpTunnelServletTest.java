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

import org.eclipse.edc.connector.dataplane.spi.iam.DataPlaneAuthorizationService;
import org.eclipse.edc.spi.monitor.ConsoleMonitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.web.jetty.JettyConfiguration;
import org.eclipse.edc.web.jetty.JettyService;
import org.eclipse.edc.web.jetty.PortMappingRegistryImpl;
import org.eclipse.edc.web.spi.configuration.PortMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.eclipse.tractusx.edc.windx.tunnel.TcpTunnelEndpointDataReferenceService.FLOW_ID_PROPERTY;

/**
 * Tunnel-Ende-zu-Ende ohne Connector: echter EDC-{@link JettyService} mit dem {@link TcpTunnelServlet},
 * {@code java.net.http}-WebSocket-Client als Consumer und ein TCP-Echo-Server als Tunnelziel.
 */
class TcpTunnelServletTest {

    private static final String FLOW_ID = "flow-1";
    private static final String VALID_TOKEN = "valid-token";
    private static final String LIMITED_TOKEN = "limited-token";

    private final Set<String> activeFlows = ConcurrentHashMap.newKeySet();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private EchoServer echoServer;
    private JettyService jetty;
    private TcpTunnelRegistry registry;
    private URI tunnelUri;

    @BeforeEach
    void setUp() throws IOException {
        var monitor = new ConsoleMonitor();
        echoServer = new EchoServer();
        activeFlows.add(FLOW_ID);

        registry = new TcpTunnelRegistry(activeFlows::contains, Duration.ofMillis(200), monitor);
        registry.start();

        DataPlaneAuthorizationService authorizationService = (token, requestData) -> switch (token) {
            case VALID_TOKEN -> Result.success(targetAddress(Map.of()));
            case LIMITED_TOKEN -> Result.success(targetAddress(Map.of("maxConnections", "1")));
            default -> Result.failure("invalid token");
        };

        var port = freePort();
        var portMappings = new PortMappingRegistryImpl();
        portMappings.register(new PortMapping("tunnel", port, "/api/tunnel"));
        jetty = new JettyService(new JettyConfiguration(null, null), monitor, portMappings);
        var settings = new TcpTunnelServlet.TunnelSettings(4, 2000, Duration.ofMinutes(1));
        // Reihenfolge wie in der Runtime: erst Jetty starten, dann Servlet registrieren
        jetty.start();
        jetty.registerServlet("tunnel", new TcpTunnelServlet(authorizationService, registry, settings, monitor));

        tunnelUri = URI.create("ws://localhost:%d/api/tunnel".formatted(port));
    }

    @AfterEach
    void tearDown() throws IOException {
        registry.shutdown();
        jetty.shutdown();
        echoServer.close();
    }

    @Test
    void tunnelsBytesInBothDirections() {
        var client = connect(VALID_TOKEN);
        var payload = new byte[256 * 1024];
        new Random(42).nextBytes(payload);

        // in mehreren Messages senden, wie ein Agent, der TCP-Chunks weiterreicht
        for (var offset = 0; offset < payload.length; offset += 10_000) {
            var length = Math.min(10_000, payload.length - offset);
            client.webSocket.sendBinary(ByteBuffer.wrap(payload, offset, length), true).join();
        }

        await().atMost(5, TimeUnit.SECONDS).until(() -> client.received.size() == payload.length);
        assertThat(client.received.toByteArray()).isEqualTo(payload);
        assertThat(registry.connectionCount(FLOW_ID)).isEqualTo(1);
    }

    @Test
    void acceptsBearerPrefix() {
        var client = connect("Bearer " + VALID_TOKEN);
        client.webSocket.sendBinary(ByteBuffer.wrap(new byte[]{ 1, 2, 3 }), true).join();
        await().atMost(5, TimeUnit.SECONDS).until(() -> client.received.size() == 3);
    }

    @Test
    void rejectsMissingToken() {
        assertHandshakeStatus(null, 401);
    }

    @Test
    void rejectsInvalidToken() {
        assertHandshakeStatus("wrong", 403);
    }

    @Test
    void rejectsInactiveFlow() {
        activeFlows.clear();
        assertHandshakeStatus(VALID_TOKEN, 403);
    }

    @Test
    void enforcesConnectionLimitOfAsset() {
        connect(LIMITED_TOKEN);
        await().atMost(5, TimeUnit.SECONDS).until(() -> registry.connectionCount(FLOW_ID) == 1);
        assertHandshakeStatus(LIMITED_TOKEN, 429);
    }

    @Test
    void revokeClosesOpenTunnels() {
        var client = connect(VALID_TOKEN);
        await().atMost(5, TimeUnit.SECONDS).until(() -> registry.connectionCount(FLOW_ID) == 1);

        assertThat(registry.closeFlow(FLOW_ID, "transfer revoked")).isEqualTo(1);

        assertThat(client.closeCode.orTimeout(5, TimeUnit.SECONDS).join()).isEqualTo(TcpTunnelSession.POLICY_VIOLATION);
        await().atMost(5, TimeUnit.SECONDS).until(() -> echoServer.openConnections() == 0);
    }

    @Test
    void watchdogClosesTunnelWhenFlowEnds() {
        var client = connect(VALID_TOKEN);
        await().atMost(5, TimeUnit.SECONDS).until(() -> registry.connectionCount(FLOW_ID) == 1);

        activeFlows.remove(FLOW_ID);

        assertThat(client.closeCode.orTimeout(5, TimeUnit.SECONDS).join()).isEqualTo(TcpTunnelSession.POLICY_VIOLATION);
        assertThat(registry.connectionCount(FLOW_ID)).isZero();
    }

    @Test
    void closesWebSocketWhenTargetClosesConnection() {
        var client = connect(VALID_TOKEN);
        await().atMost(5, TimeUnit.SECONDS).until(() -> echoServer.openConnections() == 1);

        echoServer.closeAllConnections();

        assertThat(client.closeCode.orTimeout(5, TimeUnit.SECONDS).join()).isEqualTo(TcpTunnelSession.NORMAL);
    }

    private DataAddress targetAddress(Map<String, String> extra) {
        var builder = DataAddress.Builder.newInstance()
                .type(TcpTunnelDataPlaneExtension.TCP_TUNNEL_TYPE)
                .property("host", "localhost")
                .property("port", String.valueOf(echoServer.port()))
                .property(FLOW_ID_PROPERTY, FLOW_ID);
        extra.forEach(builder::property);
        return builder.build();
    }

    private TunnelClient connect(String authorization) {
        var listener = new TunnelClient();
        var builder = httpClient.newWebSocketBuilder();
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        listener.webSocket = builder.buildAsync(tunnelUri, listener).join();
        return listener;
    }

    private void assertHandshakeStatus(String authorization, int status) {
        assertThatThrownBy(() -> connect(authorization))
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOfSatisfying(WebSocketHandshakeException.class,
                        e -> assertThat(e.getResponse().statusCode()).isEqualTo(status));
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class TunnelClient implements WebSocket.Listener {
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        private WebSocket webSocket;

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            var bytes = new byte[data.remaining()];
            data.get(bytes);
            synchronized (received) {
                received.writeBytes(bytes);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closeCode.completeExceptionally(error);
        }
    }

    /**
     * TCP-Echo-Server als Tunnelziel (stellvertretend fuer OPC-UA-Server, MQTT-Broker oder IED).
     */
    private static final class EchoServer implements AutoCloseable {
        private final ServerSocket serverSocket = new ServerSocket(0);
        private final Set<Socket> connections = ConcurrentHashMap.newKeySet();

        EchoServer() throws IOException {
            var acceptor = new Thread(this::acceptLoop, "echo-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        int openConnections() {
            connections.removeIf(Socket::isClosed);
            return connections.size();
        }

        void closeAllConnections() {
            connections.forEach(socket -> {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Test-Hilfe
                }
            });
        }

        private void acceptLoop() {
            while (!serverSocket.isClosed()) {
                try {
                    var socket = serverSocket.accept();
                    connections.add(socket);
                    var echo = new Thread(() -> echo(socket), "echo-connection");
                    echo.setDaemon(true);
                    echo.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void echo(Socket socket) {
            try (socket; var in = socket.getInputStream(); var out = socket.getOutputStream()) {
                var buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException ignored) {
                // Verbindung beendet
            }
        }

        @Override
        public void close() throws IOException {
            closeAllConnections();
            serverSocket.close();
        }
    }
}
