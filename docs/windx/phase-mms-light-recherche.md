# `mms-light` — Architektur-Recherche (Portierung nach tractus-x 0.17)

**Datum:** 2026-07-07
**Scope:** Nur Recherche/Risikobewertung (kein Code). Auslöser: User-Fachentscheidung, dass `mms-light`
(nativer MMS/IEC-61850-TCP-Tunnel) tatsächlich gebraucht wird.
**Status:** ✅ Recherche abgeschlossen — `mms-light` ist portierbar, sauberer 0.17-Hook gefunden, kein Blocker.
**Umgesetzt:** ja (grün) — verbindliche Details + finale Namen in [`phase-mms-light-plan.md`](phase-mms-light-plan.md).

> **Hinweis:** Vor-Umsetzungs-Recherche. Finale Namen weichen ab: EDR-Service = `MmsLightEndpointDataReferenceService`
> (nicht `MmsTcpEndpointDataReferenceService`), Source-Typ vereinheitlicht auf **`MmsLight`** (nicht `mms-light`).

---

## 1. Was `mms-light` in BIBA ist

Ein **transparenter roher MMS-TCP-Tunnel**. Der Consumer bekommt eine `tcp://host:port`-Adresse und spricht
darüber **nativ MMS/IEC-61850** — im Gegensatz zu `mms` (Aggregation→JSON über HTTP).

**BIBA-Dateien** (`org.eclipse.edc.sample.extension.mmsLight`):
- **`MMSLightExtension`** — `ServiceExtension`; `dataFlowManager.register(10, new MMSLightDataFlowController(...))`.
  Setting `mms.light.proxy.port` (Default **104**, fest), `edc.hostname`.
- **`MMSLightDataFlowController`** — Control-Plane `DataFlowController`: `canHandle` Source-Typ `mms-light`,
  `transferTypesFor` = `MmsLight-PULL`. In `start()`: öffnet `ServerSocket(proxyPort)`, startet `MMSTcpProxy`,
  gibt eine **`MmsTcp`-DataAddress** zurück (`host`/`port`/`url=tcp://host:port`/`mms:logicalDevice`/`mms:sensors`).
  `terminate()`/`suspend()`: stoppt den Proxy (keyed by `transferProcess.getId()`).
- **`MMSTcpProxy`** — das Herzstück:
  1. verbindet sich über den (schon portierten!) **`MMSClient`** zum echten MMS-Server, `readServerModel` +
     `subscribeToReports`;
  2. pollt kontinuierlich (Default 5000 ms) einen Sensor, cacht die rohe TPKT-Read-Response;
  3. agiert **selbst als vollständiger MMS-Server** gegenüber Consumern: `accept()`-Loop, pro Client
     COTP-Connection-Confirm + MMS-Associate-Response (hartkodierte Frames), dann kontinuierlicher **Push**
     von `InformationReport`-Frames (`repackForClientPush` baut aus der Read-Response einen A3-Report,
     `convertMmsTagsToUniversal` schreibt MMS-Tags auf universelle BER-Tags um, die der `MMSClient`-Listener parst).

**Reale Nutzung:** In `MMS.json` **nicht** exerziert (toter Code, wie eager-Mediator/`mms-light` in Phase 2/3) —
aber vom User nun als **benötigt** eingestuft.

---

## 2. Warum das BIBA-Muster in 0.17 nicht 1:1 geht

Wie bei `mms`/`mediator`: `MMSLightExtension` nutzt **`dataFlowManager.register(10, controller)`** — den in
0.17 **entfernten** `DataFlowManager` (kein `DataFlowControllerRegistry` mehr; einziger `DataFlowController` =
`LegacyDataPlaneSignalingFlowController`, der generisch an die Data Plane delegiert). Der gesamte
Control-Plane-Shell fällt weg. Die Arbeit gehört in die **Data Plane**.

---

## 3. Der 0.17-Data-Plane-Hook (am Quellcode verifiziert)

**PULL-Fluss in 0.17** (`data-plane-core`, `DataPlaneManagerImpl`):
```
start(DataFlowStartMessage) → triggerDataFlow(dataFlow) → switch flowType {
    PULL → handlePull(dataFlow) → endpointDataReferenceServiceRegistry.create(dataFlow, source)
    PUSH → handlePush(dataFlow) → transferService.transfer(...)
}
```
- 🔑 **Bei PULL wird `TransferService.transfer()` NICHT aufgerufen** — nur die EDR wird erzeugt. Ein eigener
  `TransferService` ist für den PULL-Lifecycle also irrelevant.
- **Teardown:** `DataPlaneManagerImpl.stop()` (aus `terminate()`/`suspend()`) ruft bei PULL
  `endpointDataReferenceServiceRegistry.revoke(dataFlow, reason)`.

**`Endpoint` ist protokoll-agnostisch** (`data-plane-spi`):
```java
public record Endpoint(String endpoint, String endpointType) {
    public static Endpoint url(String url) { return new Endpoint(url, "https://w3id.org/idsa/v4.1/HTTP"); }
}
```
→ `new Endpoint("tcp://host:port", "tcp://")` ist zulässig. **Keine harten HTTP-Annahmen** brechen eine tcp-EDR.

**Bewerbung des Typs** (`DataplaneSelfRegistrationExtension`): PULL-Typen = `<T>-PULL` für jedes `T` in
`endpointDataReferenceServiceRegistry.supportedDestinationTypes()`. `MmsLight` dort registrieren ⇒ `MmsLight-PULL`.

### Empfohlene Zielarchitektur
Eine **eigene `EndpointDataReferenceService`-Impl** (`MmsTcpEndpointDataReferenceService`), registriert via
`edrRegistry.register("MmsLight", service)`. Sie kapselt den kompletten Socket-Lifecycle:
- `createEndpointDataReference(dataFlow)` → öffnet `ServerSocket(0)` (freier Port pro Transfer), startet
  `MMSTcpProxy` (liest `mms:*`-Properties aus der Source-DataAddress), merkt sich Proxy unter `dataFlow.getId()`,
  gibt eine `MmsTcp`-DataAddress (`tcp://host:port` + `mms:logicalDevice`/`mms:sensors`) zurück.
- `revokeEndpointDataReference(flowId, reason)` → stoppt/entfernt den Proxy zu `flowId`.

Damit sitzt **Start UND Teardown** an genau den 0.17-Lifecycle-Punkten — kein Seiteneffekt-Hack in einer reinen
Generator-Function (deren `revoke` beim Default-`DataPlaneAuthorizationServiceImpl` nur das Access-Token widerruft,
den Socket aber nicht schlösse).

### Registrierungen (analog zur Drei-Registrierungs-Erkenntnis)
1. `edrRegistry.register("MmsLight", mmsTcpEdrService)` → `supportedDestinationTypes()` enthält `MmsLight` ⇒ `MmsLight-PULL`.
2. Wahrscheinlich weiterhin **eine minimale Source-Factory** mit `supportedType()="mms-light"`
   (`pipelineService.registerFactory`), damit `LegacyDataPlaneSignalingFlowController.transferTypesFor(asset)` den
   Typ für ein `mms-light`-Asset überhaupt anbietet (allowedSourceType) — auch wenn sie bei PULL nie geöffnet wird
   (siehe Weg-A-Befund Phase 1/2). **Beim Impl verifizieren.**
3. Kein `addGeneratorFunction` nötig, wenn die eigene EDR-Impl den Endpoint direkt baut.

---

## 4. Risiken / offene Punkte

| Punkt | Bewertung |
|---|---|
| **Portierbarkeit** | ✅ Gegeben — sauberer create/revoke-Lifecycle-Hook, kein HTTP-Zwang. |
| **Port-Management** | BIBA: **ein fester Port 104** ⇒ nur EIN gleichzeitiger Transfer. Besser: `ServerSocket(0)` pro Flow (freier Port), Proxy kann `getLocalPort()`. |
| **EDR ohne Token** | Der Consumer verbindet roh per TCP (kein Bearer-Token auf der MMS-Verbindung); die eigene EDR-Impl gibt die `MmsTcp`-Adresse ohne Secure-Token zurück (BIBA-treu). Sicherheitsgrenze bleibt Vertrag/EDR-Ausgabe. Verifizieren, dass der Standard-EDR-Consumer-Flow eine token-lose DataAddress akzeptiert. |
| **Testbarkeit** | ✅ **Closed-Loop möglich ohne fremde Consumer-HW:** Proxy verbindet zum echten Server `agilevt-dev…:102`; als Consumer dient unser eigener portierter `MMSClient` gegen den Proxy-Port (der Proxy repackt Reports genau für diesen Listener). Ergibt einen echten E2E-Tunnel-Test. |
| **Vendored-Code** | `MMSTcpProxy` (~720 Z., roher ASN.1/TPKT/COTP, System.out, hartkodierte Associate-Frames) — wie der übrige `mms.client`-Stack ins `windx-mms`(-artige) Modul mit deaktiviertem Checkstyle. |
| **Modulwahl** | Entweder in `windx-mms` integrieren (teilt den `mms.client`-Stack, `MMSTcpProxy` nutzt `MMSClient`) oder eigenes `windx-mms-light`. Empfehlung: **in `windx-mms`** (kein Code-Duplikat, `MMSTcpProxy` hängt direkt am `MMSClient`). |

---

## 5. Fazit

`mms-light` ist **mit mittlerem Aufwand und ohne Blocker** portierbar. Der einzige zuvor unbekannte Punkt —
„wie serviert die 0.17-Data-Plane einen rohen TCP-Endpunkt mit Lifecycle?" — ist gelöst: **eigene
`EndpointDataReferenceService`-Impl für Typ `MmsLight`** (create=Socket öffnen+Proxy starten,
revoke=Proxy stoppen) + minimale `mms-light`-Source-Factory für die Typbewerbung. Testbar als echter
Closed-Loop-Tunnel gegen den realen Server, mit unserem `MMSClient` als Consumer.

## Quellen (verifiziert, 0.17.0-sources.jar im Gradle-Cache)
- `data-plane-core`: `DataPlaneManagerImpl` (`handlePull`/`stop`/`triggerDataFlow`), `EndpointDataReferenceServiceRegistryImpl`.
- `data-plane-iam`: `DataPlaneAuthorizationServiceImpl` (Default-EDR-Service, `revoke`=Token-Widerruf).
- `data-plane-spi`: `PublicEndpointGeneratorService`, `Endpoint`, `TransferService`.
- `data-plane-self-registration`: `DataplaneSelfRegistrationExtension` (Typbewerbung).
- BIBA: `mmsLight/{MMSLightExtension,MMSLightDataFlowController,MMSTcpProxy}.java`.
