# `mms-light` — Implementierungs- & Testplan (code-fundiert)

**Datum:** 2026-07-07
**Basis:** `docs/phase-mms-light-recherche.md` (Architektur) + intensive Code-Recherche (0.17-SPI + BIBA + unser `windx-mms`).
**Status:** ✅ **UMGESETZT & GRÜN (2026-07-07)** — s. §6.

---

## 0. Kernentscheidung: EIN konsistenter Typ-String `MmsLight`

Am 0.17-Code verifiziert (`EndpointDataReferenceServiceRegistryImpl`):
- `create(dataFlow, source)` dispatcht nach **`source.getType()`** (Source-Address-Typ).
- `revoke(dataFlow)` dispatcht nach **`dataFlow.getTransferType().destinationType()`**.
- `supportedDestinationTypes()` = **alle Registry-Keys** (→ jeder Key wird als `<key>-PULL` beworben).

**Konsequenz:** Damit ein *echter* PULL-Transfer läuft, muss gelten
**Source-Address-Typ == Registry-Key == Transfer-destinationType**. Nur EIN Key registrieren (sonst werden
mehrere `-PULL`-Typen beworben). Deshalb: durchgängig **`MmsLight`** verwenden — Asset-`dataAddress.type` =
`MmsLight`, Registry-Key `MmsLight`, Source-Factory `supportedType()="MmsLight"`, Transfer-Typ `MmsLight-PULL`.
(BIBA nutzte Source `mms-light` + Transfer `MmsLight-PULL` — in 0.17 bräche das `create`. Bewusste,
dokumentierte Abweichung; der beworbene Transfer-Typ `MmsLight-PULL` bleibt BIBA-identisch.)

> ✅ **Latenter Bug in Phase 4a (mms) — BEHOBEN 2026-07-07** (s. §6): gleiche Ursache (Registry-Key `Mms` ≠
> Asset-Source-Typ `mms` ⇒ echter `Mms-PULL`-Transfer scheiterte an `create`, „No EDR service with type mms found").
> **Umgesetzter Fix:** Source-Typ auf **`Mms`** vereinheitlicht (Source==Key==destType; Transfer-Typ bleibt
> `Mms-PULL`) + Beweis-Test `mmsPull_reachesStarted_edrCreated`. (Der ursprünglich hier notierte Alternativ-Fix
> „Registry unter `mms` + Transfer-Typ `mms-PULL`" wurde **nicht** gewählt.)

---

## 1. Was portiert/gebaut wird (Modul `edc-extensions/windx/windx-mms`)

**Empfehlung:** in `windx-mms` integrieren (kein Duplikat; `MMSTcpProxy` hängt direkt am schon portierten
`MMSClient`). Alle benötigten Methoden sind in unserem Port bereits **public**: `MMSClient(host,port)`,
`connect/readServerModel/subscribeToReports/disconnect/isConnected`, `getConnector()`,
`decodeDataAndReturnValue(byte[])`; `AasIecConnector2.sendRequest("read",req)`, statisch
`AasIecConnector2.buildMmsReadRequest3(domain,item,invokeId)`.

### Neue/portierte Dateien
1. **`client/MMSTcpProxy.java`** — 1:1 aus BIBA portiert (nur Package `…windx.mms.client`, Apache-Header,
   Checkstyle im Modul bereits deaktiviert). ~720 Z. roher TPKT/COTP/ASN.1-Server + Polling + Push.
   **Eine Verbesserung:** Konstruktor-Overload mit `serverPort=0` → `ServerSocket(0)` (freier Port pro Flow),
   `getLocalPort()` liefert den echten Port. (BIBA hatte 1 festen Port ⇒ nur 1 Transfer gleichzeitig.)
2. **`MmsLightEndpointDataReferenceService.java`** (neu, sauber) — implementiert
   `EndpointDataReferenceService`:
   - Feld `Map<String, MMSTcpProxy> activeProxies` (key = `dataFlow.getId()`).
   - `createEndpointDataReference(DataFlow df)`:
     - liest `mms:*`-Properties aus `df.getSource().getProperties()` (host/port/logicalDevice/sensors/pollingInterval),
     - `var proxy = new MMSTcpProxy(mmsHost, mmsPort, 0, logicalDevice, sensors, pollingInterval); proxy.start();`
     - `activeProxies.put(df.getId(), proxy);`
     - `return Result.success(DataAddress.Builder.newInstance().type("MmsTcp")
         .property("host", hostname).property("port", ""+proxy.getLocalPort())
         .property("url", "tcp://"+hostname+":"+proxy.getLocalPort())
         .property("mms:logicalDevice", logicalDevice).property("mms:sensors", sensors).build());`
     - Fehler (IOException) → `Result.failure(...)`.
   - `revokeEndpointDataReference(String flowId, String reason)`:
     - `var p = activeProxies.remove(flowId); if (p!=null) p.stop(); return ServiceResult.success();`
3. **`MmsLightDataSourceFactory.java`** (neu, minimal) — `DataSourceFactory` mit `supportedType()="MmsLight"`,
   `validateRequest()=success`, `createSource()` → wird bei PULL **nie** aufgerufen (nur zur Typbewerbung als
   allowedSourceType nötig; Lehre aus Weg A: sonst verwirft `LegacyDataPlaneSignalingFlowController.transferTypesFor`
   den Typ → Provider 400). Kann eine `StreamResult.error(...)` / no-op-Source zurückgeben.
4. **`WindxMmsLightDataPlaneExtension.java`** (neu) oder Erweiterung der bestehenden `WindxMmsDataPlaneExtension`:
   - `@Inject PipelineService pipelineService; @Inject EndpointDataReferenceServiceRegistry edrServiceRegistry;
     @Inject Hostname hostname;`
   - `initialize`:
     ```java
     pipelineService.registerFactory(new MmsLightDataSourceFactory());                 // allowedSourceType "MmsLight"
     var edr = new MmsLightEndpointDataReferenceService(hostname.get(), monitor);
     edrServiceRegistry.register("MmsLight", edr);                                      // → "MmsLight-PULL"
     // KEIN addGeneratorFunction: unsere EDR-Impl baut den Endpoint selbst.
     ```
   - **Kein** `PublicEndpointGeneratorService` nötig (im Gegensatz zu mms-PULL) — die eigene EDR-Impl liefert die
     DataAddress direkt. `authorizationService` wird hier nicht gebraucht.

### Registrierung im Runtime + settings
- `windx-mms` ist bereits in `edc-controlplane/edc-runtime-windx` und der Test-Runtime
  `runtime-memory-dcp-ih-windx` eingebunden — die neue Extension lädt per ServiceLoader mit
  (`META-INF/services/...ServiceExtension` um den neuen Extension-Klassennamen ergänzen).

---

## 2. Datenfluss (Ziel)

```
Consumer (MMS-Client)  ──TCP──►  MmsTcpProxy(:freierPort)  ──MMS/poll──►  echter MMS-Server (agilevt-dev:102)
        ▲  EDR: tcp://host:port          (in unserer Data Plane)
        │
Provider-DCP: MmsLight-PULL  →  DataPlaneManagerImpl.handlePull  →  edrRegistry.create(df, source[type=MmsLight])
              →  MmsLightEndpointDataReferenceService.createEndpointDataReference  →  Proxy start + tcp://-EDR
Terminate/Suspend  →  DataPlaneManagerImpl.stop  →  edrRegistry.revoke(df)  →  revokeEndpointDataReference → Proxy stop
```

---

## 3. Teststrategie (dank erreichbarem Server echt E2E)

### T1 — Closed-Loop-Hardware-IT (Kern, `@Tag("MmsHardwareIT")`) — *der wichtigste Test*
Ohne DCP-Overhead, prüft den kompletten Tunnel:
1. `var edr = new MmsLightEndpointDataReferenceService("localhost", monitor);`
2. Baue `DataFlow` (oder rufe direkt mit einem Test-`DataFlow`-Objekt) mit Source-`DataAddress` type `MmsLight`,
   `mms:serverHost=agilevt-dev.ikap.biba.uni-bremen.de`, port 102, WPPS, `LCCS1$ST$Mod$stVal`, pollingInterval 1000.
3. `var addr = edr.createEndpointDataReference(df).getContent();` → lies `port` aus der `MmsTcp`-DataAddress.
4. **Consumer:** `var consumer = new MMSClient("localhost", port); consumer.connect();` (COTP+Associate gegen den
   Proxy), dann `consumer.readVariable("WPPS","LCCS1$ST$Mod$stVal", 0x30)` bzw. Listener → **assert Wert == "1"**
   (der Proxy antwortet mit gecachter, umgepackter Read-Response / pusht InformationReports).
5. `edr.revokeEndpointDataReference(df.getId(), "test")` → assert Proxy gestoppt (Port zu).

> Baut auf `DataFlow.Builder` (data-plane-spi). Falls `DataFlow` schwer testweise baubar ist: `MMSTcpProxy` direkt
> instanziieren (`new MMSTcpProxy(host,102,0,"WPPS","LCCS1$ST$Mod$stVal",1000); start();`) und Schritt 4/5 identisch
> — testet denselben Kern, nur ohne die EDR-Wrapper-Schicht.

### T2 — DCP-Advertising-Test (`@EndToEndTest`, analog `mmsAsset_offersMmsPullAndHttpDataPush`)
`MmsLight`-Asset anlegen → `CONSUMER.getCatalogDatasets(PROVIDER)` → Dataset bietet **`MmsLight-PULL`**.
Kein Server nötig (nur Katalog). Sichert die 2 Registrierungen + korrekte Typbewerbung.

### T3 (optional/später) — voller DCP-PULL-Transfer
Consumer verhandelt `MmsLight-PULL`, zieht die EDR (`GET …/edrs/{id}/dataaddress` → `tcp://…`), öffnet TCP.
Braucht Consumer-seitige TCP-Logik im Test → aufwändiger; T1 deckt den Tunnel bereits real ab. Zurückstellen.

### Regression
`MediatorConsumerPullTest` (GET/PUT/mms-Advertising) muss grün bleiben; `MMSAggregationCrawlerTest` + neue Tests dazu.

---

## 4. Risiken & Gegenmaßnahmen (Umsetzung)
- **Typ-Symmetrie** (s. §0): durchgängig `MmsLight` → gelöst.
- **Port pro Flow:** `ServerSocket(0)` statt fester Port → mehrere Transfers parallel möglich.
- **Token-lose EDR:** `MmsTcp`-DataAddress ohne Secure-Token (BIBA-treu; der TCP-MMS-Kanal kennt kein Bearer-Token).
  Prüfen, dass der EDR-Consumer-Flow (`edrs/{id}/dataaddress`) eine solche DataAddress unverändert ausliefert.
- **Proxy-Lifecycle bei Runtime-Stop:** optional `closeAll` beim Extension-`shutdown()` (alle activeProxies stoppen).
- **Vendored-Rohcode:** `MMSTcpProxy` bleibt unter dem modulweiten Checkstyle-Off; neue Klassen sauber halten.

---

## 5. Reihenfolge der Umsetzung
1. `MMSTcpProxy` portieren (+ `ServerSocket(0)`-Overload) → compileJava grün.
2. `MmsLightDataSourceFactory` + `MmsLightEndpointDataReferenceService` schreiben.
3. Extension + `META-INF/services` → Boot-Smoke (Log „MmsLight-PULL beworben").
4. T1 (Closed-Loop-Hardware-IT) → grün gegen echten Server.
5. T2 (DCP-Advertising) → grün; Gesamt-Regression.
6. Doku (`phase-mms-light-*.md`) + Memory finalisieren.

## 6. ✅ Umgesetzt & grün (2026-07-07)

**Neue/portierte Dateien in `edc-extensions/windx/windx-mms`:**
- `client/MMSTcpProxy.java` — 1:1 aus BIBA portiert (Package/Header, `public class` + Konstruktor/`start`/`stop`/
  `getLocalPort` public; Proxy-Port `0` → freier Port pro Flow). Roher TPKT/COTP/ASN.1-Server + Polling + Push.
- `MmsLightEndpointDataReferenceService.java` — implementiert `EndpointDataReferenceService`; `create()` startet den
  Proxy (liest `mms:serverHost/serverPort/logicalDevice/sensors/pollingInterval` aus der Source-DataAddress) und gibt
  eine `MmsTcp`-DataAddress (`tcp://host:port`) zurück, `revoke()` stoppt ihn (keyed by `dataFlow.getId()`), `stopAll()`
  für Shutdown.
- `MmsLightDataSourceFactory.java` — minimale Source-Factory (`supportedType()="MmsLight"`), nur Typbewerbung.
- `WindxMmsLightDataPlaneExtension.java` — `edrServiceRegistry.register("MmsLight", edr)` +
  `pipelineService.registerFactory(new MmsLightDataSourceFactory())`; `shutdown()`→`stopAll()`. In `META-INF/services`
  ergänzt. Durchgängiger Typ `MmsLight` (Source==Key==destType), Transfer-Typ `MmsLight-PULL`.

**Verifikation (grün):**
- `:edc-extensions:windx:windx-mms:compileJava` ✅.
- **T1 `MmsLightTunnelIT.tunnelsNativeMmsFromRealServerToConsumer`** (`@Tag("MmsHardwareIT")`) — Closed-Loop gegen den
  echten Server: `create()` startet den Proxy → dynamischer TCP-Port; ein zweiter `MMSClient` verbindet sich nativ
  (COTP+Associate) und empfängt einen getunnelten InformationReport mit **echten** Serverdaten
  (`WPPS/LCCS1$ST$Mod$stVal = 1`, echter Timestamp); `revoke()` stoppt den Proxy. `PASSED`.
- **T2 `MediatorConsumerPullTest.mmsLightAsset_offersMmsLightPull`** (`@EndToEndTest`) — DCP-Katalog bietet
  `MmsLight-PULL` für ein `MmsLight`-Asset. Suite grün: **4 Tests (GET, PUT, mms-Advertising, mms-light-Advertising)**.

**Offen/Folgearbeit:** voller DCP-PULL-Transfer-Test (T3, Consumer-TCP-Logik) zurückgestellt — T1 deckt den Tunnel real
ab.

**✅ Phase-4a-mms-Bug behoben (2026-07-07)** (der in §0 entdeckte Registry-Key ≠ Source-Typ): mms-Source-Typ von
`mms` auf **`Mms`** vereinheitlicht (Asset-`dataAddress.type` == EDR-Key == destType == `Mms`; Transfer-Typ bleibt
`Mms-PULL`; Property-Keys bleiben `mms:*`). Geändert: `WindxMmsDataPlaneExtension.MMS_SOURCE_TYPE`,
`MMSDataSourceFactory.supportedType()` (an Konstante gekoppelt), Testasset-Typen. **Neuer Beweis-Test**
`MediatorConsumerPullTest.mmsPull_reachesStarted_edrCreated`: echter `Mms-PULL`-Transfer erreicht `STARTED` + EDR
erzeugt (vorher `TERMINATED`/`No EDR service with type mms found`) — kein MMS-Server nötig (EDR = statische
Public-API-URL). Suite: **5 Tests, 5 passed**.

## Quellen (verifiziert)
- 0.17: `EndpointDataReferenceService(+Registry)`, `EndpointDataReferenceServiceRegistryImpl` (create=source-typ,
  revoke=destType), `DataFlow` (getId/getSource/getTransferType), `TransferType` (destType-`FlowType` split),
  `DataPlaneManagerImpl` (handlePull/stop).
- BIBA: `mmsLight/{MMSLightExtension,MMSLightDataFlowController,MMSTcpProxy}`.
- Unser Repo: `WindxMmsDataPlaneExtension`, `windx-mediator-proxy` (Typ-Symmetrie-Vergleich),
  `MediatorConsumerPullTest` (Testmuster), `MmsHardwareConnectivityIT` (Hardware-IT-Muster).
