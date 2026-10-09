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

package org.eclipse.tractusx.edc.tests.transfer;

import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferProcessStates;
import org.eclipse.edc.jsonld.spi.JsonLd;
import org.eclipse.edc.junit.annotations.EndToEndTest;
import org.eclipse.edc.junit.extensions.RuntimeExtension;
import org.eclipse.edc.spi.system.configuration.Config;
import org.eclipse.edc.spi.system.configuration.ConfigFactory;
import org.eclipse.tractusx.edc.spi.identity.mapper.BdrsClient;
import org.eclipse.tractusx.edc.tests.MockBdrsClient;
import org.eclipse.tractusx.edc.tests.participant.DataspaceIssuer;
import org.eclipse.tractusx.edc.tests.participant.DcpParticipant;
import org.eclipse.tractusx.edc.tests.runtimes.KeyPool;
import org.eclipse.tractusx.edc.tests.transfer.dcp.harness.StsParticipant;
import org.eclipse.tractusx.edc.tests.transfer.extension.DidServerExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static jakarta.json.Json.createObjectBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.awaitility.pollinterval.FibonacciPollInterval.fibonacci;
import static org.eclipse.edc.jsonld.spi.JsonLdKeywords.TYPE;
import static org.eclipse.edc.spi.constants.CoreConstants.EDC_NAMESPACE;
import static org.eclipse.edc.util.io.Ports.getFreePort;
import static org.eclipse.tractusx.edc.edr.spi.CoreConstants.CX_POLICY_2025_09_NS;
import static org.eclipse.tractusx.edc.tests.TestRuntimeConfiguration.CONSUMER_BPN;
import static org.eclipse.tractusx.edc.tests.TestRuntimeConfiguration.CONSUMER_NAME;
import static org.eclipse.tractusx.edc.tests.TestRuntimeConfiguration.DSP_2025;
import static org.eclipse.tractusx.edc.tests.TestRuntimeConfiguration.DSP_2025_PATH;
import static org.eclipse.tractusx.edc.tests.TestRuntimeConfiguration.PROVIDER_BPN;
import static org.eclipse.tractusx.edc.tests.TestRuntimeConfiguration.PROVIDER_NAME;
import static org.eclipse.tractusx.edc.tests.helpers.CatalogHelperFunctions.getDatasetAssetId;
import static org.eclipse.tractusx.edc.tests.helpers.PolicyHelperFunctions.bpnPolicy;
import static org.eclipse.tractusx.edc.tests.helpers.PolicyHelperFunctions.frameworkPolicy;
import static org.eclipse.tractusx.edc.tests.participant.TractusxParticipantBase.ASYNC_TIMEOUT;
import static org.eclipse.tractusx.edc.tests.transfer.dcp.runtime.Runtimes.dcpRuntime;
import static org.eclipse.tractusx.edc.tests.transfer.dcp.runtime.Runtimes.dcpRuntimeWithMediator;
import static org.eclipse.tractusx.edc.tests.transfer.dcp.runtime.Runtimes.stsRuntime;

/**
 * End-to-end Consumer-Pull-Test fuer den Wind-X-{@code TcpTunnel}-Typ ueber echtes DCP (IdentityHub + STS +
 * Credentials), Docker-frei; Aufbau wie {@link MediatorConsumerPullTest}.
 *
 * <p>Beweist die ganze Kette: Katalog bietet {@code TcpTunnel-PULL} an -&gt; Negotiation -&gt; Transfer STARTED -&gt;
 * EDR mit Tunnel-Endpoint und Token -&gt; WebSocket-Verbindung mit dem EDR-Token tunnelt Bytes zu einem TCP-Echo-Server
 * hinter der Provider-Data-Plane -&gt; Terminate des Transfers schliesst den offenen Tunnel.</p>
 */
@EndToEndTest
public class TcpTunnelConsumerPullTest {

    private static final int MED_PORT = getFreePort();
    private static final int TUNNEL_PORT = getFreePort();

    @RegisterExtension
    private static final DidServerExtension DID_SERVER = new DidServerExtension();

    private static final StsParticipant STS = StsParticipant.Builder.newInstance()
            .id("STS")
            .name("STS")
            .build();

    private static final DataspaceIssuer DATASPACE_ISSUER_PARTICIPANT = new DataspaceIssuer(DID_SERVER.didFor("issuer"));

    private static final DcpParticipant CONSUMER = DcpParticipant.Builder.newInstance()
            .name(CONSUMER_NAME)
            .id(DID_SERVER.didFor(CONSUMER_NAME))
            .stsUri(STS.stsUri())
            .stsClientId(CONSUMER_BPN)
            .credentialServiceUri(STS.credentialServiceUri())
            .trustedIssuer(DATASPACE_ISSUER_PARTICIPANT.didUrl())
            .bpn(CONSUMER_BPN)
            .protocol(DSP_2025, DSP_2025_PATH)
            .build();

    private static final DcpParticipant PROVIDER = DcpParticipant.Builder.newInstance()
            .name(PROVIDER_NAME)
            .id(DID_SERVER.didFor(PROVIDER_NAME))
            .stsUri(STS.stsUri())
            .stsClientId(PROVIDER_BPN)
            .credentialServiceUri(STS.credentialServiceUri())
            .trustedIssuer(DATASPACE_ISSUER_PARTICIPANT.didUrl())
            .bpn(PROVIDER_BPN)
            .protocol(DSP_2025, DSP_2025_PATH)
            .build();

    @RegisterExtension
    private static final RuntimeExtension CONSUMER_RUNTIME = dcpRuntime(CONSUMER.getName(), CONSUMER.getKeyPair(),
            () -> CONSUMER.dcpConfig().merge(bdrsDummyConfig()))
            .registerServiceMock(BdrsClient.class, mockBdrs());

    @RegisterExtension
    private static final RuntimeExtension PROVIDER_RUNTIME = dcpRuntimeWithMediator(PROVIDER.getName(), PROVIDER.getKeyPair(),
            () -> PROVIDER.dcpConfig().merge(windxBdrsConfig()).merge(windxDataPlaneConfig()));

    @RegisterExtension
    private static final RuntimeExtension STS_RUNTIME = stsRuntime(STS.getName(), STS.getKeyPair(),
            () -> STS.stsConfig(CONSUMER, PROVIDER).merge(bdrsDummyConfig()));

    private static EchoServer echoServer;

    @BeforeAll
    static void beforeAll() throws IOException {
        KeyPool.register(DATASPACE_ISSUER_PARTICIPANT.getFullKeyId(), DATASPACE_ISSUER_PARTICIPANT.getKeyPair());
        DID_SERVER.register(CONSUMER_NAME, CONSUMER.getDidDocument());
        DID_SERVER.register(PROVIDER_NAME, PROVIDER.getDidDocument());
        DID_SERVER.register("issuer", DATASPACE_ISSUER_PARTICIPANT.didDocument());

        CONSUMER.configureParticipant(DATASPACE_ISSUER_PARTICIPANT, CONSUMER_RUNTIME, STS_RUNTIME);
        PROVIDER.configureParticipant(DATASPACE_ISSUER_PARTICIPANT, PROVIDER_RUNTIME, STS_RUNTIME);
        CONSUMER.setJsonLd(CONSUMER_RUNTIME.getService(JsonLd.class));

        echoServer = new EchoServer();
    }

    @AfterAll
    static void afterAll() throws IOException {
        echoServer.close();
    }

    @Test
    void tunnelPull_tunnelsBytes_andTerminateClosesTunnel() {
        var assetId = "tcp-tunnel-asset-1";
        createTunnelAsset(assetId, "def-tunnel-1");

        // Katalog: Provider bietet den Tunnel-Typ an
        var dataset = CONSUMER.getCatalogDatasets(PROVIDER).stream()
                .map(JsonValue::asJsonObject)
                .filter(ds -> assetId.equals(getDatasetAssetId(ds)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no dataset offered for asset " + assetId));
        assertThat(dataset.toString()).contains("TcpTunnel-PULL");

        var transferProcessId = CONSUMER.requestAssetFrom(assetId, PROVIDER)
                .withTransferType("TcpTunnel-PULL")
                .withDestination(tunnelDestination())
                .execute();

        await().pollInterval(fibonacci())
                .atMost(ASYNC_TIMEOUT)
                .untilAsserted(() -> assertThat(CONSUMER.getTransferProcessState(transferProcessId))
                        .isEqualTo(TransferProcessStates.STARTED.toString()));

        var edr = new AtomicReference<JsonObject>();
        await().pollInterval(fibonacci())
                .atMost(ASYNC_TIMEOUT)
                .untilAsserted(() -> {
                    edr.set(CONSUMER.edrs().getEdr(transferProcessId));
                    assertThat(edr.get()).isNotNull();
                });

        var endpoint = edr.get().getString("endpoint");
        assertThat(endpoint).isEqualTo("ws://localhost:%d/api/tunnel".formatted(TUNNEL_PORT));

        // Consumer verbindet sich mit dem EDR-Token und spricht "sein" Protokoll -- hier: Echo
        var client = new TunnelClient();
        client.webSocket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Authorization", edr.get().getString("authorization"))
                .buildAsync(URI.create(endpoint), client)
                .orTimeout(10, TimeUnit.SECONDS)
                .join();

        var payload = "hello through the dataspace".getBytes(StandardCharsets.UTF_8);
        client.webSocket.sendBinary(ByteBuffer.wrap(payload), true).join();
        await().atMost(10, TimeUnit.SECONDS).until(() -> client.received().length == payload.length);
        assertThat(client.received()).isEqualTo(payload);

        // Vertrag/Transfer beenden -> Provider-Data-Plane widerruft und schliesst den Tunnel
        CONSUMER.terminateTransfer(transferProcessId);

        var closeCode = client.closeCode.orTimeout(30, TimeUnit.SECONDS).join();
        assertThat(closeCode).isEqualTo(1008);
        await().atMost(10, TimeUnit.SECONDS).until(() -> echoServer.openConnections() == 0);
    }

    @Test
    void tunnelPull_rejectsConnectionWithoutValidToken() {
        var client = new TunnelClient();
        var result = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Authorization", "not-a-token")
                .buildAsync(URI.create("ws://localhost:%d/api/tunnel".formatted(TUNNEL_PORT)), client)
                .handle((ws, error) -> error)
                .orTimeout(10, TimeUnit.SECONDS)
                .join();

        assertThat(result).isNotNull().hasRootCauseMessage("Unexpected HTTP response status code 403");
    }

    private void createTunnelAsset(String assetId, String contractDefinitionId) {
        Map<String, Object> dataAddress = Map.of(
                "type", "TcpTunnel",
                "host", "localhost",
                "port", String.valueOf(echoServer.port())
        );
        PROVIDER.createAsset(assetId, Map.of(), dataAddress);

        var accessPolicyId = PROVIDER.createPolicyDefinition(bpnPolicy(CONSUMER.getBpn()));
        var contractPolicyId = PROVIDER.createPolicyDefinition(
                frameworkPolicy(Map.of(CX_POLICY_2025_09_NS + "Membership", "active"), CX_POLICY_2025_09_NS + "access"));
        PROVIDER.createContractDefinition(assetId, contractDefinitionId, accessPolicyId, contractPolicyId);
    }

    private static MockBdrsClient mockBdrs() {
        return new MockBdrsClient(
                bpn -> bpn.equals(CONSUMER.getBpn()) ? CONSUMER.getDid() : PROVIDER.getDid(),
                did -> did.equals(CONSUMER.getDid()) ? CONSUMER.getBpn() : PROVIDER.getBpn());
    }

    private static Config bdrsDummyConfig() {
        return ConfigFactory.fromMap(Map.of("tx.edc.iam.dcp.bdrs.server.url", "http://localhost:1/bdrs"));
    }

    private static Config windxBdrsConfig() {
        var mappings = "%s=%s;%s=%s".formatted(
                CONSUMER.getBpn(), CONSUMER.getDid(),
                PROVIDER.getBpn(), PROVIDER.getDid());
        return ConfigFactory.fromMap(Map.of("tx.edc.windx.bdrs.mappings", mappings));
    }

    private static Config windxDataPlaneConfig() {
        return ConfigFactory.fromMap(Map.of(
                // der Mediator laeuft in derselben Runtime mit und hat Pflicht-Settings
                "web.http.med.port", String.valueOf(MED_PORT),
                "web.http.med.path", "/api/med",
                "tx.edc.windx.mediator.base.url", "http://localhost:1",
                "edc.dataplane.windx.med.endpoint", "http://localhost:" + MED_PORT + "/api/med",
                "web.http.tunnel.port", String.valueOf(TUNNEL_PORT),
                "web.http.tunnel.path", "/api/tunnel",
                "edc.dataplane.windx.tunnel.endpoint", "ws://localhost:%d/api/tunnel".formatted(TUNNEL_PORT),
                "edc.dataplane.windx.tunnel.watchdog.interval.seconds", "1"
        ));
    }

    private static JsonObject tunnelDestination() {
        return createObjectBuilder()
                .add(TYPE, EDC_NAMESPACE + "DataAddress")
                .add(EDC_NAMESPACE + "type", "TcpTunnel")
                .build();
    }

    private static final class TunnelClient implements WebSocket.Listener {
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        private WebSocket webSocket;

        byte[] received() {
            synchronized (received) {
                return received.toByteArray();
            }
        }

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
     * TCP-Echo-Server als Tunnelziel hinter der Provider-Data-Plane.
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
            serverSocket.close();
        }
    }
}
