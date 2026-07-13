# Phase 1 — Recherche: `proxy`-Extensions (Custom-Proxy + Mediator)

**Datum:** 2026-06-24
**Status:** ✅ Recherche **und alle 4 Entscheidungen abgeschlossen** (siehe §6). Implementierung inzwischen fertig & grün (`phase-1-ergebnisse.md`). Das unten als „🔴 PFLICHT" gelistete **PUT-Pfad-Backlog ist erledigt**.
**Scope:** Das BIBA-Paket `org.eclipse.edc.sample.extension.proxy` (4 Dateien).

---

## 1. Was migriert werden soll (BIBA-Bestand)

Vier Dateien, zwei `ServiceExtension`s + zwei JAX-RS-Controller:

| Datei | Typ | Funktion |
|---|---|---|
| `proxy/CustomProxyDataPlaneExtension.java` | ServiceExtension | registriert PUBLIC-Kontext + HttpData-Generator + `ProxyController` |
| `proxy/ProxyController.java` | JAX-RS Resource | **generischer HTTP-Passthrough-Proxy** (GET) |
| `proxy/MediatorDataPlaneExtension.java` | ServiceExtension | registriert `med`-Kontext + HttpData-Generator + `MediatorController` |
| `proxy/MediatorController.java` | JAX-RS Resource | **Semantic-Mediator-Adapter** (GET→POST-Notify, PUT→createVirtualDataSource) |

Beide Extensions sind in der BIBA-`META-INF/services/...ServiceExtension` registriert (bestätigt).

### 1a. Was die beiden Extensions beim Boot tun

```
CustomProxyDataPlaneExtension.initialize():
  portMappingRegistry.register(new PortMapping(PUBLIC, 8185, "/api/public"))   // ApiContext "public"
  generatorService.addGeneratorFunction("HttpData", da -> Endpoint.url(<edc.dataplane.proxy.public.endpoint>))
  webService.registerResource(PUBLIC, new ProxyController(authorizationService))   // @Path("{any:.*}")

MediatorDataPlaneExtension.initialize():
  portMappingRegistry.register(new PortMapping("med", 8185, "/api/med"))       // ApiContext "med"
  generatorService.addGeneratorFunction("HttpData", da -> Endpoint.url(<edc.dataplane.proxy.med.endpoint>))
  webService.registerResource("med", new MediatorController(authorizationService))   // @Path("{any:.*}")
```

### 1b. Was die Controller tun

- **`ProxyController`** (GET): liest `Authorization`-Header → `authorizationService.authorize(token, {})` → aus der Source-`DataAddress` das Property `edc:baseUrl` → hängt den Request-Pfad an → leitet den HTTP-Request 1:1 weiter und streamt die Antwort zurück. **Das ist ein generischer EDR-autorisierter HTTP-Pull-Proxy.**
- **`MediatorController`** (GET + PUT): autorisiert ebenso, baut aber aus DataAddress-Properties (`MEDIATOR-URL`, `configurationID`, `responseType`, `referenceModell`) eine **JSON-POST-Anfrage an den Semantic Mediator** (GET → `/forwardRealDataSource`, PUT → `/createVirtualDataSource`). Kein reiner Passthrough, sondern Protokoll-Transformation.

---

## 2. 🔴 Kollisionsmatrix mit tractus-x-edc

Der tractus-x-Data-Plane (`edc-dataplane-base`, **fest eingebunden** in unsere `edc-runtime-windx`) bringt **bereits** genau die Bausteine mit, die BIBA registrieren will. Belegt durch Quellcode **und** unser Phase-0-Bootlog (*„The public API endpoint … default 'http://localhost:8085/api/public' will be used"*).

| # | Konflikt | tractus-x-Stelle | BIBA-Stelle | Kritikalität |
|---|---|---|---|---|
| K1 | `addGeneratorFunction("HttpData", …)` doppelt | `DataPlanePublicApiV2Extension.java:105` | beide Extensions | 🔴 kritisch |
| K2 | `PortMapping("public", …)` doppelt | `DataPlanePublicApiV2Extension.java:93` | `CustomProxy:54` | 🔴 kritisch (Boot-Abbruch) |
| K3 | Wildcard-Resource `@Path("{any:.*}")` auf `public` doppelt | `DataPlanePublicApiV2Extension.java:120` | `CustomProxy:58` | 🔴 kritisch |
| K4 | weitere Resource auf `public` (`@Path("/token")`) | `TokenRefreshApiExtension.java:51` | — (nur Kontext geteilt) | 🟡 Kontext-Teilung |
| — | `med`-Kontext | (existiert in tx nicht) | `Mediator:58` | 🟢 frei |
| — | `proxy`-Kontext :8186 | `DataPlaneProxyConsumerApiExtension` | — | 🟢 separat |

### K1 im Detail — die SPI-Semantik (verifiziert an EDC 0.17.0-Sources)
`PublicEndpointGeneratorServiceImpl.addGeneratorFunction(type, fn)` macht ein **stilles `generatorFunctions.put(type, fn)`** in eine `ConcurrentHashMap` (keyed auf den `destinationType`, z. B. `"HttpData"`). Daraus folgt:
- **Kein Fehler bei Duplikat — der zuletzt initialisierte Registrant gewinnt.**
- Schon **innerhalb von BIBA** ist das ein latenter Bug: Custom- und Mediator-Extension registrieren beide `"HttpData"` → einer überschreibt den anderen; alle `HttpData`-Transfers bekommen die Endpoint-URL der zuletzt geladenen Extension.
- In tractus-x kommt `DataPlanePublicApiV2Extension` als **dritter** Registrant für `"HttpData"` hinzu. Je nach Init-Reihenfolge wird **entweder der Standard-tractus-x-Pull oder der BIBA-Proxy stillschweigend kaputtgemacht** — ohne Fehlermeldung. Das ist der gefährlichste Punkt.

### K2 im Detail — `PortMapping` wirft tatsächlich
Genau diese Klasse von Fehler haben wir in Phase 0 schon live gesehen: `A binding for port 8181 already exists`. Der `PortMappingRegistry` lässt **keine** zwei Mappings auf demselben Port zu → doppeltes `PortMapping("public", …)` bricht den Boot ab. (Zusätzlich kollidieren die Default-Ports: BIBA-Default `8185` = tractus-x-Default `8185`.)

---

## 3. 💡 Architektur-Kernbefund

**`CustomProxyDataPlaneExtension` ist in tractus-x praktisch redundant.**
Der `ProxyController` (EDR autorisieren → `baseUrl` aus DataAddress → HTTP weiterleiten) macht **funktional dasselbe** wie der tractus-x-`DataPlanePublicApiV2Controller`, der bereits im Stack steckt und auf `:8085/api/public` läuft. Der generische „autorisierter HTTP-Pull-Proxy"-Use-Case ist im tractus-x-Standard **schon abgedeckt**.

→ **Empfehlung: `CustomProxy` NICHT 1:1 portieren, sondern fallen lassen** und stattdessen den Standard-Public-API von tractus-x für den einfachen Proxy-Fall nutzen. Spart genau die drei kritischen Kollisionen K1–K3.

**`MediatorDataPlaneExtension` ist die eigentliche Phase-1-Arbeit.**
Der `MediatorController` ist *keine* Standard-Funktion — die Transformation HTTP→Semantic-Mediator (`forwardRealDataSource` / `createVirtualDataSource`, JSON-Body aus DataAddress-Properties) gibt es im tractus-x-Stack nicht. Diese Extension muss migriert werden.

---

## 4. Weitere Probleme im BIBA-Code (Migrationsschulden)

| Problem | Ort | Folge |
|---|---|---|
| **Hartkodierte BIBA-URL** `http://agilevt-dev.ikap.biba.uni-bremen.de:3051/...` | `MediatorController:91,237` | nicht portierbar, muss `@Setting` werden |
| **`System.out.println`-Logging** (~25×) | `MediatorController` durchgängig | gehört auf `Monitor`; flutet stdout |
| **`HttpClient` pro Controller-Instanz, nie geschlossen** | beide Controller | Ressourcen-/Thread-Leak bei vielen Instanzen (klein, aber unsauber) |
| **Verschachteltes try/catch mit totem `return` am Ende** | `MediatorController.proxyGet` | unleserlich; Status-`!=200` ohne Body-Rückgabe → schweigt |
| **`X-Configuration-ID` nur wenn `!= 0`** | `MediatorController:254` | Default 27 wird gesendet, 0 nie — Logik fragwürdig |
| **Reuse des Typs `"HttpData"`** statt eigenem Transfer-Typ | beide Extensions | Ursache von K1 |

---

## 5. Empfohlenes Phase-1-Design (zur Abstimmung)

**Ein neues Modul** `edc-extensions/windx/windx-mediator-proxy/` mit:

1. **Nur die Mediator-Funktion** migrieren (Custom-Proxy weglassen, s. §3).
2. **Eigener ApiContext** `med` mit **eigenem, eindeutigem Port** (z. B. `web.http.med.port=8087`, Pfad `/api/med`) — nicht `public`. → umgeht K2/K3/K4.
3. **Eigener Transfer-/Destination-Typ** statt `"HttpData"`, z. B. `addGeneratorFunction("MediatorHttpData", …)`. tractus-x macht das genauso vor (`"ProxyHttpData"`). → umgeht K1 vollständig.
4. **Semantic-Mediator-URL als `@Setting`** (z. B. `tx.edc.windx.mediator.base-url`), Default optional.
5. **`Monitor` statt `System.out`**, Controller aufräumen.
6. Package → `org.eclipse.tractusx.edc.windx.mediator`, `@Extension`-Annotation, eigene META-INF-Registrierung.

**Konsequenz für den Datentransfer:** Damit ein Transfer über `/api/med` läuft, muss das **Asset/der Transfer den neuen Destination-Typ** (`MediatorHttpData`) verwenden, nicht `HttpData`. Das betrifft Asset-/Contract-Definition + EDR-Aushandlung, nicht nur die Extension → in der Verifikation mit einkalkulieren.

### Alternative (nicht empfohlen)
BIBA-Verhalten 1:1 erhalten (Custom-Proxy + `"HttpData"`) würde erfordern, `dataplane-public-api-v2` aus `edc-dataplane-base` **auszuschließen**. Das bricht den Standard-tractus-x-Pull und der `TokenRefreshApiExtension` hängt am `public`-Kontext. → hoher Kollateralschaden, verwerfen.

---

## 6. Entscheidungen (getroffen 2026-06-29)

1. **Custom-Proxy fallen lassen — JA.** ✅ Verifiziert: Der BIBA-`ProxyController` ist eine *funktionale Teilmenge* des tractus-x-`DataPlanePublicApiV2Controller` (GET-only, HTTP-only, schwächerer Autorisierungskontext, kein `PipelineService`) **und** wird in BIBAs eigenen Demo-Flows (`MMS.json`) gar nicht benutzt — der reale Datenabruf läuft über die Standard-Pfade `/api/` bzw. `/public`. Risiko des Weglassens: praktisch null.
2. **Transfer-Typ-Name = `MediatorHttpData`.** ✅ Folgt der tractus-x-Konvention (`PROXY_HTTP_DATA_TYPE = "ProxyHttpData"`, `DataPlanePublicApiV2Extension.java:113`). Wichtig: eindeutig (nicht `"HttpData"`), exakt/case-sensitiv an drei Stellen gleich (Generator-Registrierung ↔ Asset-DataAddress-`type` ↔ Transfer-`transferType`), kein Bindestrich im Basistyp (Wire-Form `MediatorHttpData-PULL`).
3. **Mediator-Ziel-URL = Option A: reines `@Setting`** (eine zentrale Instanz, z. B. `tx.edc.windx.mediator.base-url`). ✅ Es gibt fachlich nur einen Mediator. Der per-Asset-Pfad `MEDIATOR-URL` **und** die hartkodierte `agilevt-dev.ikap.biba.uni-bremen.de`-URL fallen ersatzlos weg. Fehlt das `@Setting` → klarer Fehler, kein stiller Default.
4. **Scope Phase 1 = nur GET** (`forwardRealDataSource`). ✅ PUT wird im aktuellen Szenario nirgends aufgerufen (0 PUT-Requests in `MMS.json`, `createVirtualDataSource` kommt außer in seiner eigenen Methode nirgends vor).

### 🔴 Backlog — PFLICHT für später (nicht vergessen!)
- **PUT-Pfad (`createVirtualDataSource`) MUSS später migriert werden.** Bewusst aus Phase 1 ausgeklammert, aber fest eingeplant. Beim Nachziehen mitbereinigen: Streaming-Body, die fragwürdige `configurationId != 0`-Logik (`MediatorController.java:254`), `System.out`→`Monitor`. Gleiches `med`-Kontext-/`MediatorHttpData`-Design wie GET.

---

## 7. Risiken

- **Stille Fehlfunktion (K1):** Wäre der gefährlichste Bug, wenn wir `"HttpData"` wiederverwenden — kein Boot-Fehler, aber Transfers liefern falsche Endpoints. Der eigene Typ (§5.3) eliminiert das.
- **Deprecation:** Der Legacy-Data-Plane (das ganze Public-API-Pull-Modell) ist in EDC 0.17 **deprecated** zugunsten Data Plane Signaling (im Phase-0-Bootlog 2× gewarnt). Die Mediator-Migration baut also auf einem Auslaufmodell auf. Für jetzt okay (BIBA tut dasselbe), aber als technische Schuld vormerken — perspektivisch DPS-konforme Lösung.
- **EDR-/Identitäts-Abhängigkeit:** `authorizationService.authorize(token,…)` setzt eine funktionierende EDR-Token-Kette voraus. Ein echter End-to-End-Test des Mediators berührt damit die in Phase 0 vertagte Identitätsfrage (Weg A/B/C). Reiner Boot + Registrierung lässt sich aber schon vorher verifizieren.

---

## 8. Quellen (verifiziert im Clone)
- BIBA: `…/MeinConnector/src/main/java/org/eclipse/edc/sample/extension/proxy/*.java`
- tractus-x: `edc-extensions/dataplane/dataplane-proxy/dataplane-public-api-v2/.../DataPlanePublicApiV2Extension.java` (Z. 93/105/113/120)
- tractus-x: `edc-extensions/dataplane/dataplane-token-refresh/token-refresh-api/.../TokenRefreshApiExtension.java` (Z. 51)
- tractus-x: `edc-dataplane/edc-dataplane-base/build.gradle.kts` (Z. 35–38)
- EDC SPI-Semantik: `data-plane-core-0.17.0-sources.jar` → `PublicEndpointGeneratorServiceImpl` (silent `put`)
- Phase-0-Bootlog: `public API endpoint default :8085/api/public`, `ConsumerProxyAPI`, Legacy-Data-Plane-Deprecation
