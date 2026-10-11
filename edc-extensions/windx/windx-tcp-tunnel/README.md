# windx-tcp-tunnel

Protokollneutraler **TCP-über-WebSocket-Tunnel** durch die Provider-Data-Plane. Damit lassen sich OPC UA
(`opc.tcp`), MQTT, MMS/IEC 61850 oder jedes andere TCP-Protokoll über einen Dataspace-Vertrag freigeben, ohne dass
das Zielsystem selbst aus dem Internet erreichbar sein muss.

```text
Consumer                                              Provider
┌────────────┐ tcp  ┌───────────┐  wss + Authorization: <EDR-Token>  ┌──────────────┐ tcp  ┌────────────┐
│ OPC/MQTT/  │────► │ Agent     │ ══════════════════════════════════►│ Data Plane   │────► │ Ziel       │
│ MMS-Client │      │ (websocat)│                                     │ /api/tunnel  │      │ host:port  │
└────────────┘      └───────────┘                                     └──────────────┘      └────────────┘
```

Die Data Plane versteht das getunnelte Protokoll nicht, sie kopiert nur Bytes. Authentifizierung und Bindung an den
Vertrag laufen über das normale EDR-Token der Data Plane, genau wie bei `HttpData-PULL`.

## Ablauf

1. Der Provider legt ein Asset mit `dataAddress.type = "TcpTunnel"` an. Das Asset nennt das Ziel (`host`, `port`).
2. Der Consumer verhandelt einen Vertrag und startet einen Transfer mit `transferType = "TcpTunnel-PULL"`.
3. Der EDR enthält `endpoint` (z. B. `wss://dataplane.provider.example/api/tunnel`) und `authorization`.
4. Der Consumer öffnet eine WebSocket-Verbindung zu `endpoint` und schickt dabei `Authorization: <token>` mit.
   Das Präfix `Bearer ` ist optional.
5. Die Data Plane prüft das Token, den Zustand des Flows und das Verbindungslimit. Danach öffnet sie die
   TCP-Verbindung zum Ziel und leitet die Bytes in beide Richtungen weiter (binäre WebSocket-Messages).
6. Wird der Transfer beendet (Terminate oder Suspend), schließt die Data Plane alle offenen Tunnel des Flows mit
   Close-Code `1008`. Ein Watchdog prüft zusätzlich periodisch den Flow-Zustand im `DataPlaneStore`. Er greift auch
   dann, wenn der Widerruf eine andere Data-Plane-Replik erreicht hat.

Wenn das Token abläuft, bleiben bestehende Verbindungen offen. Für jede **neue** Verbindung braucht der Consumer
aber ein gültiges Token (Token-Refresh über den Consumer-Connector).

## Asset

```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "opcua-turbine-17",
  "properties": { "name": "OPC UA Server WEA 17" },
  "dataAddress": {
    "type": "TcpTunnel",
    "host": "opcua.windpark.internal",
    "port": "4840",
    "maxConnections": "2"
  }
}
```

| Property         | Pflicht | Bedeutung                                                                      |
|------------------|---------|--------------------------------------------------------------------------------|
| `host`           | ja      | Ziel-Host aus Sicht der Provider-Data-Plane                                    |
| `port`           | ja      | Ziel-Port                                                                      |
| `maxConnections` | nein    | max. gleichzeitige Tunnel pro Transfer (Default aus `edc.dataplane.windx.tunnel.max.connections`) |

## Consumer-Agent

Empfohlen ist der mitgelieferte Agent [`agent/windx_tunnel_agent.py`](agent/windx_tunnel_agent.py). Er stellt den
Tunnel als lokalen TCP-Port bereit und holt **für jede neue Verbindung** den aktuellen EDR bei der Management-API des
eigenen Connectors (`/v3/edrs/{transferId}/dataaddress?auto_refresh=true`). Abgelaufene Tokens erneuert damit der
Connector, und der Agent muss nie neu gestartet werden.

```bash
pip install -r agent/requirements.txt
export WINDX_MGMT_API_KEY=...        # API-Key der eigenen Management-API
python3 agent/windx_tunnel_agent.py --listen 127.0.0.1:4840 \
  --management-url https://consumer.example/management --transfer-id <transferProcessId>
# OPC-UA-Client -> opc.tcp://127.0.0.1:4840, MQTT-Client -> 127.0.0.1:<port> usw.
```

Beendet der Provider den Tunnel (Transfer terminiert), loggt der Agent den Close-Code. Neue Verbindungen lehnt der
Provider dann mit HTTP 403 ab.

Für einen schnellen Test geht auch [websocat](https://github.com/vi/websocat). Das Token wird dabei nur einmal beim
Start gelesen und läuft nach `tx-auth:expiresIn` Sekunden ab (bei Tractus-X 300 s):

```bash
websocat -b tcp-l:127.0.0.1:4840 "$ENDPOINT" -H="Authorization: $TOKEN"   # -H= mit Gleichheitszeichen
```

## Protokoll-Hinweise

- **OPC UA:** Der Client sieht nur die lokale Tunnel-Adresse. Zwei Dinge weichen deshalb von der Server-Identität ab:
  1. Die Endpoint-URL aus `GetEndpoints` (z. B. `opc.tcp://dave-opcua:50000/`). Der Client muss die lokale Adresse
     behalten ("Endpoint-URL/Hostname-Abweichung ignorieren").
  2. Die `ServerUri` in `CreateSession`: Manche Clients bilden sie aus dem Hostnamen (z. B. asyncua: `urn:127.0.0.1`),
     und der Server lehnt dann mit `BadServerUriInvalid` ab. Abhilfe: die ApplicationUri aus `GetEndpoints`
     übernehmen.

  Mit OPC-UA-Security (Sign/SignAndEncrypt) muss außerdem das Server-Zertifikat zum Hostnamen passen oder die
  Prüfung im Client gelockert werden. Getestet live mit dem OPC PLC Simulator: Browse, Read, Subscription und Write.
- **MQTT:** funktioniert ohne Besonderheiten. Live getestet mit Mosquitto (pub/sub).
- **Datenbanken** (PostgreSQL, MySQL/MariaDB, MS SQL, Oracle), noch nicht live getestet: Die Wire-Protokolle sind
  normales TCP und laufen durch. Die Anmeldung an der Datenbank bleibt Sache der Datenbank. Für Abfragen auf Legacy-Systeme einen eigenen
  Benutzer mit reinem Leserecht auf freigegebene Views anlegen. Ausnahmen:
  - Oracle mit Port-Redirect (Shared Server, ältere Windows-Listener)
  - MS SQL mit benannter Instanz (SQL Browser über UDP 1434): stattdessen den festen Port der Instanz nutzen

## Settings (Data Plane)

| Key                                                     | Default                          | Bedeutung                                         |
|---------------------------------------------------------|----------------------------------|---------------------------------------------------|
| `web.http.tunnel.port`                                  | `8088`                           | Port des Tunnel-Kontexts                          |
| `web.http.tunnel.path`                                  | `/api/tunnel`                    | Pfad des Tunnel-Kontexts                          |
| `edc.dataplane.windx.tunnel.endpoint`                   | `ws://<hostname>:<port><path>`   | Endpoint, der in den EDR geschrieben wird (extern: `wss://…`) |
| `edc.dataplane.windx.tunnel.max.connections`            | `4`                              | Default-Limit gleichzeitiger Tunnel pro Transfer  |
| `edc.dataplane.windx.tunnel.connect.timeout.ms`         | `5000`                           | Timeout für den Verbindungsaufbau zum Ziel        |
| `edc.dataplane.windx.tunnel.idle.timeout.seconds`       | `600`                            | Idle-Timeout einer Tunnel-Verbindung              |
| `edc.dataplane.windx.tunnel.watchdog.interval.seconds`  | `30`                             | Intervall für Keep-alive-Pings und Flow-Watchdog  |

## Deployment (Kubernetes)

- Service- und Ingress-Regel für den Port `8088` anlegen. TLS terminiert am Ingress. Der EDR-Endpoint muss dann
  `wss://…` sein.
- ingress-nginx unterstützt WebSockets ohne weitere Konfiguration. Der Default-Timeout von 60 s muss aber
  hochgesetzt werden:

  ```yaml
  nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"
  nginx.ingress.kubernetes.io/proxy-send-timeout: "3600"
  ```

  Die Keep-alive-Pings im Intervall `edc.dataplane.windx.tunnel.watchdog.interval.seconds` halten ruhige
  Verbindungen zusätzlich offen.
- Die Data Plane braucht Netzwerkzugriff auf die Ziele. Eine NetworkPolicy oder Egress-Regel sollte das auf die
  tatsächlich freigegebenen Ziele beschränken.

## Grenzen und Sicherheit

- **Vollzugriff:** Der Tunnel kann nicht nur lesen, er kann auch schreiben und steuern, z. B. MMS-Control oder
  OPC-UA-Write. Ein Vertrag, der nur Lesezugriff erlaubt, lässt sich so technisch nicht durchsetzen. Für reinen
  Lesezugriff sollte das Asset auf einen lesenden Gateway oder Aggregationsserver zeigen statt auf das Gerät selbst.
- **Ziel-Hosts:** Wer Assets anlegen darf, bestimmt die Ziele. Die Management-API muss also geschützt sein. Eine
  Allowlist für Ziele ist noch nicht umgesetzt.
- **OPC UA:** Der Server meldet bei `GetEndpoints` seine interne Endpoint-URL. Der Client muss sie überschreiben
  können ("Endpoint-URL ignorieren"). Die OPC-UA-eigene Sicherheit (SecureChannel, Benutzer) läuft unverändert
  Ende-zu-Ende durch den Tunnel.
- **IEDs:** Geräte erlauben oft nur wenige gleichzeitige Verbindungen. `maxConnections` deshalb klein halten.
- **Mehrere Data-Plane-Repliken:** Der Widerruf schließt die Tunnel sofort auf der Replik, die ihn empfängt. Auf
  anderen Repliken schließt der Watchdog sie spätestens nach einem Intervall.

## Tests

- `TcpTunnelServletTest`: echter EDC-`JettyService` + `java.net.http`-WebSocket-Client + TCP-Echo-Server
  (Daten in beide Richtungen, 401/403/429, Widerruf, Watchdog, Ziel schließt).
- `TcpTunnelConsumerPullTest` (E2E, `edc-tests/e2e/dcp-tests`): Katalog → Negotiation → Transfer → EDR →
  Tunnel → Terminate schließt den Tunnel, über echtes DCP.

```bash
./gradlew :edc-extensions:windx:windx-tcp-tunnel:test
./gradlew -p edc-tests/e2e/dcp-tests test -DincludeTags="EndToEndTest" --tests "*TcpTunnelConsumerPullTest*"
```
