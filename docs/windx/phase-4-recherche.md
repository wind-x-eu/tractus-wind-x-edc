# Phase 4 — Recherche & Plan: `mms` (IEC-61850/MMS)

**Datum:** 2026-07-01
**Scope:** BIBA-Package `org.eclipse.edc.sample.extension.mms` (5 Dateien) + `mms.client` (11 Dateien).
**Status:** ✅ **Phase 4a (`Mms-PULL`) UMGESETZT & GRÜN** — s. §7. Scope: nur PULL, Typname `Mms-PULL`.
**Vorgeschichte:** Phase 3 (`mms-light`) übersprungen (Weg A, `docs/phase-3-recherche.md`).

---

## 1. Was `mms` in BIBA ist — der Datenfluss

Eine gemeinsame „Engine" aus dem `mms.client`-Paket, angezapft von zwei EDC-Pfaden:

**Engine (mms.client, nur `slf4j`+`gson` als Fremd-Deps, MMS handgeschrieben):**
1. `MMSClient` (1089 Z.) + `AasIecConnector2` (1049 Z.) — roher IEC-61850/MMS-Client über TCP-Sockets
   (COTP/TPKT/ASN.1 von Hand), `connect/associate/readServerModel/subscribeToReports/readVariable`.
2. `MMSClientManager` (233 Z.) — verbindet, Auto-Reconnect, pollt alle `observedSensors` alle
   `updateInterval` ms → baut `MMSMessage` (reportId/dataSet/dataPoints) → `MMSMessageQueue`.
3. `MMSAggregationCrawler` (353 Z., Runnable) — dequeued, puffert **`AGGREGATION_SIZE=10`** Nachrichten,
   aggregiert zu `MMSAggregation` (Timestamp-Array + Werte-Arrays pro Label + Statistik), Gson→JSON,
   ruft `onAggregation.accept(json)`.
4. Helfer: `MMSMessage`, `MMSMessageQueue`, `MMSQueueListener`, `MMSAggregation`, `DataArrayEntry`,
   `MMSClientConfig`(+`Loader`).

**Zwei EDC-Anzapfungen derselben Engine:**

| Pfad | BIBA-Klasse | Typ | EDC-Erweiterungspunkt | Semantik |
|---|---|---|---|---|
| **PULL** | `MMSDataSource` + `MMSDataSourceFactory` | `Mms-PULL` | **DP-nativ**: `DataSourceFactory.supportedType()="mms"` | `openPartStream()` startet Manager+Crawler, wartet (max **120 s**) auf **eine** Aggregation, gibt sie als **einen** JSON-`Part` zurück, stoppt. **Snapshot.** |
| **PUSH** | `MMSDataFlowController` + `MMSPushTask` | `Mms-PUSH` | **Control-Plane** `dataFlowManager.register(10, …)` | liest `dataDestination.baseUrl` (HttpData), Crawler-Callback **POSTet jede** Aggregation an die URL. **Kontinuierlicher Stream.** |

**Reale Nutzung (`MMS.json`):**
- Asset (Z. 193): dataAddress `type: "mms"` + `mms:serverHost/serverPort/logicalDevice/sensors/updateInterval/reconnectInterval`.
- Z. 425: `transferType: "Mms-PULL"` (kein Ziel — Consumer-Pull).
- Z. 641: `transferType: "Mms-PUSH"` + `dataDestination: { type: "HttpData", baseUrl: "http://localhost:4000" }`.
→ **Beide Pfade sind real exerziert** (anders als der tote `mms-light`).

---

## 2. Abbildung auf tractus-x 0.17 (DP-nativ, da kein `DataFlowManager` mehr — s. Phase-3-Recherche)

### PULL (`Mms-PULL`) — der saubere, fast fertige Pfad ✅
BIBAs `MMSDataSourceFactory`/`MMSDataSource` sind **bereits** exakt die 0.17-DP-Form (`DataSourceFactory`/
`DataSource`) — laut Plan §1 „toter, nie registrierter Pull-Pfad". In 0.17 wird er **durch Registrierung
lebendig**, genau wie unsere `MediatorHttpDataSourceFactory`:
- `pipelineService.registerFactory(new MMSDataSourceFactory())` → `mms` als **allowedSourceType**.
- Damit der Provider `Mms-PULL` (oder `HttpData-PULL`) für ein `mms`-Asset anbietet, gilt dieselbe
  **Drei-Registrierungs-Erkenntnis** aus Weg A (generator + edrServiceRegistry + pipelineFactory) —
  **es sei denn** wir nutzen den Standard-`HttpData-PULL` als Transfer-Typ (dann liefert die Standard-
  Public-API die Daten, und nur `registerFactory` ist nötig; der Quell-Typ `mms` wird über den
  Standard-Public-Endpoint bedient). **Namens-Entscheidung nötig** (s. §4).
- Anders als beim Mediator ist `openPartStream()` **echt** (treibt die MMS-Engine, liefert die JSON-Aggregation).

### PUSH (`Mms-PUSH`) — Semantik-Entscheidung
DP-nativ = Provider-Push-Pipeline **Quelle `mms` → Sink `HttpData`** (Standard-`HttpDataSink` an `baseUrl`).
Registriert man die `mms`-Source-Factory, kann der **Standard-Push-Flow** einen `mms`→`HttpData`-Transfer
fahren — liefert aber **PUSH-once** (eine Aggregation), nicht BIBAs **kontinuierlichen** Stream.
- **PUSH-once:** trivial, sobald die Source-Factory registriert ist.
- **PUSH-continuous** (BIBAs echtes Verhalten): braucht die **non-finite**-Data-Plane
  (`non-finite-provider-push-core`, Plan §5) — deutlich mehr Arbeit.

---

## 3. Testbarkeit
- Der **Consumer** ist bei beiden Pfaden Standard-HTTP (PULL: Public-API-GET; PUSH: HttpData-Sink) — gut.
- Der **Provider** braucht einen echten IEC-61850/MMS-Server (Raw-ASN.1/COTP) für `MMSClient.connect()`.
  Ein Live-DCP-E2E wie Weg A (WireMock) ginge nur mit einem **MMS-Server-Stub** (roher ASN.1) — aufwendig.
- **Pragmatische Teststrategie:** (a) Unit-Tests der Aggregations-/JSON-Pipeline mit synthetischen
  `MMSMessage`s (ohne Socket); (b) Boot-/Registrierungs-Smoke-Test (`mms` als Source-Typ, `Mms-PULL`
  beworben); (c) echtes E2E gegen reale Hardware zurückstellen.

---

## 4. Offene Entscheidungen (User)
1. **Scope zuerst:** nur **`Mms-PULL`** (minimal, existierender DP-Code) — oder gleich **PULL + PUSH-once**
   — oder **PULL + PUSH-continuous** (non-finite, groß)?
2. **Transfer-Typ-Name PULL:** BIBAs **`Mms-PULL`** beibehalten (braucht 3 Registrierungen, eigener
   Generator/EDR) — oder Standard-**`HttpData-PULL`** wiederverwenden (nur `registerFactory`, minimal,
   aber Typname weicht von BIBA/`MMS.json` ab)?
3. **Modulname:** Vorschlag `edc-extensions/windx/windx-mms` (Paket
   `org.eclipse.tractusx.edc.windx.mms` + `…windx.mms.client`), Runtime `edc-runtime-windx` erweitern.
4. **`MMSAggregationCrawler.AGGREGATION_SIZE=10`** + `updateInterval` → ~10 s bis zur ersten Aggregation;
   `MMSDataSource`-Timeout 120 s deckt das. Ggf. konfigurierbar machen.

---

## 5. Vorläufiger Bauplan (nach Scope-Freigabe)
1. Neues Modul `windx-mms`: gesamtes `mms.client`-Paket portieren (Package-Rename → `…windx.mms.client`;
   `System.out`/`java.util.logging` in AasIecConnector2 ggf. auf Monitor/slf4j lassen — nur slf4j+gson als Deps).
2. `MMSDataSource` + `MMSDataSourceFactory` portieren (DP-SPI-Imports identisch zu 0.17).
3. Data-Plane-Extension `WindxMmsDataPlaneExtension`: `@Inject PipelineService` +
   `pipelineService.registerFactory(new MMSDataSourceFactory())` (+ ggf. generator/edrServiceRegistry je
   nach Typname-Entscheidung).
4. `MMSDataFlowController`/`MMSPushTask`/`MMSLight…` **nicht** übernehmen (Control-Plane-Muster, 0.17-tot).
5. `build.gradle.kts`: `libs.edc.spi.dataplane.dataplane` + gson + slf4j; in Runtime + `settings.gradle.kts`.
6. Tests nach §3.

---

## 7. ✅ Umgesetzt — Phase 4a (`Mms-PULL`), 2026-07-01

**Scope-Entscheidung (User):** nur **`Mms-PULL`**, Typname **BIBA-treu `Mms-PULL`** (→ 3 Registrierungen).

**Neues Modul `edc-extensions/windx/windx-mms`:**
- Gesamter `mms.client`-Stack portiert (11 Dateien) + `MMSDataSource` + `MMSDataSourceFactory` (Package
  `…windx.mms[.client]`, Apache-Header, Script `scratchpad/port_mms.py`). BIBAs Control-Plane-Push-Dateien
  (`MMSDataFlowController`, `MMSPushTask`, `MMSDataPlaneExtension`) **nicht** portiert (0.17-tot).
- **`WindxMmsDataPlaneExtension`** (neu, sauber): die **3 Registrierungen** analog Mediator —
  `pipelineService.registerFactory(new MMSDataSourceFactory())` (Source-Typ `Mms`),
  `edrServiceRegistry.register("Mms", (EndpointDataReferenceService) authorizationService)` (→ `Mms-PULL`),
  `generatorService.addGeneratorFunction("Mms", da -> Endpoint.url(publicBaseUrl))`.
  > **Bug-Fix 2026-07-07:** Source-Typ war ursprünglich `mms` (≠ Registry-Key `Mms`); ein echter `Mms-PULL`-Transfer
  > wäre an der EDR-Erzeugung gescheitert. Vereinheitlicht auf **`Mms`** (Source==Key==destType), s.
  > [`phase-mms-light-plan.md`](phase-mms-light-plan.md) §0/§6. Property-Keys bleiben `mms:*`.
- **EDR-Pull-Endpoint = Standard-Public-API** (source-typ-agnostisch, öffnet beim Pull die `Mms`-Source und
  streamt die JSON-Aggregation). URL aus `edc.dataplane.api.public.baseurl`, sonst abgeleitet aus
  `web.http.public.port`/`path` + Hostname (im Boot korrekt `http://localhost:8085/api/public`).

**Build-Hürden gelöst:**
- Checkstyle (Google-Style: `AvoidStarImport`, `FileTabCharacter`, Naming, …) würde den Vendored-Raw-Code
  (Star-Imports, Tabs, 141× System.out) hundertfach flaggen → **`tasks.withType<Checkstyle> { enabled = false }`**
  nur für dieses Modul; die neu geschriebene Extension bleibt sauber.
- `gson` als neuer Fremd-Dep (EDC nutzt Jackson) direkt deklariert; `DEPENDENCIES` leer, keine Dash-Task → ok.
- slf4j-api kommt bereits über den `allprojects`-Block.

**Verifikation (grün):**
- `:edc-extensions:windx:windx-mms:compileJava` ✅; `:edc-controlplane:edc-runtime-windx:shadowJar` ✅.
- Boot: `170 service extensions started` → `Runtime ... ready`; Log
  `Wind-X MMS Data Plane Extension initialized: source type 'Mms', transfer type 'Mms-PULL', pull endpoint
  'http://localhost:8085/api/public'` (nach Bug-Fix; ursprünglich `'mms'`).
- Unit-Test `MMSAggregationCrawlerTest` (10 synthetische Nachrichten → 1 JSON-Aggregation, ohne Socket):
  `SUCCESS (1 passed)` — deckt gson-`LocalDateTime`-Adapter + Aggregations-Logik ab.

**✅ mms-push-once (2026-07-01):** Entscheidung: Push-once, Typname **`HttpData-PUSH`** (semantisch korrekt —
das Ziel *ist* ein HttpData-Sink; BIBAs `Mms-PUSH` wäre künstlich). Verifiziert am 0.17-Code
(`DataplaneSelfRegistrationExtension`): PUSH-Typen kommen aus `pipelineService.supportedSinkTypes()` → der
Standard-HttpData-Sink bewirbt `HttpData-PUSH`, gefiltert nach `supportedSourceTypes()` (enthält `mms`).
→ **kein neuer Produktivcode nötig** (die Phase-4a-Source-Factory genügt). Test:
`MediatorConsumerPullTest.mmsAsset_offersMmsPullAndHttpDataPush` (mms-Asset → DCP-Katalog bietet `Mms-PULL`
**und** `HttpData-PUSH`; kein MMS-Server nötig, da nur Advertising, kein Datenfluss) — `windx-mms` in die
Test-Runtime `runtime-memory-dcp-ih-windx` aufgenommen. Lauf grün: **3 Tests, 3 passed** (GET, PUT, mms-Advertising).

**✅ Hardware-E2E PULL GRÜN (2026-07-07):** BIBA stellte einen echten IEC-61850/MMS-Testserver bereit —
`agilevt-dev.ikap.biba.uni-bremen.de:102` (nativer MMS-Port, kein HTTP), von hier erreichbar. Neuer
getaggter Integrationstest `MmsHardwareConnectivityIT` (Tag `MmsHardwareIT`, nur via
`-DincludeTags="MmsHardwareIT"`) fährt den echten Produktionspfad `MMSDataSource.openPartStream()` gegen den
Server: verbindet, liest Server-Modell, pollt `WPPS`/`LCCS1$ST$Mod$stVal`, aggregiert 10 reale Nachrichten →
JSON. `PASSED`, echte Werte (`Mod$stVal=1`, Timestamps). ⇒ **der portierte ~3800-Z.-ASN.1/COTP-Codec
funktioniert gegen echte Hardware; `Mms-PULL` ist hardware-verifiziert.**

**Scope-Erweiterung (2026-07-07, User):** `mms-light` WIRD gebraucht (nativer MMS-TCP-Tunnel); `Mms-PUSH` muss
KONTINUIERLICH sein (non-finite Data Plane — Modul `non-finite-provider-push-core` existiert bereits im Repo).

**✅ Bug-Fix (2026-07-07): mms-Source-Typ `mms` → `Mms` vereinheitlicht** (s. `docs/phase-mms-light-plan.md` §0/§6).
Grund: die EDR-Registry dispatcht `create()` nach Source-Typ, `revoke()` nach `destinationType` — der alte Mismatch
(Key `Mms` vs. Source `mms`) hätte einen echten `Mms-PULL`-Transfer scheitern lassen (nur Advertising war getestet).
Transfer-Typ bleibt `Mms-PULL`, Property-Keys bleiben `mms:*`. Neuer Beweis-Test `mmsPull_reachesStarted_edrCreated`
(Transfer erreicht `STARTED` + EDR) — DCP-Suite 5 Tests grün.

**Offen / Backlog:** **`mms-light`** (roher MMS/IEC-61850-TCP-Tunnel als DP-Extension) bauen; **`Mms-PUSH`
continuous** (non-finite Data Plane) bauen + gegen Hardware verifizieren.
~~Mediator-PUT-Pfad~~ ✅ **erledigt 2026-07-01** (s. `docs/phase-1-ergebnisse.md` / Memory): `proxyPut` im
`MediatorController` (streamt Consumer-Body als POST an `<mediator>/createVirtualDataSource`, Properties als
`X-*`-Header, zentrale `mediatorBaseUrl`); HttpClient auf HTTP/1.1 gezwungen (Default-HTTP/2 scheiterte am
gestreamten Upload: „GOAWAY received"). E2E-Test `MediatorConsumerPullTest.mediatorPut_whenContractPolicyFulfilled`
grün (2 Tests, 2 passed).

## 6. Quellen (verifiziert)
- BIBA: `…/extension/mms/{MMSDataFlowController,MMSPushTask,MMSDataSource,MMSDataSourceFactory,MMSDataPlaneExtension}.java`,
  `mms/client/*` (11 Dateien), `MMS.json` (Z. 193/425/641).
- Phase-1/2-Muster: `windx-mediator-proxy` (Drei-Registrierungs-Erkenntnis), `docs/phase-identitaet-weg-a.md`.
- 0.17 kein `DataFlowManager`: `docs/phase-3-recherche.md`.
</content>
