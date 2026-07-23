# `Mms-PUSH` continuous — Implementierungs- & Testplan (code-fundiert)

**Datum:** 2026-07-07
**Scope:** Nur Recherche/Plan (kein Code). Ziel: BIBAs **kontinuierlichen** Provider-Push (jede MMS-Aggregation wird
laufend an eine Ziel-URL gePOSTet) in tractus-x 0.17 umsetzen — via das vorhandene `non-finite-provider-push`-Modul.
**Status:** ✅ **UMGESETZT & GRÜN (2026-07-07)** — Option A (Standard-`HttpData-PUSH`), s. §8.
**Update 2026-07-21:** ✅ **Option B umgesetzt** — eigener Transfertyp **`Mms-PUSH`** (`MmsPushSink`, Sink-Typ `Mms`), s. §9.

---

## 1. Was BIBA macht (Ist)

`MMSDataFlowController` (Control-Plane) + `MMSPushTask`: `transferTypesFor`=`Mms-PUSH`; `start()` liest
`dataDestination.baseUrl`, startet `MMSPushTask` = `MMSClientManager`+`MMSAggregationCrawler` mit Callback
`push(json)` → **POSTet jede** Aggregation (application/json) an die baseUrl; `terminate/suspend` stoppt.
Kontinuierlicher Strom bis Terminate. Gleiche `mms:*`-Config wie unsere `MMSDataSource`.

In 0.17 **nicht 1:1**: kein `DataFlowManager` mehr (Control-Plane-Controller unportierbar). PUSH gehört in die
Data Plane über `TransferService.transfer()` = `PipelineService` (Source→Sink).

---

## 2. Der 0.17-Mechanismus (am Quellcode verifiziert)

### `non-finite-provider-push-core` (liegt bereits im Repo, in `settings.gradle.kts`)
- **`NonFiniteCapablePipelineService`** (`@Provider public PipelineService`) — ersetzt die Standard-PipelineService.
  In `transfer(request, sink)`: erzeugt Source, `sink.transfer(source)`; wenn `finitenessEvaluator.isNonFinite` →
  gibt ein Future zurück, das **nur bei Fehler** komplettiert (sonst nie) → DataFlow bleibt dauerhaft `STARTED`.
  Speichert die Source unter `processId`; `terminate()` → `source.close()`.
- **`FinitenessEvaluatorImpl`** — non-finite ⟺ Source-DataAddress hat `edc:isNonFinite == "true"`.
- **Provider-Präzedenz ✅ KEIN Konflikt** (verifiziert `DependencyGraph.java:96`): der Standard
  `DataPlaneDefaultServicesExtension` stellt `PipelineService` als **`@Provider(isDefault=true)`** bereit (Fallback,
  landet in `defaultServiceProviders`). Der non-finite-`@Provider` (non-default) **überschreibt** ihn automatisch,
  sobald das Modul im Classpath ist. Bringt zugleich den `FinitenessEvaluator` mit (`FinitenessEvaluatorExtension`).

### DataPlaneManager PUSH-Lifecycle (`DataPlaneManagerImpl`, verifiziert)
`processReceived()` → `transferService.transfer(request)` **fire-and-forget** (`.whenComplete(onTransferCompletion)`),
`flow.transitionToStarted()` sofort. Future komplettiert nie (non-finite) → bleibt `STARTED`, Lease wird periodisch
erneuert. `terminate/suspend` → `transferService.terminate(dataFlow)` → `NonFiniteCapablePipelineService.terminate`
→ `source.close()`. Matching: `getSourceFactory` nach `sourceDataAddress.getType()`, `getSinkFactory` nach
`destinationDataAddress.getType()` → unsere `Mms`-Source-Factory wird mit dem Ziel-Sink gepaart.

### 🔑 Kritischer Befund zum Standard-`HttpDataSink` (asyncAllOf)
`HttpDataSink extends ParallelSink`; `ParallelSink.process()` macht
`PartitionIterator.streamOf(parts, partitionSize).map(processPartitionAsync).collect(asyncAllOf())`.
- `PartitionIterator` ist **lazy** (Iterator, `hasNext()` blockiert bedarfsgesteuert) → **POSTs passieren laufend**,
  während der Stream traversiert wird (jede gezogene Partition feuert sofort einen async POST). Der Agent-Befund
  „postet nie" ist **falsch**.
- ABER: `asyncAllOf()` = `collectingAndThen(toList(), …)` — `collect()` ist ein **blockierender** Terminal-Op und
  `toList()` **akkumuliert alle** Partition-Futures. Bei unendlichem Stream: `collect()` kehrt nie zurück →
  (a) **ein Executor-Thread bleibt dauerhaft blockiert** (aus dem **geteilten** HttpDataSink-Executor →
  Starvation-Risiko für andere Transfers!) und (b) **eine Future-Liste wächst unbegrenzt** (langsames Memory-Leak).
- `partitionSize` ist per Setting **`edc.dataplane.http.sink.partition.size`** (Default 5) konfigurierbar (→ 1 =
  jeder Part sofort einzeln). Behebt aber weder Thread-Blockade noch Leak.

**Schlussfolgerung:** Der Standard-`HttpDataSink` **funktioniert** für einen kurzen/mittleren Lauf, ist aber für
einen *dauerhaften* Strom wegen Shared-Executor-Blockade + Leak **ungeeignet**. → **eigener Sink empfohlen.**

---

## 3. Zielarchitektur (Plan: empfohlen Option B — **umgesetzt wurde Option A**, s. §8)

> **Hinweis:** Dieser Abschnitt ist der Planungsstand. Der User wählte **Option A** (Standard-`HttpData-PUSH`,
> kein eigener Sink). Real gebaut/getestet: `MMSDataSource`-Streaming-Modus + `MmsPushContinuousIT` +
> `NonFiniteSinkConsumptionTest` (nicht `MmsPushSink`). Verbindlich ist **§8**. Option B bleibt der empfohlene
> Ausbau für echten Dauerbetrieb.

Drei Teile, alle im Modul `windx-mms`:

1. **`MMSDataSource` — non-finite-Streaming-Modus.** Aktuell wartet `openPartStream()` auf **eine** Aggregation
   (120 s) → `Stream.of(einPart)`. Neu: wenn `edc:isNonFinite=="true"` in der Source-DataAddress, liefert es einen
   **unendlichen, blockierenden** `Stream<Part>` — je eine JSON-Aggregation pro Element, gespeist aus der
   `resultQueue` des Crawlers (`Stream.generate(() -> blockingNextAggregation()).takeWhile(p -> p != POISON)`).
   `close()` stoppt Manager+Crawler und legt eine **Poison-Pill** in die Queue → `takeWhile` beendet den Stream sauber.
2. **`MmsPushSink` + `MmsPushSinkFactory`** (neu, BIBA-`MMSPushTask`-treu, **kein `asyncAllOf`**). `supportedType()`
   = eigener Ziel-Typ (Vorschlag **`HttpPush`**). `transfer(source)` läuft auf einem **eigenen** Executor:
   `partStream.forEach(part -> POST part an dataDestination.baseUrl, Content-Type application/json)`; Fehler →
   loggen/weiterlaufen (BIBA-treu) oder Future-Fehler (→ Transfer FAILED). Kein Batching, keine Future-Akkumulation,
   isolierter Thread (keine Shared-Executor-Starvation). Nutzt `EdcHttpClient` (injiziert).
3. **`WindxMmsPushDataPlaneExtension`** (neu): `pipelineService.registerFactory(new MmsPushSinkFactory(httpClient,…))`
   → `supportedSinkTypes()` enthält `HttpPush` ⇒ Provider bewirbt **`HttpPush-PUSH`**. (`Mms` als Source-Typ ist
   bereits registriert; `getSinkFactory` matcht `destinationDataAddress.type=="HttpPush"`.)

**Asset/Transfer-Kontrakt:** Provider-Asset `dataAddress.type="Mms"` + **`edc:isNonFinite="true"`** + `mms:*`-Props.
Consumer-Transfer: `transferType="HttpPush-PUSH"`, `dataDestination={type:"HttpPush", baseUrl:"http://…"}`.
Ablauf: `NonFiniteCapablePipelineService` paart `Mms`-Source (unendlich) + `HttpPush`-Sink → POSTet jede Aggregation
laufend an baseUrl; `isNonFinite` hält den Transfer `STARTED`; terminate → `source.close()` → Stromende.

### Runtime-Verdrahtung
- `implementation(project(":edc-extensions:non-finite-provider-push:non-finite-provider-push-core"))` in
  `edc-controlplane/edc-runtime-windx` **und** Test-Runtime `runtime-memory-dcp-ih-windx` (ersetzt die PipelineService).
- `windx-mms` `build.gradle.kts`: Dep auf `data-plane-http-spi` (für `EdcHttpClient`/`HttpDataAddress`) bzw. den
  `EdcHttpClient` (bereits via core verfügbar). Neue Extension in `META-INF/services`.
- **Regression-Pflicht:** nach Aufnahme des non-finite-Moduls die komplette `MediatorConsumerPullTest`-Suite (5) +
  Boot prüfen — die non-finite-PipelineService muss finite Flows (Pull-EDR, push-once) unverändert bedienen.

### Option A (leichtere Alternative, dokumentiert): Standard-`HttpData-PUSH`
Nur `MMSDataSource`-Streaming-Modus + non-finite-Modul + `edc.dataplane.http.sink.partition.size=1`; Ziel-Typ bleibt
`HttpData` (BIBA-`dataDestination`-treu), kein neuer Sink. **Aber:** Shared-Executor-Thread-Blockade + Future-Leak
pro Transfer (s. §2) → nur für kurze Läufe/Demo, nicht für Dauerbetrieb. Weniger Code, schlechtere Robustheit.

**Empfehlung: Option B** (eigener Sink) — „continuous" heißt langlebig, da zählen Thread-Isolation & Leak-Freiheit;
zudem am nächsten an BIBAs `MMSPushTask`.

---

## 4. Teststrategie (dank erreichbarem Server echt E2E)

### T1 — Sink-/Streaming-Unit-Test (kein Netzwerk, Muster: `NonFiniteCapablePipelineServiceIntegrationTest`)
Synthetische **unendliche** Source (liefert N Fake-Aggregationen, dann blockiert/Poison) + `MmsPushSink` gegen einen
In-Memory-/lokalen HTTP-Stub. Assert: **mehrere** POSTs kommen an (nicht nur einer); `source.close()` beendet den
Stream; kein `asyncAllOf`-Deadlock. Deckt Sink-Logik + non-finite-Streaming-Contract ab.

### T2 — Hardware-Continuous-IT (`@Tag("MmsHardwareIT")`) — *Kernbeweis*
`MMSDataSource` (non-finite) gegen den echten Server `agilevt-dev…:102` (WPPS/`LCCS1$ST$Mod$stVal`,
updateInterval klein) + `MmsPushSink` → lokaler `HttpServer`/WireMock. ~30–60 s laufen lassen, assert **≥2**
POSTs mit echten Aggregationsdaten (Timestamps unterscheiden sich), dann `close()` → keine weiteren POSTs.
Beweist den kontinuierlichen Push end-to-end. (`AGGREGATION_SIZE=10` × updateInterval steuert die Rate.)

### T3 — voller DCP-Push-Transfer (`@EndToEndTest`, optional/größer)
`MediatorConsumerPullTest`-Stil: `Mms`+`isNonFinite`-Asset, Consumer verhandelt `HttpPush-PUSH` mit
`dataDestination.baseUrl` = lokaler WireMock; assert Transfer erreicht `STARTED` **und** WireMock erhält **≥2** POSTs;
dann Terminate → Stop. Braucht MMS-Server-Zugriff aus der Test-Runtime (gegeben) + non-finite-Modul in der
Provider-Runtime. Am aussagekräftigsten, aber am aufwändigsten → nach T1/T2.

### Regression
`MediatorConsumerPullTest` (5) grün halten (Pull-EDR/PUT/mms-/mms-light-Advertising) + `windx-mms`-Unit/IT +
Boot-Smoke — insb. nachweisen, dass die non-finite-PipelineService die finiten Flows nicht bricht.

---

## 5. Fallstricke → Gegenmaßnahmen
| Fallstrick | Maßnahme |
|---|---|
| Shared-Executor-Blockade + Future-Leak (Standard-HttpDataSink) | **Eigener `MmsPushSink`** mit eigenem Executor, `forEach`-POST, ohne `asyncAllOf` (Option B). |
| PipelineService-Ersetzung bricht finite Flows? | Volle Regression (5 Tests) + Boot nach Modul-Aufnahme; non-finite-Impl ist voll PipelineService-kompatibel. |
| Sauberes Stromende bei Terminate | `close()` → Manager/Crawler stop + Poison-Pill → `takeWhile` beendet Stream; `terminate()` ist verdrahtet. |
| Unendlicher `Stream.generate` nie beendbar | `takeWhile(p -> p != POISON)`; blockierendes `resultQueue.poll(timeout)` im Supplier. |
| Reconnect/Fehler beim MMS-Server | BIBA-treu: Crawler/Manager haben Auto-Reconnect; Sink-POST-Fehler loggen, Strom weiterlaufen lassen. |

## 6. Umsetzungsreihenfolge
1. `MMSDataSource` non-finite-Streaming-Modus (+ Poison-Pill-close) — Unit-testbar mit synthetischer Queue.
2. `MmsPushSink` + `MmsPushSinkFactory` + `WindxMmsPushDataPlaneExtension` + `META-INF/services`.
3. non-finite-Modul in beide Runtimes; `build.gradle.kts`-Deps; Boot-Smoke (`HttpPush-PUSH` beworben).
4. T1 (Sink/Streaming-Unit) → T2 (Hardware-continuous) → Regression → optional T3 (DCP).
5. Doku + Memory.

## 8. ✅ Umgesetzt & grün (2026-07-07) — Option A (User-Entscheidung)

**Umgesetzt (Standard-`HttpData-PUSH`, kein eigener Sink):**
- **`MMSDataSource` non-finite-Streaming-Modus** (einziger neuer Produktivcode): bei
  `edc:isNonFinite=="true"` (gleiche Property wie `FinitenessEvaluatorImpl`) liefert `openPartStream()` einen
  unendlichen, blockierenden `Stream.generate(...).takeWhile(!=POISON)` — je eine JSON-Aggregation aus der
  Crawler-Queue. `close()` stoppt Manager/Crawler + legt Poison-Pill → Stream endet. Finiter Pfad (Pull/push-once)
  unverändert.
- **`non-finite-provider-push-core`** in beide Runtimes (`edc-runtime-windx` + `runtime-memory-dcp-ih-windx`):
  ersetzt die Standard-PipelineService. Kein Provider-Konflikt.
- Kontrakt: Provider-Asset `type=Mms` + `edc:isNonFinite=true` + `mms:*`; Consumer `transferType=HttpData-PUSH`,
  `dataDestination={type:HttpData, baseUrl}`. (Ggf. `edc.dataplane.http.sink.partition.size=1` für POST-pro-Aggregation.)

**Verifikation (grün):**
- **T1 `MmsPushContinuousIT`** (`@Tag MmsHardwareIT`) — `MMSDataSource` non-finite gegen den echten Server:
  **≥2 reale Aggregationen** kontinuierlich empfangen, `close()` beendet den Strom (Consumer-Thread ausgelaufen). `PASSED`.
- **T2 `NonFiniteSinkConsumptionTest`** (deterministisch, kein Netz) — beweist, dass der **echte `ParallelSink`**
  (den der HttpDataSink erbt) einen **unendlichen** Stream **laufend** konsumiert (≥3 Parts) und das non-finite-Future
  offen bleibt. **Widerlegt die asyncAllOf-„Deadlock"-Sorge:** `collect()` traversiert lazy → jeder Part löst sofort
  `transferParts` aus. `PASSED`.
- **Regression** `MediatorConsumerPullTest` **5/5** (Pull-EDR/PUT/mms-/mms-light-Advertising/mms-PULL-Transfer) — die
  non-finite-PipelineService bricht **keinen** finiten Flow. `edc-runtime-windx` shadowJar grün.

**Bewusste Restpunkte (Option A):**
- **Ressourcen-Retention pro non-finite-Transfer** (Grund für Option B in §3): der Standard-`ParallelSink` blockiert
  dauerhaft **einen** (geteilten) Executor-Thread in `collect()` und akkumuliert die Partition-Futures in einer
  wachsenden Liste. Für einzelne/mittlere Läufe unkritisch, für Dauer-/Viel-Transfer-Betrieb → auf **Option B**
  (eigener `MmsPushSink`) umstellen.
- **T3 (voller DCP-Continuous-Push-Transfer)** noch offen: negotiate `HttpData-PUSH` + `isNonFinite`-Asset →
  Provider postet an Consumer-`baseUrl` (WireMock), assert ≥2 POSTs. Bewusst zurückgestellt (Netzwerk zum echten
  MMS-Server aus der Test-Runtime + CI-Hygiene). T1+T2 decken Source und Sink-Mechanik bereits ab.

## 9. ✅ Option B umgesetzt (2026-07-21) — Transfertyp `Mms-PUSH`

**Auslöser:** Die Data-Explorer-UI (Repo des Kollegen) sendet fest `transferType: 'Mms-PUSH'`
(BIBA-Vorlage) — die Tractus-X-Data-Plane bewarb aber nur `Mms-PULL`/`MmsLight-PULL`/`HttpData-PUSH`,
weshalb die Diagnose-Station dort abgeschaltet war (`supportsMmsPush: false`).

**Mechanik (am 0.17.0-Bytecode verifiziert, `DataplaneSelfRegistrationExtension`):** beworbene
Transfertypen = EDR-Destination-Typen × `-PULL` ∪ **`pipelineService.supportedSinkTypes()` × `-PUSH`**.
`Mms-PUSH` erscheint also genau dann, wenn eine `DataSinkFactory` mit `supportedType()=="Mms"`
registriert ist. Der Sink-Dispatch zur Laufzeit läuft über **`dataDestination.type`** (nicht über den
Transfertyp-String) — der Consumer muss deshalb `dataDestination: {type: "Mms", baseUrl: …}` senden.

**Umgesetzt (alles im Modul `windx-mms`, null CP-/Chart-/Config-Änderungen):**
- **`MmsPushSink`** — der in §3 geplante eigene Sink (BIBA-`MMSPushTask`-treu): eigener
  Single-Thread-Daemon-Executor pro Instanz, blockierendes `forEach` über den Part-Stream, je
  Aggregation ein POST (`Content-Type: application/json`) an `dataDestination.baseUrl`.
  **Kein `ParallelSink`/`asyncAllOf`** → die §8-Restpunkte (dauerhaft blockierter Shared-Executor-Thread
  + unbegrenzt wachsende Future-Liste) sind damit behoben. Fehlerpolitik: einzelne POST-Fehler
  (non-2xx, IOException) nur loggen + weiterstreamen — ein failed-Result würde den non-finiten Flow
  terminal FAILED machen (`NonFiniteCapablePipelineService` komplettiert bei non-finite nur bei Fehler);
  einziger failed-Pfad ist eine Source, die gar nicht erst aufgeht.
- **`MmsPushSinkFactory`** — `supportedType()="Mms"` (== `MMS_TRANSFER_DESTINATION_TYPE`),
  `validateRequest` prüft `baseUrl` (vorhanden + URI mit Schema); `getStringProperty("baseUrl")`
  löst den JSON-LD-expandierten Key (`edc:baseUrl`) mit Fallback auf den rohen Key auf.
- Registrierung als 4. Schritt in `WindxMmsDataPlaneExtension.initialize()`. Kommt damit automatisch
  in `edc-runtime-windx`, `edc-runtime-windx-postgresql`, `edc-dataplane-windx` und die Test-Runtime
  `runtime-memory-dcp-ih-windx`.

**Kontrakt:** Provider-Asset `type=Mms` + **`edc:isNonFinite=true`** + `mms:*`-Props; Consumer
`transferType="Mms-PUSH"`, `dataDestination={type:"Mms", baseUrl:"http://…/log?signal=…"}`.
Ohne `isNonFinite` ist der Push finit (eine Aggregation, dann COMPLETED) — deshalb im Cluster-Seed
ein **eigenes** Live-Asset `windx-mms-live-1` (`windx-cx-seed.sh` §3d); das PULL-Asset bleibt finit.

**Verifikation:**
- **`MmsPushSinkTest`** (5 Tests, ohne Netz; JDK-`HttpServer` als Logger-Stub + synthetische unendliche
  Source): laufende Zustellung mit korrektem Content-Type, `close()` beendet Future + Strom, transienter
  500er bricht den Strom nicht, Factory-Validierung, und mit echter `NonFiniteCapablePipelineService`:
  Future bleibt offen, `terminate` stoppt die POSTs. Alle grün.
- **e2e-Advertising:** `MediatorConsumerPullTest.mmsAsset_offersMmsPullMmsPushAndHttpDataPush` prüft
  jetzt zusätzlich `Mms-PUSH` in der Katalog-Distribution.
- **Cluster:** `windx-cx-push-transfer.py` (Muster `windx-cx-mms-transfer.py`) — Katalog → Negotiation →
  `Mms-PUSH` → STARTED bleibt STARTED, ≥2 Aggregationen landen im Forwarding-Service, Terminate stoppt
  den Strom. Push-Ziel ist die **Forwarding-Service-Nachbildung** in ns `windx`
  (`windx-forwarding.py`/`.yaml`/`-deploy.sh`, API-kompatibel zu BIBAs
  `simple.mms.forwarding.service`: `POST/GET /log?signal=…`, `GET /log/queues`).

## 7. Quellen (verifiziert, 0.17.0-sources im Gradle-Cache)
- non-finite: `NonFiniteCapablePipelineService`, `FinitenessEvaluatorImpl`, `FinitenessEvaluator` (Repo).
- 0.17: `ParallelSink`/`PartitionIterator`/`AsyncUtils.asyncAllOf` (util-lib/data-plane-util),
  `HttpDataSink`/`HttpDataSinkFactory`/`DataPlaneHttpExtension` (`edc.dataplane.http.sink.partition.size`),
  `DataPlaneDefaultServicesExtension` (`@Provider(isDefault=true)`), `DependencyGraph` (Provider-Präzedenz),
  `DataPlaneManagerImpl` (`processReceived`/`onTransferCompletion`/terminate).
- BIBA: `mms/{MMSDataFlowController,MMSPushTask,MMSDataPlaneExtension}`.
