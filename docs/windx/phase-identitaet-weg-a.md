# Identität — Weg A: In-Memory-DCP-E2E-Test (Docker-frei)

**Datum:** 2026-06-30
**Scope:** Echter End-to-End-Transfer (Consumer → Provider) mit DCP-Identität, um Phase 1+2
(`windx-mediator-proxy`, Typ `MediatorHttpData-PULL`) endlich **real** zu verifizieren.
**Status:** ✅ **UMGESETZT & GRÜN** (2026-06-30). `MediatorConsumerPullTest` läuft end-to-end:
Verhandlung → Transfer `STARTED` → EDR → Consumer-Pull (GET) über den med-Controller → Mediator-Stub
liefert die Antwort. `Results: SUCCESS (1 tests, 1 passed)`.

### Ergebnis & die entscheidenden Erkenntnisse (über den ursprünglichen Plan hinaus)

Ein eigener **Consumer-Pull-Typ** braucht in 0.17 **drei** Registrierungen in der Data-Plane-Extension —
die ersten zwei reichten NICHT:
1. `generatorService.addGeneratorFunction("MediatorHttpData", …)` → baut die EDR-Endpoint-URL.
2. `edrServiceRegistry.register("MediatorHttpData", authService)` → bewirbt `MediatorHttpData-**PULL**`
   als `allowedTransferType`.
3. **NEU/nötig:** `pipelineService.registerFactory(new MediatorHttpDataSourceFactory())` mit
   `supportedType()="MediatorHttpData"` → bewirbt `MediatorHttpData` als **`allowedSourceType`**.
   Ohne (3) verwirft `LegacyDataPlaneSignalingFlowController.transferTypesFor(asset)` den Typ und der
   Provider antwortet `TransferType MediatorHttpData-PULL is not supported` (400) → `TERMINATED`.
   Die Factory wird beim Pull **nie geöffnet** (unser med-Controller holt die Daten selbst); sie bewirbt
   nur den Source-Typ. Datei: `windx-mediator-proxy/.../MediatorHttpDataSourceFactory.java`.

Test-Harness-Lehren:
- **Docker-frei** ist Pflicht (kein Docker installiert): `MockBdrsClient` per `registerServiceMock(BdrsClient.class, …)`
  auf Consumer+Provider + **Dummy** `tx.edc.iam.dcp.bdrs.server.url` (das `@Setting` ist required). Lambdas
  liefern echte DID↔BPN-Mappings, nicht `s->s`.
- Eigene Extension **nicht** per `registerSystemExtension` aus dem Test-Classpath laden (sonst liegt die
  Data-Plane-SPI doppelt vor → Cross-Classloader-DI bricht: „is required by extension … no provider").
  Stattdessen **dediziertes Runtime-Modul** `:edc-tests:runtime:dcp:runtime-memory-dcp-ih-windx`
  (dcp-ih + windx-mediator-proxy) → ServiceLoader lädt im Runtime-Classloader.
- WireMock-Mediator-Stub auf **festem** Port (`getFreePort()` vorab), sonst ist der Port beim Provider-Boot
  noch nicht bekannt (`wireMockServer is null`).

### Geänderte/neue Dateien
- NEU `edc-tests/runtime/dcp/runtime-memory-dcp-ih-windx/build.gradle.kts` (+ `settings.gradle.kts`-Include).
- NEU `edc-tests/e2e/dcp-tests/.../transfer/MediatorConsumerPullTest.java`.
- `Runtimes.java` (dcp-tests): neue Methode `dcpRuntimeWithMediator(...)`.
- `dcp-tests/build.gradle.kts`: `testCompileOnly` auf das neue Runtime-Modul.
- NEU `windx-mediator-proxy/.../MediatorHttpDataSourceFactory.java` + Registrierung in
  `MediatorDataPlaneExtension` (`@Inject PipelineService`).

Lauf: `./gradlew :edc-tests:e2e:dcp-tests:test --tests "*MediatorConsumerPullTest" -DincludeTags="EndToEndTest" -PverboseTest=true`

---

**(Ursprünglicher Plan, zur Nachvollziehbarkeit belassen:)** Recherche abgeschlossen, Bauplan steht.

---

## 0. Entscheidung (Kontext)

- **Weg A gewählt**, Wege „echter bootbarer IdentityHub" und „iam-mock behalten" verworfen.
- Das separate Repo `fma-identityhub` (Eclipse-IdentityHub-Quelle, 0.18.0-SNAPSHOT) wird **ignoriert** —
  tractus-x bündelt die nötigen IdentityHub-Module bereits selbst (`runtime-memory-dcp-ih`).
- Ziel ist ein **JUnit-Test-Harness** (kein bootbares Deployment), nach Vorbild der vorhandenen
  tractus-x-DCP-Pull-Tests — und **Docker-frei** über `MockBdrsClient`.

---

## 1. Topologie

Drei In-Process-Runtimes, vom Test-Harness verdrahtet (wir bauen sie nicht von Hand):

| Rolle | Runtime-Modul | Besonderheit |
|---|---|---|
| **Consumer** | `:edc-tests:runtime:dcp:runtime-memory-dcp-ih` | Standard |
| **Provider** | `:edc-tests:runtime:dcp:runtime-memory-dcp-ih` | **+ unsere `MediatorDataPlaneExtension`** |
| **STS** | vom Fixture bereitgestellt (`stsUri`) | über `dcpConfig()` verdrahtet |

- Das Runtime-Modul `runtime-memory-dcp-ih` enthält **Control Plane** (`edc-controlplane-base`)
  **und Data Plane** (`edc-dataplane-base`) → unsere Data-Plane-Extension findet ihre Inject-Targets
  (`PublicEndpointGeneratorService`, `DataPlaneAuthorizationService`, `EndpointDataReferenceServiceRegistry`,
  `PortMappingRegistry`, `WebService`, `Hostname`).
- DID-Auflösung läuft über `registerServiceMock(DidPublicKeyResolver.class, …)` (schon in `genericRuntime`).
- **BPN↔DID** ohne Docker: `MockBdrsClient` statt `BdrsServerExtension` (siehe §3).

---

## 2. Vorlage-Dateien (kopieren/anpassen)

| Zweck | Vorlage | Aktion |
|---|---|---|
| Test-Klasse | `edc-tests/e2e/dcp-tests/.../transfer/StsConsumerPullTest.java` | → `MediatorConsumerPullTest.java` kopieren, anpassen |
| Runtime-Helper | `edc-tests/e2e/dcp-tests/.../transfer/dcp/runtime/Runtimes.java` | neue Methode `dcpRuntimeWithMediator(...)` |
| Transfer-Ablauf (geerbt) | `edc-tests/e2e-fixtures/.../transfer/ConsumerPullBaseTest.java` | nur lesen — Asset/Transfer-Typ überschreiben |
| Participant-Config (geerbt) | `edc-tests/e2e-fixtures/.../participant/TractusxDcpParticipantBase.java` | nur lesen (`dcpConfig()`) |
| Credentials (geerbt) | `edc-tests/e2e-fixtures/.../participant/DataspaceIssuer.java` | nur lesen (stellt Membership/BPN/… aus) |

---

## 3. Die drei konkreten Eingriffe

### (a) Unsere Extension in die Provider-Runtime laden
Per `registerSystemExtension` — **keine Gradle-Änderung am Runtime-Modul nötig**:

```java
.registerSystemExtension(ServiceExtension.class,
        new org.eclipse.tractusx.edc.windx.mediator.MediatorDataPlaneExtension());
```

Gekapselt in einer neuen Runtimes-Methode:

```java
// Runtimes.java
static RuntimeExtension dcpRuntimeWithMediator(String name, KeyPair keyPair, Supplier<Config> cfg) {
    return genericRuntime(name, ":edc-tests:runtime:dcp:runtime-memory-dcp-ih", keyPair, cfg)
        .registerSystemExtension(ServiceExtension.class,
                new VaultSeedExtension(Map.of("client_secret_alias", "client_secret")))
        .registerSystemExtension(ServiceExtension.class, new MediatorDataPlaneExtension());
}
```

> Hinweis: Die Test-`build.gradle.kts` der `dcp-tests` braucht eine `testImplementation`-Dependency auf
> `:edc-extensions:windx:windx-mediator-proxy`, damit die Extension-Klasse im Test-Classpath liegt.

### (b) Docker-frei: `MockBdrsClient` statt `BdrsServerExtension`
In der Vorlage mergt der Provider `BDRS_SERVER_EXTENSION.getConfig()` (Docker-Container). Stattdessen:

- `@RegisterExtension … BDRS_SERVER_EXTENSION` **entfernen** und den `.merge(BDRS_SERVER_EXTENSION.getConfig())` weglassen.
- Auf **beiden** Runtimes registrieren:

```java
.registerServiceMock(BdrsClient.class,
        new MockBdrsClient(bpn -> /* did */ ..., did -> /* bpn */ ...))
```

Die Lambdas müssen die **echten** Participant-Mappings liefern (vgl. `TransferWithTokenRefreshTest`:
`(c) -> CONSUMER.getDid()` / `(c) -> CONSUMER.getBpn()`), nicht Identity (`s -> s`), weil bei
tractus-x-Teilnehmern DID ≠ BPN. Die Dummy-BDRS-URL aus `dcpConfig()` bleibt ungenutzt.

### (c) Eigener Transfer-Typ + Asset
Im (überschriebenen) Transfer-Schritt:

```java
Map<String, Object> dataAddress = Map.of(
        "baseUrl", privateBackendUrl,
        "type", "MediatorHttpData",          // statt "HttpData"
        "contentType", "application/json");
...
consumer().requestAssetFrom(assetId, provider())
        .withTransferType("MediatorHttpData-PULL")   // statt "HttpData-PULL"
        .execute();
```

---

## 4. Offene Punkte (vor/bei der Implementierung klären)

1. **Mediator-Backend-Stub.** Unser `MediatorController` POSTet beim Pull an
   `tx.edc.windx.mediator.base.url + /forwardRealDataSource` und erwartet 200. Im Test brauchen wir dafür
   einen **HTTP-Stub** (WireMock/MockWebServer), und `tx.edc.windx.mediator.base.url` muss per Test-Config
   auf dessen URL zeigen. Ohne Stub → `MediatorController` liefert 502, Pull schlägt fehl.
2. **`med`-Kontext-Port.** Unsere Extension öffnet einen eigenen Web-Kontext (`web.http.med.port`, Default
   8087). In Multi-Runtime-Tests werden Ports sonst dynamisch vergeben → **fixer Port 8087 kann kollidieren**
   oder belegt sein. Per Test-Config einen freien/dynamischen Port setzen (`web.http.med.port=…`,
   `web.http.med.path=…`) und ggf. den EDR-Public-Endpoint (`edc.dataplane.windx.med.endpoint`) passend.
3. **EDR-Pull-Pfad.** `consumer().data().pullData(edr, …)` macht einen GET gegen den `med`-Endpoint des
   Providers. Prüfen, dass die EDR-Adresse aus unserem `addGeneratorFunction(MEDIATOR_HTTP_DATA, …)` auf den
   testweise erreichbaren `med`-Port zeigt (Hostname/Port im Test).
4. **Was beweist der Test?** Erfolgskriterium: Negotiation ok → Transfer `STARTED` → EDR vorhanden →
   `pullData` liefert die (gestubbte) Mediator-Antwort. Damit ist die §3-Registrierungszeile aus Phase 2
   (EDR-Service-Registry → `MediatorHttpData-PULL` selektierbar) **end-to-end** belegt.

---

## 5. Reihenfolge der Umsetzung

1. `testImplementation(project(":edc-extensions:windx:windx-mediator-proxy"))` in `dcp-tests/build.gradle.kts`.
2. `Runtimes.dcpRuntimeWithMediator(...)` hinzufügen.
3. `MediatorConsumerPullTest` von `StsConsumerPullTest` ableiten: Provider-Runtime = `dcpRuntimeWithMediator`,
   `MockBdrsClient` auf beiden, Asset-`type`/`transferType` = `MediatorHttpData(-PULL)`.
4. Mediator-Backend-Stub + `tx.edc.windx.mediator.base.url` + `med`-Port in die Test-Config.
5. `./gradlew :edc-tests:e2e:dcp-tests:test --tests MediatorConsumerPullTest` (oder das `InMemory`-Nested).

---

## 6. Quellen (verifiziert im Workspace)
- `StsConsumerPullTest.java`, `DivConsumerPullTest.java`, `AbstractDcpConsumerPullTest` — `edc-tests/e2e/dcp-tests/`
- `ConsumerPullBaseTest.java` (Z. 72–107: dataAddress/transferType/EDR/pullData) — `edc-tests/e2e-fixtures/`
- `Runtimes.java` (`dcpRuntime`/`genericRuntime`/`EmbeddedRuntime`/`registerSystemExtension`) — `dcp-tests/.../runtime/`
- `runtime-memory-dcp-ih/build.gradle.kts` (CP `edc-controlplane-base` + DP `edc-dataplane-base` + `dcp-extensions`)
- `MockBdrsClient.java` — `edc-tests/e2e-fixtures/.../tests/` (Docker-freier `BdrsClient`)
- `DcpPresentationFlowTest`, `TransferWithTokenRefreshTest` (Beispiele `registerServiceMock(BdrsClient.class, …)`)
- `TractusxDcpParticipantBase.dcpConfig()`, `DcpParticipant`, `DataspaceIssuer.issueCredentials()` — `e2e-fixtures/`
- unsere `MediatorDataPlaneExtension.java` (Inject-Targets, `med`-Kontext, `MEDIATOR_HTTP_DATA`) — `windx-mediator-proxy/`
