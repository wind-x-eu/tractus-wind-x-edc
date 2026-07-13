# Phase 3 — Recherche & Entscheidungsvorlage: `mmsLight`

**Datum:** 2026-07-01
**Scope:** BIBA-Package `org.eclipse.edc.sample.extension.mmsLight` (4 Dateien) + transitive
Abhängigkeit auf `mms.client` (Phase-4-Kern).
**Status:** 🔎 Historische Recherche/Entscheidungsvorlage (Momentaufnahme 2026-07-01).

> **⚠️ Aktualitäts-Hinweis (2026-07-07):** Damalige Entscheidung **A** (mms-light als Einzelphase übersprungen)
> wurde später durch eine **User-Fachentscheidung revidiert**: **`mms-light` wird gebraucht und ist inzwischen
> gebaut & grün** (roher MMS-TCP-Tunnel als DP-`EndpointDataReferenceService`, Closed-Loop-Hardware-Test) — s.
> [`phase-mms-light-plan.md`](phase-mms-light-plan.md). Ebenso **überholt:** Befund „kein Live-E2E ohne Hardware"
> (es gibt jetzt den echten Testserver `agilevt-dev…:102`, Hardware-Tests grün) und Befund „mms-light = toter Code"
> (als BIBA-`MMS.json`-Analyse korrekt, fachlich aber revidiert). Die interne 0.17-Analyse (kein `DataFlowManager`,
> ~90 % Code-Überlappung) bleibt gültig.

---

## 1. Was `mmsLight` in BIBA ist

| Datei | Inhalt |
|---|---|
| `MMSLightExtension` | `@Inject DataFlowManager` → `dataFlowManager.register(10, new MMSLightDataFlowController(...))` |
| `MMSLightDataFlowController` | Control-Plane-`DataFlowController`: `canHandle` = dataAddress-Typ `mms-light`; `start()` startet einen **rohen TCP-Server** (`MMSTcpProxy`) und liefert eine **`MmsTcp`-DataAddress** (`tcp://host:port`); `transferTypesFor` = `MmsLight-PULL` |
| `MMSTcpProxy` (722 Z.) | bindet `ServerSocket` (Default-Port 104), pollt zyklisch einen echten IEC-61850/MMS-Server über `MMSClient`, spricht selbst COTP/TPKT/MMS-ASN.1 gegen die Consumer-Clients, cached + pusht Reports |
| `example/MmsDataAddressExample` | nur Doku/Beispiel-Strings, kein produktiver Code |

**Externe Abhängigkeiten des benötigten Codes:** nur `slf4j` + `gson`. Das MMS-Protokoll ist
**handgeschrieben** (`AasIecConnector2`, 1049 Z., Raw-Sockets/ASN.1) — kein Framework.

---

## 2. Vier gewichtige Befunde

### 🔴 Befund 1 — `mms-light` ist in BIBAs eigener Nutzung toter Code
In `MMS.json` (BIBAs reale Request-Collection) kommt **kein** `mms-light`, **kein** `MmsLight-PULL`,
**kein** `MmsTcp`, kein Port 104, kein `pollingInterval` vor. Real benutzt: `HttpData-PUSH`, `Mms-PULL`,
`Mms-PUSH` — alle mit dataAddress-Typ **`mms`** (Phase 4, `MMSDataFlowController`), nicht `mms-light`.
→ Gleiche Lage wie der in Phase 2 als tot nachgewiesene eager-Mediator-Pfad.

### 🔴 Befund 2 — `mmsLight` zieht den vollen MMS-Client-Stack rein (= Phase-4-Kern)
`MMSTcpProxy` nutzt `new MMSClient(...)`, `connect/disconnect/readServerModel/subscribeToReports/
isConnected/getConnector/decodeDataAndReturnValue` und `AasIecConnector2.buildMmsReadRequest3(...)`.
`MMSClient` (1089 Z.) + `AasIecConnector2` (1049 Z.) sind zugleich der Kern von Phase 4. **Phase 3 und 4
teilen ~90% Code** — die Plan-Aufteilung ist künstlich; ein separates, früheres Phase-3-Modul lohnt den
Client-Stack-Port nicht mehr als Phase 4.

### 🔴 Befund 3 — Architektur-Bruch: 0.17 hat KEINEN `DataFlowManager` mehr (verifiziert)
BIBA (0.14) registriert `mmsLight` **und** `mms` als **zusätzliche** Control-Plane-`DataFlowController`
per `dataFlowManager.register(10, controller)` (Prioritäts-Registry + `canHandle`-Selektion).

**In EDC 0.17.0 existiert weder `DataFlowManager` noch `DataFlowControllerRegistry` in irgendeinem Jar.**
Stattdessen:
- `DataFlowController` ist ein **einzelner** `@Inject`-Service (belegt in `control-plane-transfer`
  `TransferProcessorsImpl`, `control-plane-catalog` `DefaultDistributionResolver`,
  `control-plane-aggregate-services`).
- Einziger `@Provider DataFlowController` = `LegacyDataPlaneSignalingFlowController`
  (**`@Deprecated since 0.16`**), dessen `canHandle` jeden wohlgeformten `<Typ>-PULL/PUSH` annimmt und an
  die **Data Plane** delegiert.
- Keine Prioritäts-Selektion, kein Mehrfach-Controller-Mechanismus.

→ **Ein eigener Control-Plane-`DataFlowController` ist in 0.17 nicht mehr additiv registrierbar.** Ein
eigener `@Provider DataFlowController` würde den Legacy-Controller **ersetzen** (Single-Injection) und damit
**alle** Standard-Transfers zerstören (HttpData-PULL/PUSH **und** unser `MediatorHttpData-PULL`). Nicht
tragbar. BIBAs `mmsLight`- **und** `mms`-Muster sind so **strukturell unportierbar**. Das ist exakt der in
Plan §1 notierte „Architektur-Smell": BIBA bewegt Daten im Control-Plane-Controller; 0.17 verlangt die
Data Plane.

**Verifikation:** `DataFlowController`-Interface 0.17 = `canHandle`, `prepare`, `start`
(→ `StatusResult<DataFlowResponse>`), `suspend`, `terminate`, `started`, `completed`,
`transferTypesFor(Asset)`, `transferTypesFor(String)`. `DataFlowResponse.Builder` kann eine **beliebige**
`DataAddress` als Consumer-EDR liefern (relevant, falls Data-Plane-Redesign). Quelle:
`transfer-spi-0.17.0-sources.jar`, `transfer-data-plane-signaling-0.17.0-sources.jar`,
Cache-Scan aller `*0.17.0*`-Jars (kein `DataFlowManager`).

### 🔴 Befund 4 — Kein Live-E2E-Test möglich
Der Mediator ließ sich mit einem WireMock-HTTP-Stub end-to-end testen (Weg A). `mmsLight` braucht auf
beiden Seiten einen echten IEC-61850/MMS-Endpunkt (Raw-ASN.1/COTP); ein Stub dafür wäre extrem aufwendig.
Verifikation bliebe auf Build-grün + Boot + Typ-Advertising beschränkt.

---

## 3. Konsequenz für die Architektur (gilt für Phase 3 **und** 4)

Die einzige 0.17-konforme Umsetzung der mms/mmsLight-Funktion ist **in der Data Plane** — wie schon beim
Mediator (Phase 1 baute eine echte DP-Extension mit Source-Factory):
- **`Mms-PUSH`** (Phase 4, real genutzt) → DP-Push-Pipeline (`DataSink`/`TransferService`), die den
  `mms.client`-Stack antreibt und an die Consumer-`baseUrl` pusht.
- **`mms-light` / `MmsTcp`** → DP-Flow, der bei `DataFlowStartMessage` den TCP-Proxy startet und eine
  `MmsTcp`-EDR-DataAddress zurückgibt. Deutlich aufwendiger (non-HTTP-EDR), für nachweislich toten Code.

Das entspricht der in Plan §5 als *optional* gelisteten „mms/mediator-Logik in DP-Factories umbauen"-Härtung
— ist hier aber **nicht optional, sondern Voraussetzung** jeder 0.17-Portierung von mms/mmsLight.

---

## 4. Entscheidungsvorlage

| Option | Idee | Aufwand | Bewertung |
|---|---|---|---|
| **A (empfohlen)** | Phase 3 als Einzelphase **überspringen**, direkt **Phase 4 `mms`** angehen: `mms.client`-Stack **einmal** als Modul portieren + real genutzten `Mms-PUSH`/`Mms-PULL`-Pfad als **DP**-Extension bauen. `mms-light` danach als optionale DP-Variante — oder streichen. | – | Vermeidet Doppelarbeit (Befund 2), zielt auf real genutzten Code (Befund 1), löst den Architektur-Bruch einmal zentral (Befund 3). |
| **B** | `mmsLight` jetzt trotzdem, als **DP-Redesign** (Proxy-Start + `MmsTcp`-EDR in DP-Flow). | hoch | Viel Arbeit + kein Live-Test (Befund 4) für toten Code (Befund 1). Schwer zu rechtfertigen. |
| **C** | `mms-light` **streichen** (wie eager-Mediator in Phase 2), Plan aktualisieren, direkt Phase 4. | gering | Sauber, falls die TCP-Pull-Variante fachlich nicht gebraucht wird. |

**Offene Fachfrage an den User:** Wird die `mms-light`/`MmsTcp`-**Pull**-Variante (Consumer verbindet sich
per rohem TCP/MMS mit einem Provider-Proxy) fachlich überhaupt gebraucht — oder reicht der real genutzte
`mms`-**Push**-Pfad (Provider pusht MMS-Daten per HTTP an den Consumer)?

---

## 5. Quellen (verifiziert)
- BIBA: `…/extension/mmsLight/{MMSLightExtension,MMSLightDataFlowController,MMSTcpProxy}.java`,
  `…/extension/mms/{MMSDataPlaneExtension,MMSDataFlowController}.java`, `mms/client/*` (nur slf4j+gson).
- `MMS.json` (kein `mms-light`/`MmsLight-PULL`/`MmsTcp`).
- EDC 0.17.0 Gradle-Cache: kein `DataFlowManager`/`DataFlowControllerRegistry`; einzelner `@Inject
  DataFlowController`; `LegacyDataPlaneSignalingFlowController` (`@Deprecated 0.16`) als einziger Provider.
- Vgl. [`phase-2-recherche.md`](phase-2-recherche.md) (identisches „toter Code + Standard-Controller"-Muster).
</content>
