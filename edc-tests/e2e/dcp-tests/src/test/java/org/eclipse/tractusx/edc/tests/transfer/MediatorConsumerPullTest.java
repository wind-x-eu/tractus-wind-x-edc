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

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.restassured.RestAssured.given;
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
 * End-to-end Consumer-Pull-Test fuer den Wind-X-{@code MediatorHttpData}-Typ ueber echtes DCP
 * (IdentityHub + STS + Credentials), aber <b>Docker-frei</b>: BDRS wird durch {@link MockBdrsClient}
 * ersetzt (siehe {@code DcpPresentationFlowTest}), die BDRS-URL bleibt ein Dummy.
 *
 * <p>Aufgebaut nach Vorbild von {@code StsConsumerPullTest}. Der Provider laedt zusaetzlich unsere
 * {@link MediatorDataPlaneExtension} (per {@code registerSystemExtension}); ein WireMock stubt den
 * Semantic Mediator ({@code /forwardRealDataSource}). Der Test beweist, dass ein
 * {@code MediatorHttpData-PULL}-Transfer ueber den Standard-DPS-Controller laeuft und der EDR-Pull
 * den Mediator anspricht.</p>
 */
@EndToEndTest
public class MediatorConsumerPullTest {

    private static final int MED_PORT = getFreePort();
    // fester Port fuer den Mediator-Stub: so haengt mediatorConfig() nicht am Startzustand des WireMock
    private static final int MEDIATOR_BACKEND_PORT = getFreePort();

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

    // Stub fuer den Semantic Mediator (/forwardRealDataSource). Muss vor der Provider-Runtime starten.
    @RegisterExtension
    protected static final WireMockExtension MEDIATOR_BACKEND = WireMockExtension.newInstance()
            .options(wireMockConfig().bindAddress("localhost").port(MEDIATOR_BACKEND_PORT))
            .build();

    @RegisterExtension
    private static final RuntimeExtension CONSUMER_RUNTIME = dcpRuntime(CONSUMER.getName(), CONSUMER.getKeyPair(),
            () -> CONSUMER.dcpConfig().merge(bdrsDummyConfig()))
            .registerServiceMock(BdrsClient.class, mockBdrs());

    @RegisterExtension
    private static final RuntimeExtension PROVIDER_RUNTIME = dcpRuntimeWithMediator(PROVIDER.getName(), PROVIDER.getKeyPair(),
            () -> PROVIDER.dcpConfig().merge(bdrsDummyConfig()).merge(mediatorConfig()))
            .registerServiceMock(BdrsClient.class, mockBdrs());

    @RegisterExtension
    private static final RuntimeExtension STS_RUNTIME = stsRuntime(STS.getName(), STS.getKeyPair(),
            () -> STS.stsConfig(CONSUMER, PROVIDER).merge(bdrsDummyConfig()));

    @BeforeAll
    static void beforeAll() {
        KeyPool.register(DATASPACE_ISSUER_PARTICIPANT.getFullKeyId(), DATASPACE_ISSUER_PARTICIPANT.getKeyPair());
        DID_SERVER.register(CONSUMER_NAME, CONSUMER.getDidDocument());
        DID_SERVER.register(PROVIDER_NAME, PROVIDER.getDidDocument());
        DID_SERVER.register("issuer", DATASPACE_ISSUER_PARTICIPANT.didDocument());

        CONSUMER.configureParticipant(DATASPACE_ISSUER_PARTICIPANT, CONSUMER_RUNTIME, STS_RUNTIME);
        PROVIDER.configureParticipant(DATASPACE_ISSUER_PARTICIPANT, PROVIDER_RUNTIME, STS_RUNTIME);
        CONSUMER.setJsonLd(CONSUMER_RUNTIME.getService(JsonLd.class));
    }

    @Test
    void mediatorPull_whenContractPolicyFulfilled() {
        var assetId = "mediator-asset-1";

        Map<String, Object> dataAddress = Map.of(
                "type", "MediatorHttpData",
                "configurationID", "27",
                "responseType", "application/json",
                "referenceModell", "default-referenceModell"
        );

        PROVIDER.createAsset(assetId, Map.of(), dataAddress);

        var accessPolicyId = PROVIDER.createPolicyDefinition(bpnPolicy(CONSUMER.getBpn()));
        var contractPolicyId = PROVIDER.createPolicyDefinition(
                frameworkPolicy(Map.of(CX_POLICY_2025_09_NS + "Membership", "active"), CX_POLICY_2025_09_NS + "access"));
        PROVIDER.createContractDefinition(assetId, "def-1", accessPolicyId, contractPolicyId);

        var transferProcessId = CONSUMER.requestAssetFrom(assetId, PROVIDER)
                .withTransferType("MediatorHttpData-PULL")
                .withDestination(mediatorDestination())
                .execute();

        // wait until transfer process is STARTED on the consumer side
        await().pollInterval(fibonacci())
                .atMost(ASYNC_TIMEOUT)
                .untilAsserted(() -> {
                    var tpState = CONSUMER.getTransferProcessState(transferProcessId);
                    assertThat(tpState).isNotNull().isEqualTo(TransferProcessStates.STARTED.toString());
                });

        // stub the Semantic Mediator backend that our med controller forwards the pull to
        MEDIATOR_BACKEND.stubFor(post(urlPathEqualTo("/forwardRealDataSource"))
                .willReturn(ok("{\"result\":\"mediated\"}")));

        var edr = new AtomicReference<JsonObject>();
        await().pollInterval(fibonacci())
                .atMost(ASYNC_TIMEOUT)
                .untilAsserted(() -> {
                    edr.set(CONSUMER.edrs().getEdr(transferProcessId));
                    assertThat(edr.get()).isNotNull();
                });

        // pull data: Consumer -> Prov-med-controller -> Semantic Mediator stub
        var data = CONSUMER.data().pullData(edr.get(), Map.of());
        assertThat(data).contains("mediated");

        MEDIATOR_BACKEND.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlPathEqualTo("/forwardRealDataSource")));
    }

    @Test
    void mediatorPut_whenContractPolicyFulfilled() {
        var assetId = "mediator-asset-put-1";

        Map<String, Object> dataAddress = Map.of(
                "type", "MediatorHttpData",
                "configurationID", "27",
                "responseType", "application/json",
                "referenceModell", "default-referenceModell"
        );

        PROVIDER.createAsset(assetId, Map.of(), dataAddress);

        var accessPolicyId = PROVIDER.createPolicyDefinition(bpnPolicy(CONSUMER.getBpn()));
        var contractPolicyId = PROVIDER.createPolicyDefinition(
                frameworkPolicy(Map.of(CX_POLICY_2025_09_NS + "Membership", "active"), CX_POLICY_2025_09_NS + "access"));
        PROVIDER.createContractDefinition(assetId, "def-put-1", accessPolicyId, contractPolicyId);

        var transferProcessId = CONSUMER.requestAssetFrom(assetId, PROVIDER)
                .withTransferType("MediatorHttpData-PULL")
                .withDestination(mediatorDestination())
                .execute();

        await().pollInterval(fibonacci())
                .atMost(ASYNC_TIMEOUT)
                .untilAsserted(() -> {
                    var tpState = CONSUMER.getTransferProcessState(transferProcessId);
                    assertThat(tpState).isNotNull().isEqualTo(TransferProcessStates.STARTED.toString());
                });

        // der med-Controller leitet den PUT als POST an /createVirtualDataSource weiter
        MEDIATOR_BACKEND.stubFor(post(urlPathEqualTo("/createVirtualDataSource"))
                .willReturn(ok("{\"virtual\":\"created\"}")));

        var edr = new AtomicReference<JsonObject>();
        await().pollInterval(fibonacci())
                .atMost(ASYNC_TIMEOUT)
                .untilAsserted(() -> {
                    edr.set(CONSUMER.edrs().getEdr(transferProcessId));
                    assertThat(edr.get()).isNotNull();
                });

        // PUT mit Body an das med-Endpoint: Consumer -> Prov-med-controller -> Semantic Mediator stub
        var payload = "<sensor>put-payload</sensor>";
        var response = given()
                .baseUri(edr.get().getString("endpoint"))
                .header("Authorization", edr.get().getString("authorization"))
                .contentType("application/xml")
                .body(payload)
                .put()
                .then()
                .statusCode(200)
                .extract().body().asString();

        assertThat(response).contains("created");

        // der Mediator hat den gestreamten Body und die Properties als X-Header erhalten
        MEDIATOR_BACKEND.verify(1, postRequestedFor(urlPathEqualTo("/createVirtualDataSource"))
                .withHeader("X-Configuration-ID", equalTo("27"))
                .withHeader("X-Response-Type", equalTo("application/json"))
                .withHeader("X-Reference-Modell", equalTo("default-referenceModell"))
                .withRequestBody(equalTo(payload)));
    }

    @Test
    void mmsAsset_offersMmsPullAndHttpDataPush() {
        var assetId = "mms-asset-1";

        Map<String, Object> dataAddress = Map.of(
                "type", "Mms",
                "mms:serverHost", "localhost",
                "mms:serverPort", "102",
                "mms:logicalDevice", "WPPS",
                "mms:sensors", "LCCS1$ST$Mod$stVal"
        );

        PROVIDER.createAsset(assetId, Map.of(), dataAddress);

        var accessPolicyId = PROVIDER.createPolicyDefinition(bpnPolicy(CONSUMER.getBpn()));
        var contractPolicyId = PROVIDER.createPolicyDefinition(
                frameworkPolicy(Map.of(CX_POLICY_2025_09_NS + "Membership", "active"), CX_POLICY_2025_09_NS + "access"));
        PROVIDER.createContractDefinition(assetId, "def-mms-1", accessPolicyId, contractPolicyId);

        // Advertising-Test (kein MMS-Server noetig, da kein Datenfluss): der Provider muss fuer das
        // mms-Asset 'Mms-PULL' (Phase 4a, EDR-Registry) UND 'HttpData-PUSH' (push-once ueber den
        // Standard-HttpData-Sink + registrierte mms-Source) anbieten.
        var datasets = CONSUMER.getCatalogDatasets(PROVIDER);
        var mmsDataset = datasets.stream()
                .map(JsonValue::asJsonObject)
                .filter(ds -> assetId.equals(getDatasetAssetId(ds)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no dataset offered for asset " + assetId));

        var offered = mmsDataset.toString();
        assertThat(offered).contains("Mms-PULL");
        assertThat(offered).contains("HttpData-PUSH");
    }

    @Test
    void mmsPull_reachesStarted_edrCreated() {
        var assetId = "mms-pull-asset-1";

        Map<String, Object> dataAddress = Map.of(
                "type", "Mms",
                "mms:serverHost", "localhost",
                "mms:serverPort", "102",
                "mms:logicalDevice", "WPPS",
                "mms:sensors", "LCCS1$ST$Mod$stVal"
        );

        PROVIDER.createAsset(assetId, Map.of(), dataAddress);

        var accessPolicyId = PROVIDER.createPolicyDefinition(bpnPolicy(CONSUMER.getBpn()));
        var contractPolicyId = PROVIDER.createPolicyDefinition(
                frameworkPolicy(Map.of(CX_POLICY_2025_09_NS + "Membership", "active"), CX_POLICY_2025_09_NS + "access"));
        PROVIDER.createContractDefinition(assetId, "def-mms-pull-1", accessPolicyId, contractPolicyId);

        var transferProcessId = CONSUMER.requestAssetFrom(assetId, PROVIDER)
                .withTransferType("Mms-PULL")
                .withDestination(mmsDestination())
                .execute();

        // STARTED wird nur erreicht, wenn die Provider-Data-Plane die EDR erzeugt. Das gelingt erst durch den
        // Bug-Fix (Source-Typ == EDR-Registry-Key == 'Mms'); vorher scheiterte create() an 'No EDR service with
        // type mms found' → TERMINATED. Kein MMS-Server noetig: die mms-EDR liefert nur die statische Public-API-URL.
        await().pollInterval(fibonacci())
                .atMost(ASYNC_TIMEOUT)
                .untilAsserted(() -> {
                    var tpState = CONSUMER.getTransferProcessState(transferProcessId);
                    assertThat(tpState).isNotNull().isEqualTo(TransferProcessStates.STARTED.toString());
                });

        var edr = new AtomicReference<JsonObject>();
        await().pollInterval(fibonacci())
                .atMost(ASYNC_TIMEOUT)
                .untilAsserted(() -> {
                    edr.set(CONSUMER.edrs().getEdr(transferProcessId));
                    assertThat(edr.get()).isNotNull();
                });

        // Die EDR verweist auf die Standard-Public-API (source-typ-agnostischer Pull-Controller) — eine gueltige
        // absolute HTTP-URL. Dass STARTED erreicht + eine EDR erzeugt wurde, beweist bereits den create()-Dispatch-Fix.
        assertThat(edr.get().getString("endpoint")).startsWith("http").contains("localhost");
    }

    @Test
    void mmsLightAsset_offersMmsLightPull() {
        var assetId = "mms-light-asset-1";

        Map<String, Object> dataAddress = Map.of(
                "type", "MmsLight",
                "mms:serverHost", "localhost",
                "mms:serverPort", "102",
                "mms:logicalDevice", "WPPS",
                "mms:sensors", "LCCS1$ST$Mod$stVal"
        );

        PROVIDER.createAsset(assetId, Map.of(), dataAddress);

        var accessPolicyId = PROVIDER.createPolicyDefinition(bpnPolicy(CONSUMER.getBpn()));
        var contractPolicyId = PROVIDER.createPolicyDefinition(
                frameworkPolicy(Map.of(CX_POLICY_2025_09_NS + "Membership", "active"), CX_POLICY_2025_09_NS + "access"));
        PROVIDER.createContractDefinition(assetId, "def-mms-light-1", accessPolicyId, contractPolicyId);

        // Advertising-Test (kein MMS-Server noetig): der Provider muss fuer das MmsLight-Asset den rohen
        // TCP-Tunnel-Typ 'MmsLight-PULL' anbieten (eigener EDR-Service + registrierte MmsLight-Source).
        var datasets = CONSUMER.getCatalogDatasets(PROVIDER);
        var dataset = datasets.stream()
                .map(JsonValue::asJsonObject)
                .filter(ds -> assetId.equals(getDatasetAssetId(ds)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no dataset offered for asset " + assetId));

        assertThat(dataset.toString()).contains("MmsLight-PULL");
    }

    private static MockBdrsClient mockBdrs() {
        return new MockBdrsClient(
                bpn -> bpn.equals(CONSUMER.getBpn()) ? CONSUMER.getDid() : PROVIDER.getDid(),
                did -> did.equals(CONSUMER.getDid()) ? CONSUMER.getBpn() : PROVIDER.getBpn());
    }

    private static Config bdrsDummyConfig() {
        // BdrsClientExtension hat ein Pflicht-Setting; der MockBdrsClient ueberschreibt den @Provider,
        // die URL wird nie kontaktiert (vgl. DcpPresentationFlowTest).
        return ConfigFactory.fromMap(Map.of("tx.edc.iam.dcp.bdrs.server.url", "http://localhost:1/bdrs"));
    }

    private static Config mediatorConfig() {
        return ConfigFactory.fromMap(Map.of(
                "web.http.med.port", String.valueOf(MED_PORT),
                "web.http.med.path", "/api/med",
                "tx.edc.windx.mediator.base.url", "http://localhost:" + MEDIATOR_BACKEND_PORT,
                "edc.dataplane.windx.med.endpoint", "http://localhost:" + MED_PORT + "/api/med"
        ));
    }

    private static JsonObject mediatorDestination() {
        return createObjectBuilder()
                .add(TYPE, EDC_NAMESPACE + "DataAddress")
                .add(EDC_NAMESPACE + "type", "MediatorHttpData")
                .build();
    }

    private static JsonObject mmsDestination() {
        return createObjectBuilder()
                .add(TYPE, EDC_NAMESPACE + "DataAddress")
                .add(EDC_NAMESPACE + "type", "Mms")
                .build();
    }
}
