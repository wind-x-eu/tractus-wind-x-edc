# Integrationsplan: BIBA-Connector → tractus-x-edc

> Ziel: Alle Funktionalitäten des BIBA-Connectors (`MeinConnector`, EDC 0.14.1) schrittweise
> in den tractus-x-edc-Stack (EDC 0.17.0) überführen.
> Stand: 2026-06-23 (Roadmap). Basis: Quellanalyse beider Repos + EDC-Release-Recherche 0.14→0.17.

> **⚠️ Aktualitäts-Hinweis (2026-07-07):** Dieses Dokument ist die **ursprüngliche Roadmap**. Inzwischen sind
> **Phasen 0–4 + Identität Weg A umgesetzt & grün** — teils über **andere** Mechanismen als hier geplant
> (0.17 hat **keinen `DataFlowManager`** mehr → alle BIBA-Control-Plane-`DataFlowController` fallengelassen,
> Logik in die **Data Plane** verlagert). Verbindlicher Ist-Stand: die `phase-*-ergebnisse.md` /
> `phase-*-plan.md` + das Projekt-Memory. Kurzüberblick am jeweiligen Phasenkopf unten.
>
> **Ist-Stand kompakt:** Phase 0 (`windx-participant-log`) ✅ · Phase 1 (`windx-mediator-proxy`, GET **+ PUT**) ✅ ·
> Phase 2 (Control-Plane-Controller **verworfen**, EDR-Registry-Ansatz) ✅ · Identität **Weg A** (echtes DCP,
> Docker-frei) ✅ · Phase 3 `mms-light` (roher MMS-TCP-Tunnel als DP-`EndpointDataReferenceService`) ✅ ·
> Phase 4 `mms` PULL (Source-Typ **`Mms`**) + push-once + **continuous** (non-finite) ✅ · echter Testserver
> `agilevt-dev.ikap.biba.uni-bremen.de:102` verfügbar, Hardware-E2E grün. **Offen:** Option-B-Sink (optional),
> volle DCP-Transfer-Tests (T3), Phase-5-Hardening.

---

## 0. Ausgangslage

| | BIBA `MeinConnector` | tractus-x-edc |
|---|---|---|
| Basis | `org.eclipse.edc` **0.14.1** (vanilla Upstream) | `org.eclipse.tractusx.edc` 0.13.x auf Upstream **0.17.0** |
| Runtime | **eine kombinierte** CP+DP-Runtime (`BaseRuntime`, Fat-JAR) | getrennte CP-/DP-Runtimes; `edc-runtime-memory` = kombiniert |
| Identität | `iam-mock` | echtes SSI/DCP (IdentityHub, BDRS, DID) |
| Build | Gradle 8.x + Shadow, Single-Module | Gradle 9.5.1 + `org.eclipse.edc.edc-build` 1.5.2, 113 Module |

**Zwei getrennte Problemdimensionen:**
- **A – Versionssprung 0.14.1 → 0.17.0** (API-/Config-Deltas).
- **B – Repackaging** der Extensions in tractus-x-Module + Runtime-Assembly.

---

## 1. Inventar der zu migrierenden BIBA-Extensions

Registriert in `META-INF/services/org.eclipse.edc.spi.system.ServiceExtension`:

| Extension | Typ | EDC-Erweiterungspunkt | Daten-/Transfer-Typ | Externe Integration |
|---|---|---|---|---|
| **`ParticipantIdLog`** | Logging | `ServiceExtension` (nur `getSetting`/`getMonitor`) | – | – |
| **`proxy.CustomProxyDataPlaneExtension`** | Data Plane | `PortMappingRegistry`, `WebService.registerResource`, `PublicEndpointGeneratorService.addGeneratorFunction("HttpData")`, `@Inject DataPlaneAuthorizationService` | Public-API `PUBLIC` (Port 8185, `/api/public`) | HTTP-Pull-Proxy → `baseUrl` |
| **`proxy.MediatorDataPlaneExtension`** | Data Plane | wie oben, eigener Kontext `med` | `med` (Port 8185, `/api/med`), GET+PUT | Semantic Mediator (HTTP) |
| **`mediator.MediatorExtension`** | Control Plane | `@Inject DataFlowManager` → `register(10, MediatorFlowController)` | `med` / `Mediator-PULL` → `Med-PULL`, liefert EDR-DataAddress | Semantic Mediator `POST /forwardRealDataSource` |
| **`mms.MMSDataPlaneExtension`** | Control Plane | `register(10, MMSDataFlowController)` | `mms` → `Mms-PUSH` | IEC-61850/MMS (TCP:102) → HTTP-Push an `baseUrl` |
| **`mmsLight.MMSLightExtension`** | Control Plane | `register(10, MMSLightDataFlowController)` | `mms-light` → `MmsLight-PULL` (Rückgabe `MmsTcp`) | IEC-61850/MMS + eigener **TCP-Server** (Port 104) |

**Architektur-Smell:** Die `mms`/`mmsLight`/`mediator`-Controller bewegen Daten **direkt im Control-Plane-`DataFlowController`** statt über eine Data Plane (`PipelineService`-Factories). Das funktioniert nur in der kombinierten Runtime. `MMSDataSourceFactory`/`MMSDataSource` (echte DP-Pipeline-Interfaces) existieren, sind aber **nirgends registriert** → toter Pull-Pfad.

---

## 2. Versions-Deltas 0.14.1 → 0.17.0 (Dimension A)

Migrationsfenster: v0.15.0 (großer Sprung) → v0.15.1 (bugfix) → v0.16.0 → v0.17.0.
**Empfehlung: inkrementell migrieren, nach jedem Schritt bauen.**

| Bereich | Änderung | Version | BIBA-Betroffenheit |
|---|---|---|---|
| **`@Setting`/Config-Injection** | feldbasierte Injection mit `key`/`required`/`defaultValue` | 0.15.0 | **gering** – Proxy-Extensions nutzen das schon; mms/mmsLight/mediator nutzen `getSetting()` (läuft weiter) |
| **DataAddress-im-Vault** | `TransferProcess.dataAddress` verschlüsselt im Vault; neue SQL-Spalte `data_address_alias`; **`dataDestination`→`dataAddress`** in `TransferRequestMessage` | 0.16.0 | **mittel-hoch** – Controller, die DataDestination-`baseUrl` lesen |
| **Participant-Context-Entitäten** | neue Pflichtfelder (`participantContextId`, `dataplaneMetadata`); SQL-Migration | 0.15.0 | mittel (nur bei eigenen Stores; BIBA hat keine) |
| **`web.http.default`** | deprecated → direkt unter `web.http` | 0.15.0 | gering (Config) |
| **Jetty 11→12 / Jersey-Upgrade** | | 0.15.0 | gering (Standard-JAX-RS-Controller) |
| **`@Priority` für Auth-Filter** | jetzt explizit nötig | 0.15.0 | nur falls eigener Auth-Filter (BIBA: nein) |
| **EDR-Service-Registry** | neue `EndpointDataReferenceServiceRegistry` | 0.15.0 | nur bei direktem EDR-Store-Zugriff |
| **`edc.dsp.well-known-path.enabled` entfernt** | | 0.17.0 | gering (Config) |
| **`monitor-jdk-logger` entfernt** | | 0.17.0 | **prüfen** – BIBA bindet `edc-monitor-jdk-logger` in libs.versions.toml |

> Zu verifizieren gegen Autodoc-Settings-Diff: exakte Renames von `edc.transfer.proxy.token.*` / `edc.dataplane.*`.

---

## 3. tractus-x-edc: wie man Extensions/Runtimes einklinkt (Dimension B)

**Modul-Muster** (`edc-extensions/<feature>/<feature>-{spi,core,api}`):
- `build.gradle.kts`: nur `plugins { \`java-library\`; \`maven-publish\` }` — Toolchain/Checkstyle/Autodoc/Tests kommen zentral über `org.eclipse.edc.edc-build`. Deps via `libs.edc.spi.*` bzw. `project(":...")`.
- Klasse mit `@Extension(NAME)` + `implements ServiceExtension`, `@Inject`/`@Provider`.
- `src/main/resources/META-INF/services/org.eclipse.edc.spi.system.ServiceExtension` mit FQCN.
- In `settings.gradle.kts`: `include(":edc-extensions:<feature>:<feature>-core")`.

**Runtime-Assembly:** Eine Runtime ist ein reines Dependency-Aggregat (kein eigenes `main()`); gebootet über EDC `BaseRuntime`, Extensions per ServiceLoader + DI, gepackt per Shadow. Eine Extension kommt in die Runtime, indem das `-base`-Modul sie als `implementation(project(...))` aufnimmt.

**DataPlane-Registrierung (Referenzbeispiele im Repo):**
- Factory am PipelineService: `DataPlaneProxyHttpExtension` → `pipelineService.registerFactory(...)`.
- Eigener PipelineService: `non-finite-provider-push-core/NonFiniteCapablePipelineServiceExtension` (`@Provider PipelineService`).
- Eigener „Transfer-Typ": kein Enum — DataAddress-Property + `supportedType()`-Matching (vgl. `FinitenessEvaluatorImpl` mit `<EDC_NAMESPACE>isNonFinite`).

---

## 4. Empfohlene Strategie

1. **Zieltopologie zuerst: kombinierte Custom-Runtime** nach Vorbild `edc-runtime-memory` (Modul `edc-runtime-windx`). ⚠️ **Überholt:** die Annahme „`DataFlowController` laufen 1:1" ist **falsch** — 0.17 hat keinen `DataFlowManager`; die Logik musste in **DP-Factories** (kein „optionaler späterer Schritt", sondern der reale Weg).
2. ~~**Identität: zunächst `iam-mock`**~~ ⚠️ **Überholt:** gewählt wurde **Weg A** (echtes DCP, Docker-frei).
3. **Vertical-Slice-Migration, einfachste zuerst** — jede Extension wird als eigenes `edc-extensions/`-Modul gegen EDC 0.17.0 portiert, kompiliert und in die Custom-Runtime aufgenommen, bevor die nächste folgt.

**Reihenfolge:** `ParticipantIdLog` (Toolchain-Proof) → `proxy` (Custom+Mediator) → `mediator` → `mmsLight` → `mms`.

---

## 5. Phasenplan

### Phase 0 — Fundament (Toolchain-Proof) ✅ ABGESCHLOSSEN (2026-06-24)
- [x] Neues Custom-Runtime-Modul `edc-controlplane/edc-runtime-windx` (Vorbild `edc-runtime-memory`), in `settings.gradle.kts` registriert.
- [x] `ParticipantIdLogExtension` als Modul `edc-extensions/windx/windx-participant-log` portiert (Package `…windx.participantlog`, `@Extension`, `@Setting`-Feld statt `getSetting`, META-INF).
- [x] Runtime baut (`:edc-runtime-windx:shadowJar`) und startet bis `Runtime ... ready`, Log-Zeile `==> Loaded property edc.participant.id = WINDX_TEST` erscheint.
- **Ergebnis:** Build-/Packaging-/Boot-Kette verifiziert. **Details: [`phase-0-ergebnisse.md`](phase-0-ergebnisse.md).**
- **Befund:** DCP-Stack in `edc-controlplane-base` erzwingt Pflicht-Config + getrennte Web-Ports schon beim Boot → mit Dummy-`windx-dev.properties` gelöst; echte Identität erst Phase 1/2.

### Phase 1 — Proxy-Data-Plane (`proxy`) ✅ ABGESCHLOSSEN
> **Ist:** Modul `windx-mediator-proxy`, Mediator GET **+ PUT** (`proxyPut`→POST `/createVirtualDataSource`).
> `CustomProxy` **fallengelassen** (redundant zur tx-Public-API); eigener Transfer-Typ `MediatorHttpData-PULL`,
> zentrale `@Setting`-Mediator-URL. Details: [`phase-1-ergebnisse.md`](phase-1-ergebnisse.md). (Tasks unten historisch.)
- [ ] `CustomProxyDataPlaneExtension` + `ProxyController` portieren.
- [ ] `MediatorDataPlaneExtension` + `MediatorController` portieren.
- [ ] **Konflikt lösen:** doppeltes `addGeneratorFunction("HttpData")` → eigene Transfer-Typen/Generator-Keys; Kollision mit tx-HttpData-Generator prüfen.
- [ ] `med`-API-Kontext + Port-Mapping; hartkodierte Mediator-URLs/Creds → Config.

### Phase 2 — Mediator-FlowController (`mediator`) ✅ ABGESCHLOSSEN (anders gelöst)
> **Ist:** BIBA-Control-Plane-`MediatorFlowController` **verworfen** — 0.17 hat keinen `DataFlowManager`; der
> Standard-`LegacyDataPlaneSignalingFlowController` delegiert generisch. Reale Arbeit = **eine Zeile**
> `edrServiceRegistry.register(MEDIATOR_HTTP_DATA, authService)`. Details: [`phase-2-recherche.md`](phase-2-recherche.md).
- [ ] `MediatorExtension` + `MediatorFlowController` portieren.
- [ ] EDR-DataAddress-Erzeugung gegen 0.17-`EndpointDataReference`-API prüfen.
- [ ] Prioritätskollision der DataFlowController auflösen.

### Phase 3 — MMS Light (`mmsLight`) ✅ ABGESCHLOSSEN (anders gelöst)
> **Ist:** roher MMS-TCP-Tunnel als **DP-`EndpointDataReferenceService`** (`MmsLightEndpointDataReferenceService`,
> Typ `MmsLight` → `MmsLight-PULL`), `MMSTcpProxy` portiert, Proxy-Port **dynamisch (`ServerSocket(0)`)** statt fix 104.
> Kein Control-Plane-Controller. Closed-Loop-Hardware-Tunnel-Test grün. Details: [`phase-mms-light-plan.md`](phase-mms-light-plan.md).
- [ ] `MMSLightExtension` + `MMSLightDataFlowController` + `MMSTcpProxy` portieren.
- [ ] TCP-Server-Port (Default 104) in Custom-Runtime exponieren (Docker/Compose).
- [ ] `getSetting`-Keys (`edc.hostname`, `mms.light.proxy.port`) ggf. auf `@Setting`-Felder heben.

### Phase 4 — MMS Voll (`mms`) ✅ ABGESCHLOSSEN (anders gelöst)
> **Ist:** kompletter `mms.client`-Stack portiert; **PULL** über DP-Source-Factory (Source-Typ **`Mms`**, nicht `mms`;
> Registry-Key==Source-Typ==destType, Transfer-Typ `Mms-PULL`) + EDR-Registry; **push-once** über Standard
> `HttpData-PUSH`; **push-continuous** über `MMSDataSource`-Streaming-Modus (`edc:isNonFinite=true`) +
> `non-finite-provider-push-core`. Kein Control-Plane-`MMSDataFlowController`. Hardware-E2E grün gegen
> `agilevt-dev…:102`. Details: [`phase-4-recherche.md`](phase-4-recherche.md), [`phase-mms-push-continuous-plan.md`](phase-mms-push-continuous-plan.md).
- [ ] `MMSDataPlaneExtension` + `MMSDataFlowController` + kompletter `mms.client`-Stack (`AasIecConnector2`, `MMSClientManager`, `MMSAggregationCrawler`, …) portieren.
- [ ] **`dataDestination`→`dataAddress`-Rename** (0.16) im Push-Pfad anpassen.
- [ ] Entscheiden: `MMSDataSourceFactory` (toter Pull-Pfad) verdrahten oder entfernen.

### Phase 5 (optional) — Härtung
> **Hinweis:** Der Punkt „DP-`PipelineService`-Factories" war **kein optionaler Zukunftsschritt**, sondern der
> **real gewählte Weg** aller Phasen 1–4 (0.17-bedingt). Verbleibendes Hardening:
- [x] ~~Split-CP/DP mit DP-Factories~~ → in Phasen 1–4 umgesetzt (Mediator/mms/mms-light als Data-Plane-Extensions).
- [ ] Catena-X-Identität (DCP/BPN) produktiv — im Test via **Weg A** (echtes DCP, Docker-frei) bereits belegt.
- [ ] `Mms-PUSH` continuous auf **Option B** (eigener `MmsPushSink`) umstellen (leak-frei für Dauerbetrieb).
- [ ] Volle DCP-Transfer-Tests **T3** (`MmsLight-PULL`-Tunnel, continuous-Push end-to-end).
- [ ] `mms.client`-Rohsocket-MMS härten (Reconnect, Timeouts, Fehlerbehandlung).

---

## 6. Getroffene Entscheidungen (2026-06-23, mit Updates 2026-07)
1. **Runtime-Topologie:** kombiniert (CP+DP in einem Prozess, Vorbild `edc-runtime-memory`). ✅
2. ~~**Identität:** `iam-mock` zum Start.~~ **Überholt:** gewählt wurde **Weg A** = In-Memory-DCP-E2E, Docker-frei via `MockBdrsClient` (echtes DCP statt Mock). ✅
3. **Persistenz:** In-Memory zum Start. ✅
4. **Modul-Namespace:** `org.eclipse.tractusx.edc.windx.*`. ✅
5. ~~**Arbeitsweise:** nur planen, noch kein Code.~~ **Überholt:** Phasen 0–4 implementiert & grün.
6. ~~**`MMSDataSourceFactory`:** offen.~~ **Erledigt:** als DP-Source-Factory verdrahtet (Source-Typ **`Mms`**); der Pull-Pfad lebt.
7. **(2026-07) 0.17 ohne `DataFlowManager`:** alle BIBA-Control-Plane-`DataFlowController` (mediator/mms/mmsLight) verworfen, Logik in die Data Plane (EDR-Registry / Source-Factory / non-finite-Sink). ✅

---

## 7. Phase 0 — Detailspezifikation (Planung, kein Code)

Ziel: kombinierte Custom-Runtime `edc-runtime-windx` + erste portierte Extension `windx-participant-log` (aus `ParticipantIdLogExtension`). Beweist die Repackaging-/Boot-Kette.

### 7.1 Vorbild `edc-runtime-memory` (verifiziert im Clone)
- Liegt unter `edc-controlplane/edc-runtime-memory/`.
- Plugins: `` `java-library` ``, `id("application")`, `alias(libs.plugins.shadow)`.
- Deps: `implementation(project(":edc-controlplane:edc-controlplane-base"))` + `implementation(project(":edc-dataplane:edc-dataplane-base")) { exclude("org.eclipse.edc", "data-plane-selector-client") }` + `core:core-utils` + `edc-extensions:single-participant-vault` + `libs.edc.spi.core` + `libs.edc.spi.participant.context.single`.
- `mainClass = "org.eclipse.edc.boot.system.runtime.BaseRuntime"`.
- `shadowJar`: `mergeServiceFiles()`, `Log4j2PluginsCacheFileTransformer`, `archiveFileName = "${project.name}.jar"`.
- Eigene Extension `VaultSeedExtension` im Package `org.eclipse.tractusx.edc.vault.memory` (Beleg für tx-Namespace-Konvention + ServiceLoader-Registrierung).

### 7.2 Geplante neue Module
```
edc-extensions/windx/windx-participant-log/
├── build.gradle.kts
└── src/main/
    ├── java/org/eclipse/tractusx/edc/windx/participantlog/ParticipantIdLogExtension.java
    └── resources/META-INF/services/org.eclipse.edc.spi.system.ServiceExtension

edc-controlplane/edc-runtime-windx/        (Kopie/Anpassung von edc-runtime-memory)
├── build.gradle.kts
└── (optional) src/main/resources/configuration/...   # FS-Config, falls genutzt
```

### 7.3 `windx-participant-log/build.gradle.kts` (Skizze)
```kotlin
plugins {
    `java-library`
    `maven-publish`
}
dependencies {
    implementation(libs.edc.spi.core)
}
```

### 7.4 Portierte Extension (Skizze — Änderungen ggü. BIBA)
- Package: `org.eclipse.edc.sample.extension` → `org.eclipse.tractusx.edc.windx.participantlog`.
- `@Extension("WindX Participant Id Log")`-Annotation ergänzen (tx-Konvention).
- Participant-Id-Zugriff: `context.getSetting(...)` ist in 0.17 ggf. deprecated → prüfen, ob `@Setting`-Feld oder `context.getConfig().getString("edc.participant.id", "UNKNOWN")` zu nutzen ist.
- META-INF/services-Datei: FQCN `org.eclipse.tractusx.edc.windx.participantlog.ParticipantIdLogExtension`.

### 7.5 `edc-runtime-windx/build.gradle.kts` (Delta zu runtime-memory)
- 1:1 wie `edc-runtime-memory`, zusätzlich:
  `implementation(project(":edc-extensions:windx:windx-participant-log"))`

#### Befund zum `iam-mock` (verifiziert 2026-06-24)
`iam-mock` wird im Stack **nur** in Test-Runtimes genutzt:
- Katalog: `gradle/libs.versions.toml:89` → `edc-iam-mock = { module = "org.eclipse.edc:iam-mock", version.ref = "edc" }` (also `libs.edc.iam.mock`).
- `edc-tests/runtime/dcp/runtime-memory-sts/build.gradle.kts:35` und `edc-tests/compatibility-tests/build.gradle.kts:36`.
- **`runtime-memory-sts` ist KEIN schlankes Mock-Runtime**, sondern eine volle DCP-Runtime (IdentityHub-BOM + STS-Bundle), die `iam-mock` nur zusätzlich mitzieht → als Vorbild zu schwer.

**Kernkonflikt:** `edc-controlplane-base` verdrahtet den **kompletten DCP/SSI-Stack fest** (`runtimeOnly(libs.edc.bom.controlplane.dcp)` + `dcp:cx-dcp`, `dcp:tx-dcp`, `dcp:tx-dcp-sts-div`, `bdrs-client`, `did-document:*`). D. h. wer auf `edc-controlplane-base` basiert (so wie `edc-runtime-memory`) **bekommt automatisch einen `IdentityService` aus DCP**. Fügt man dazu `iam-mock` (das ebenfalls einen `IdentityService` als `@Provider` liefert), gibt es **zwei konkurrierende `IdentityService`-Provider → Boot-Konflikt**.

**Drei Wege (Entscheidung in Phase 1/2 nötig, NICHT in Phase 0):**
| Weg | Idee | Aufwand | Bemerkung |
|---|---|---|---|
| A | DCP echt fahren (kein Mock), auf `edc-runtime-memory` aufsetzen | hoch (IdentityHub/BDRS/Credentials nötig) | weicht von BIBA ab |
| B | Non-DCP-CP zusammenstellen (CP-BOM **ohne** DCP) + `libs.edc.iam.mock` | mittel-hoch | entspricht BIBA (Vanilla-Upstream + `iam-mock`) am nächsten |
| C | tx-eigene `MockIdentityServiceExtension` (`@Provider IdentityService` → `MockVcIdentityService`) übernehmen | mittel | braucht trotzdem DID-Infra (`DidPublicKeyResolver`, `JtiValidationStore`) → DCP-nah |

**Wichtig für Phase 0:** `ParticipantIdLog` liest nur ein Setting und loggt — **es braucht weder DSP noch Identität**. Die kombinierte Runtime bootet auch mit dem voreingestellten DCP-Stack (Negotiation ohne Credentials schlägt erst bei echten Transfers fehl, was Phase 0 nicht testet). → **Phase 0 baut zunächst auf `edc-runtime-memory` ohne `iam-mock`**; die Identitätsfrage wird erst beim ersten echten Transfer (Phase 1/2) über Weg A/B/C entschieden.

### 7.6 `settings.gradle.kts` — neue Includes
```kotlin
include(":edc-controlplane:edc-runtime-windx")
include(":edc-extensions:windx:windx-participant-log")
// :edc-extensions:windx (Parent) nur nötig, falls dort ein eigenes build.gradle.kts liegt
```

### 7.7 Build/Run zum Verifizieren
```bash
export JAVA_HOME=/home/flo/.sdkman/candidates/java/17.0.13-tem
./gradlew :edc-controlplane:edc-runtime-windx:shadowJar
java -jar edc-controlplane/edc-runtime-windx/build/libs/edc-runtime-windx.jar
# Erwartung im Log: "Loaded property edc.participant.id = ..."
```

### 7.8 Offene Punkte für Phase 0
- ~~`iam-mock` in tx-Runtime: enthält `edc-controlplane-base` schon einen IAM?~~ **Geklärt (s. 7.5):** ja, DCP ist fest verdrahtet → für Phase 0 ohne `iam-mock` bauen; Mock-Frage erst Phase 1/2 (Weg A/B/C).
- FS-Config (`EDC_FS_CONFIG`) vs. tx-Config-Mechanismus: minimale `*.properties` mit Ports + `edc.participant.id` definieren.
- `getSetting`-Deprecation in 0.17 verifizieren.

---

## 8. Quellen
- BIBA-Inventar: Quellanalyse `bibas-eclipse-data-space-connector/MeinConnector`
- tractus-x-edc: lokaler Clone (`edc-extensions/`, `edc-controlplane`, `edc-dataplane`, `gradle/libs.versions.toml`)
- EDC-Releases: https://github.com/eclipse-edc/Connector/releases (v0.15.0–v0.17.0)
- Configuration Injection (PR #4617), DataAddress-Vault (#5489), well-known-path (#5527), EDR-Registry (#5204)
- EDC DI-Doku: https://eclipse-edc.github.io/documentation/for-contributors/runtime/dependency-injection/
