# Phase 1 — Implementierungsplan: `windx-mediator-proxy`

**Datum:** 2026-06-29
**Grundlage:** `phase-1-recherche.md` §6 (alle 4 Entscheidungen getroffen).
**Ziel:** Die BIBA-`MediatorDataPlaneExtension` (nur GET-Pfad) als neues, additives Modul in den
tractus-x-edc-Stack portieren, in die `edc-runtime-windx` einhängen, und per Build + Boot + 401-Smoke-Test
verifizieren — analog zum Phase-0-Vorgehen.

> **⚠️ Aktualität (2026-07-07):** Umgesetzt & grün; die unten in §5 gelisteten „Risiken/offenen Punkte" sind
> **aufgelöst** — EDR-Service-Registry (drei Registrierungen, Weg A) und Identität (Weg A) erledigt.

---

## 0. Designentscheidungen (aus §6, kompakt)

| # | Entscheidung | Umsetzung im Plan |
|---|---|---|
| 1 | Custom-Proxy weg | wird **nicht** angelegt |
| 2 | Transfer-Typ `MediatorHttpData` | Schlüssel in `addGeneratorFunction(...)` + Asset-`type` + `transferType` |
| 3 | Mediator-URL = ein zentrales `@Setting` | `tx.edc.windx.mediator.base.url` (required), kein per-Asset-Pfad, kein Hardcode |
| 4 | Nur GET | nur `@GET proxyGet` → `…/forwardRealDataSource`; PUT bleibt Backlog |

---

## 1. Neues Modul — Dateien

```
edc-extensions/windx/windx-mediator-proxy/
├── build.gradle.kts
└── src/main/
    ├── java/org/eclipse/tractusx/edc/windx/mediator/
    │   ├── MediatorDataPlaneExtension.java
    │   └── MediatorController.java
    └── resources/META-INF/services/
        └── org.eclipse.edc.spi.system.ServiceExtension
```

### 1.1 `build.gradle.kts`
Vorbild: `dataplane-public-api-v2/build.gradle.kts`, aber minimiert auf das, was der Mediator wirklich
braucht (kein Swagger, kein Pipeline-Service). Aliase im Version-Catalog verifiziert.

```kotlin
plugins {
    `java-library`
}

dependencies {
    implementation(libs.edc.spi.core)                // ServiceExtension, @Setting/@Inject/@Extension, Monitor, EdcException
    implementation(libs.edc.spi.web)                 // WebService, PortMapping(Registry)
    implementation(libs.edc.spi.dataplane.dataplane) // Endpoint, DataPlaneAuthorizationService, PublicEndpointGeneratorService
    implementation(libs.jakarta.rsApi)               // JAX-RS-Annotationen im Controller

    testImplementation(libs.edc.junit)
}
```
> Lizenzheader (Eclipse, 2026) wie in den bestehenden windx-Dateien voranstellen.

### 1.2 `MediatorDataPlaneExtension.java`
Struktur 1:1 nach `DataPlanePublicApiV2Extension`, aber eigener Kontext `med`, eigener Typ, eigener Port.

- Package `org.eclipse.tractusx.edc.windx.mediator`, `@Extension(value = NAME)`.
- Konstanten: `MED = "med"`, `DEFAULT_MED_PORT = 8087`, `DEFAULT_MED_PATH = "/api/med"`,
  `MEDIATOR_HTTP_DATA = "MediatorHttpData"`.
- **Settings:**
  - `tx.edc.windx.mediator.base.url` — **required**, die zentrale Semantic-Mediator-Basis-URL (Entscheidung 3).
  - EDR-Public-Endpoint für den `med`-Kontext: optionales `@Setting`
    `edc.dataplane.windx.med.endpoint`; falls null → Default aus `hostname + port + path` bauen
    (genau wie der Standard es mit `publicBaseUrl` macht). Das ist die URL, die dem Consumer als
    Pull-Adresse in den EDR geschrieben wird — **nicht** die Mediator-Backend-URL.
- **Injects:** `PortMappingRegistry`, `PublicEndpointGeneratorService`, `WebService`,
  `DataPlaneAuthorizationService`, `Hostname`.
- **`initialize()`:**
  ```java
  // 1) eigener Web-Kontext auf eigenem Port -> umgeht Port-/Kontext-Kollision K2/K3
  portMappingRegistry.register(new PortMapping(MED, apiConfiguration.port(), apiConfiguration.path()));

  // 2) klare Validierung der Pflicht-URL (Entscheidung 3: Fehler statt stiller Default)
  if (mediatorBaseUrl == null || mediatorBaseUrl.isBlank()) {
      throw new EdcException("Setting 'tx.edc.windx.mediator.base.url' must be configured");
  }

  // 3) eigener Transfer-Typ -> umgeht die stille HttpData-Kollision K1
  var endpoint = Endpoint.url(medPublicEndpoint /* aus Setting oder hostname-Default */);
  generatorService.addGeneratorFunction(MEDIATOR_HTTP_DATA, dataAddress -> endpoint);

  // 4) Controller registrieren (nur GET)
  webService.registerResource(MED,
      new MediatorController(authorizationService, context.getMonitor(), mediatorBaseUrl));
  ```
- `@Settings record MedApiConfiguration(port, path)` mit Keys `web.http.med.port` / `web.http.med.path`
  (Default 8087 / `/api/med`) — exakt nach Vorbild der `PublicApiConfiguration`.

### 1.3 `MediatorController.java`
Portierung von BIBAs `MediatorController.proxyGet`, **bereinigt**:

- Package `org.eclipse.tractusx.edc.windx.mediator`, `@Path("{any:.*}")`, `@Consumes/@Produces(WILDCARD)`.
- Konstruktor `MediatorController(DataPlaneAuthorizationService, Monitor, String mediatorBaseUrl)`.
- Ein `HttpClient` als Feld (wie BIBA; Leak ist klein, akzeptiert — Cleanup optional als Nice-to-have).
- **Nur `@GET proxyGet`:**
  1. `Authorization`-Header fehlt → `401`.
  2. `authorizationService.authorize(token, emptyMap())` schlägt fehl → `403`.
  3. Aus der Source-`DataAddress` die **Payload**-Properties lesen (bleiben per-Asset!):
     `configurationID` (int, Default 27), `responseType` (Default `application/json`),
     `referenceModell` (Default `default-referenceModell`). *Nur die URL wird zentralisiert, die
     Payload-Parameter nicht.*
  4. JSON-Body bauen, `POST` an `mediatorBaseUrl + "/forwardRealDataSource"`.
  5. Antwort 1:1 zurückgeben; Fehlerpfade → `502`.
- **Bereinigungen ggü. BIBA:**
  - `System.out.println` (~25×) → `monitor.debug(...)` / `monitor.warning(...)`.
  - `MEDIATOR-URL`-Property + hartkodierte `agilevt-dev…`-URL **ersatzlos entfernt** (Entscheidung 3).
  - Verschachteltes try/catch mit totem `return` entwirren; sauberer Statuscode bei `!= 200`.
  - `MEDIATOR-SERVICE`-Logging entfernen (war reines Debug-Rauschen).

### 1.4 META-INF-Service-Registrierung
Datei `…/META-INF/services/org.eclipse.edc.spi.system.ServiceExtension` mit Lizenzheader + Zeile:
```
org.eclipse.tractusx.edc.windx.mediator.MediatorDataPlaneExtension
```

---

## 2. Verdrahtung in den Stack (2 geänderte Dateien)

### 2.1 `settings.gradle.kts`
Eine Zeile ergänzen (bei den windx-Includes):
```kotlin
include(":edc-extensions:windx:windx-mediator-proxy")
```

### 2.2 `edc-controlplane/edc-runtime-windx/build.gradle.kts`
Eine Dependency ergänzen (bei den windx-Extensions):
```kotlin
implementation(project(":edc-extensions:windx:windx-mediator-proxy"))
```

### 2.3 `edc-controlplane/edc-runtime-windx/windx-dev.properties`
Neue Keys (med-Port frei gewählt = 8087, kollidiert nicht mit 8080–8086):
```properties
web.http.med.port=8087
web.http.med.path=/api/med
tx.edc.windx.mediator.base.url=http://localhost:3051
```
> Dummy-Mediator-URL reicht für Boot-/Registrierungs-Test (wird ohne echten Transfer nicht aufgerufen).

---

## 3. Konfig-Schlüssel (neu)

| Key | Pflicht | Default | Zweck |
|---|---|---|---|
| `tx.edc.windx.mediator.base.url` | **ja** | — | Basis-URL des Semantic Mediators (Entscheidung 3) |
| `web.http.med.port` | nein | `8087` | Port des `med`-Web-Kontexts |
| `web.http.med.path` | nein | `/api/med` | Pfad des `med`-Web-Kontexts |
| `edc.dataplane.windx.med.endpoint` | nein | `http://<host>:<medport><medpath>` | EDR-Public-Endpoint (Pull-Adresse für Consumer) |

---

## 4. Verifikation (reproduzierbar, Phase-0-Stil)

```bash
export JAVA_HOME=/home/flo/.sdkman/candidates/java/17.0.13-tem
cd tractusx-edc

# 1) Build
./gradlew :edc-controlplane:edc-runtime-windx:shadowJar      # erwartet: BUILD SUCCESSFUL

# 2) Boot
java -Dedc.fs.config=edc-controlplane/edc-runtime-windx/windx-dev.properties \
     -jar edc-controlplane/edc-runtime-windx/build/libs/edc-runtime-windx.jar
```

**Erwartet im Log:**
- `MediatorDataPlaneExtension` wird geladen (kein DI-Fehler).
- `med`-Kontext bindet auf `:8087/api/med`, **keine** `A binding for port … already exists`.
- `Runtime … ready`.

**Cheap End-to-End-Check ohne Identität** (verifiziert, dass der Controller wirklich registriert ist):
```bash
curl -i http://localhost:8087/api/med/irgendwas        # erwartet: 401 (kein Authorization-Header)
```
→ `401` beweist: Route existiert + Controller hängt am `med`-Kontext, ohne dass eine echte EDR-/Identitätskette nötig ist.

---

## 5. Risiken & offene Punkte

1. **EDR-Service-Registry (real-Transfer-Risiko).** Der tractus-x-Standard registriert seinen Typ
   zusätzlich via `edrServiceRegistry.register(TYPE, authorizationService)`
   (`DataPlanePublicApiV2Extension.java:106/113`). BIBA (0.14.1) tat das **nicht**. Ob `MediatorHttpData`
   für einen **echten** `…-PULL`-Transfer in 0.17 diese Registrierung braucht, ist offen → **beim ersten
   echten Transfer prüfen** (Boot + 401-Smoke-Test brauchen es nicht).
2. **Identität (Weg A/B/C).** Ein echter End-to-End-Transfer berührt die in Phase 0 vertagte
   DCP-vs-Mock-Frage. Reiner Boot/Registrierungs-Test ist davon unabhängig.
3. **Asset-/Transfer-Form.** Damit der Mediator greift, muss das Asset `dataAddress.type = MediatorHttpData`
   und der Transfer `transferType = MediatorHttpData-PULL` tragen (analog `Mms-PULL` in `MMS.json`).
4. **Deprecation.** Legacy-Public-Data-Plane-Pull ist in 0.17 zugunsten Data Plane Signaling deprecated —
   technische Schuld, für jetzt okay (BIBA tut dasselbe).

---

## 6. Schritt-für-Schritt-Reihenfolge

1. Modulordner + 4 Dateien anlegen (1.1–1.4).
2. `settings.gradle.kts` + Runtime-`build.gradle.kts` ergänzen (2.1/2.2).
3. `./gradlew :edc-extensions:windx:windx-mediator-proxy:build` — Modul kompiliert isoliert.
4. `windx-dev.properties` ergänzen (2.3).
5. `:edc-controlplane:edc-runtime-windx:shadowJar` — Gesamt-Build grün.
6. Boot + Log prüfen + `curl`-401-Smoke-Test (§4).
7. Ergebnisse in `phase-1-ergebnisse.md` festhalten (analog `phase-0-ergebnisse.md`).
</content>
</invoke>
