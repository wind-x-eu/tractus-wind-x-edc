# Phase 2 — Recherche & Entscheidung: Control-Plane-`mediator`

**Datum:** 2026-06-29
**Scope:** BIBA-Package `org.eclipse.edc.sample.extension.mediator` (2 Dateien:
`MediatorExtension.java`, `MediatorFlowController.java`).
**Status:** ✅ Recherche abgeschlossen, **Entscheidung getroffen + umgesetzt**.

> **⚠️ Aktualität (2026-07-07):** Der in §6 als „später/vertagt" genannte echte End-to-End-Test + die
> Identitätsentscheidung sind **erledigt** — **Weg A** (echtes DCP, Docker-frei) belegt `MediatorHttpData-PULL`
> real (`MediatorConsumerPullTest`, grün).

---

## 1. Was Phase 2 in BIBA ist

Der Control-Plane-Zwilling zur Phase-1-Data-Plane. Zwei Dateien:

| Datei | Inhalt |
|---|---|
| `MediatorExtension.java` | `@Inject DataFlowManager` → `register(10, new MediatorFlowController())` |
| `MediatorFlowController.java` | eigener `DataFlowController`: `canHandle` = Typ `med` + `transferType "Mediator-PULL"`; `start()` POSTet an `…/forwardRealDataSource` und gibt eine EDR-`DataAddress` zurück (Typ `EndpointDataReference.EDR_SIMPLE_TYPE`) |

---

## 2. Kernfrage: Brauchen wir diesen Control-Plane-FlowController? → **NEIN**

Am 0.17-Code verifiziert:

**(a) Der Standard-Controller ist generisch.** Die Runtime registriert (via `@Provider`) den
`LegacyDataPlaneSignalingFlowController`. Dessen `canHandle`:
```java
public boolean canHandle(TransferProcess tp) {
    return transferTypeParser.parse(tp.getTransferType()).succeeded();   // jeder wohlgeformte <Typ>-PULL/PUSH
}
```
Er behandelt **jeden** wohlgeformten Transfer-Typ und delegiert per Data-Plane-Selektor an die Data Plane,
die den Typ **bewirbt**. Genau so läuft der Standard-`HttpData-PULL` ohne jeglichen Control-Plane-Custom-Code.
→ Ein eigener `DataFlowController` ist im DPS-Modell **strukturell überflüssig** (gleiche Logik wie der in
Phase 1 fallen gelassene Custom-Proxy).

**(b) Wie die Data Plane Typen bewirbt (0.17).** `DataplaneSelfRegistrationExtension`:
```java
@Inject EndpointDataReferenceServiceRegistry endpointDataReferenceServiceRegistry;
...
toTransferTypes(PULL, endpointDataReferenceServiceRegistry.supportedDestinationTypes(), ...)
   ... .allowedTransferType(...)
```
Die beworbenen **PULL-Typen kommen aus der `EndpointDataReferenceServiceRegistry`** — **nicht** aus dem
`PublicEndpointGeneratorService` (der hat in 0.17 kein `supportedDestinationTypes()` mehr).

---

## 3. 🔴 Befund: Phase 1 war dafür unvollständig (jetzt behoben)

Der Standard-Public-API macht **beides**:
```java
generatorService.addGeneratorFunction("HttpData", ...);                                   // Endpoint-URL  ← hatten wir
edrServiceRegistry.register(PROXY_HTTP_DATA_TYPE, (EndpointDataReferenceService) authorizationService);  // Typ BEWERBEN  ← fehlte
```
Phase 1 hatte nur `addGeneratorFunction(...)`. Damit wäre `MediatorHttpData-PULL` **nicht beworben** worden
→ der Selektor fände keine Data Plane → echter Transfer schlägt im `prepare()` fehl. Der 401/403-Smoke-Test
sah das nicht, weil dabei kein Selektor lief.

**Umgesetzt (2026-06-29):** in `MediatorDataPlaneExtension.initialize()` ergänzt:
```java
edrServiceRegistry.register(MEDIATOR_HTTP_DATA, (EndpointDataReferenceService) authorizationService);
```
(+ `@Inject EndpointDataReferenceServiceRegistry`). Build grün, Runtime bootet, 401/403 weiter ok.

---

## 4. Entscheidung

| Frage | Entscheidung |
|---|---|
| BIBA-`MediatorFlowController` (Control Plane) portieren? | **NEIN — fallen lassen.** Der Standard-DPS-Controller routet `MediatorHttpData-PULL` selbst. |
| Was ist die echte „Phase-2"-Arbeit? | **Eine Zeile `edrServiceRegistry.register(...)`** in der Phase-1-Extension (erledigt). |
| `MediatorExtension` (registriert nur den Controller)? | entfällt mit dem Controller. |

→ Phase 2 (BIBA-Form) wird **nicht** als eigenes Modul gebaut. Es gibt **keinen** neuen Code außer der
einen Registrierungszeile in `windx-mediator-proxy`.

---

## 5. Fachliche Frage eager vs. lazy → ✅ ENTSCHIEDEN: lazy

Verhaltensunterschied: Der BIBA-Controller notifiziert den Mediator **eager beim Transfer-Start** (Control
Plane) und legt `MEDIATOR-RESULT` in die EDR. Unser Phase-1-Ansatz notifiziert **lazy beim Pull** (im
`MediatorController`).

**Am BIBA-Code verifiziert, dass der eager-Pfad toter Code ist:**

| Prüfung | Ergebnis |
|---|---|
| Wird `MEDIATOR-RESULT` (eager-Output) irgendwo gelesen? | **nirgends** — nur der FlowController schreibt es |
| Wird `Mediator-PULL` / `Med-PULL` (Control-Plane-Typ) ausgelöst? | **nirgends** — kein Asset/Request/Doc |
| Real benutzte Transfer-Typen (`MMS.json`) | `HttpData-PUSH` (5×), `Mms-PULL` (1×), `Mms-PUSH` (1×) |
| Real angelegter DataAddress-Typ | `mms` — **kein** `med` |
| Interner Widerspruch | `canHandle` prüft `Mediator-PULL`, `transferTypesFor` liefert `Med-PULL` → nie ausgeführt |

Der real benutzte Mediator-Pfad (`MMS.json` „Schritt 7 Download @Home" → GET `/mediator` mit Auth-Header)
ist der **Data-Plane-Pull** — also genau unser **lazy**-Muster aus Phase 1.

→ **Entscheidung: lazy.** Kein Beleg, dass eager je gebraucht/genutzt wurde; der eager-Control-Plane-Pfad
ist in BIBA nachweislich tot. Frage geschlossen.

---

## 6. Konsequenzen für die echte End-to-End-Verifikation (später)

Mit der Registrierungszeile sollte ein `MediatorHttpData-PULL`-Transfer über den Standard-Controller laufen.
Definitiv beweisbar erst mit echtem Transfer → braucht die vertagte **Identitätsentscheidung (Weg A/B/C)**.
Asset-`dataAddress.type` + `transferType` müssen `MediatorHttpData` / `MediatorHttpData-PULL` lauten.

---

## 7. Quellen (verifiziert im Clone / Gradle-Cache 0.17.0)
- `LegacyDataPlaneSignalingFlowController.java` (generisches `canHandle`) — `transfer-data-plane-signaling-0.17.0-sources.jar`
- `DataplaneSelfRegistrationExtension.java` (PULL-Typen aus `EndpointDataReferenceServiceRegistry`) — `data-plane-self-registration-0.17.0-sources.jar`
- `PublicEndpointGeneratorService` (kein `supportedDestinationTypes()` mehr) — `data-plane-spi-0.17.0-sources.jar`
- `DataPlanePublicApiV2Extension.java:105/106/113` (Vorbild: addGeneratorFunction **+** edrServiceRegistry.register)
- `DataFlowController.java` (0.17: `provision`→`prepare`, neu `started/completed/transferTypesFor(String)`) — `transfer-spi-0.17.0-sources.jar`
- BIBA: `…/extension/mediator/{MediatorExtension,MediatorFlowController}.java`
