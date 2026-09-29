# Onboarding-Test vom eigenen Rechner (Cloudflare Quick Tunnel)

Der Stack aus `../docker-compose.yml` bekommt vorübergehend einen öffentlichen HTTPS-Namen
(`https://<zufall>.trycloudflare.com`) mit gültigem Zertifikat — ohne Konto, DNS oder Portfreigabe.
Damit kann der SWMS-Operator die Identität prüfen und Credentials ausstellen, und Partner können
Katalog, Vertrag und Datenabruf gegen deinen Rechner testen.

## Dateien

| Datei | Rolle |
|---|---|
| `docker-compose.tunnel.yml` | Ergänzung zu `../docker-compose.yml`: Dienst `tunnel` (cloudflared) + eigenes Caddyfile für den Proxy |
| `Caddyfile` | wie `../config/Caddyfile`, setzt aber den Host-Header fest auf den Tunnelnamen |
| `start.sh` | Schritte 1–3: Tunnel starten, Stack mit dem Tunnelnamen starten, DID-Dokument öffentlich prüfen, Werte für das SWMS-UI ausgeben |
| `stop.sh` | Test beenden, Test-Identität (Volumes) löschen |
| `.env.tunnel` | entsteht beim ersten Start, enthält Geheimnisse (gitignored) |

## Ablauf

```sh
docker login ghcr.io                 # einmalig, Token mit read:packages
./start.sh BPNL00000000LT01          # BPN des Test-Teilnehmers
```

Das Skript gibt am Ende BPN, DID und CredentialServiceUrl aus. Diese im SWMS-Operator-UI unter
*Participants → New* eintragen und **MembershipCredential, BpnCredential und
DataExchangeGovernanceCredential** ausstellen. Kontrolle:

```sh
cd .. && docker compose -p edctunnel --env-file tunnel-test/.env.tunnel \
  -f docker-compose.yml -f tunnel-test/docker-compose.tunnel.yml logs identityhub | grep HolderCredentialRequest
```

Management-API: `http://127.0.0.1:18182/management`, Header `X-Api-Key` = `MGMT_KEY` aus `.env.tunnel`.

Ende: `./stop.sh` und den Participant im SWMS-UI entfernen.

## Wichtig

- **Jeder Start erzeugt einen neuen Tunnelnamen und damit eine neue Identität.** `start.sh` löscht
  deshalb die Test-Volumes; der SWMS-Eintrag muss neu angelegt werden.
- Solange der Tunnel läuft, sind die vier Datenraum-Pfade **öffentlich erreichbar**. Die
  Management-API bleibt auf `127.0.0.1`.
- Nur für Tests. Für einen festen Namen: ngrok (feste kostenlose Domain) oder ein Server mit DNS.
- Den Test-Teilnehmer bzw. die Test-BPN vorher mit SWMS abstimmen.
