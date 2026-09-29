# Wind-X-Teilnehmer im SWMS-Datenraum — Docker Compose

Ein vollständiger Datenraum-Teilnehmer (Connector, IdentityHub, Vault) für einen einzelnen
Server mit Docker Compose, ohne Kubernetes. Die Umgebungsvariablen von Control-Plane, Data-Plane und IdentityHub sind 1:1 aus den
gerenderten Charts `tractusx-connector 0.12.1` und `tractusx-identityhub v0.3.2` übernommen.

## Dienste

| Dienst | Aufgabe |
|---|---|
| `secrets` | erzeugt beim ersten Start die vier Geheimnisse im Volume `secrets` (danach unverändert) |
| `postgres` | Datenbanken `edc` und `ih` (Volume `pgdata`) |
| `vault` | HashiCorp Vault, Dev-Modus (In-Memory) |
| `vault-keeper` | setzt beim ersten Start den Superuser-Key, sichert den Vault alle 30 s auf das Volume `vaultbackup` und spielt ihn nach jedem Neustart zurück |
| `identityhub` | Wallet, DID-Dokument, STS |
| `ih-provision` | legt einmalig die Identität an (Schlüssel, DID-Dokument); idempotent |
| `controlplane`, `dataplane` | Connector (Wind-X-Images `sha-9f724fe`) |
| `proxy` | Caddy: die vier öffentlichen Pfade, optional mit Let's-Encrypt-TLS |

## Start

```sh
cp .env.example .env          # PUBLIC_HOST, BPN, SITE_ADDRESS eintragen -- sonst nichts
docker login ghcr.io          # Token mit read:packages (private Wind-X-Images)
docker compose up -d
docker compose logs ih-provision          # "Participant … angelegt"
curl https://<PUBLIC_HOST>/.well-known/did.json
```

Danach bei SWMS registrieren lassen (BPN, DID `did:web:<PUBLIC_HOST>`, CredentialServiceUrl
`https://<PUBLIC_HOST>/api/credentials/v1/participants/<base64(DID)>`) und **alle drei** Nachweise
ausstellen lassen: MembershipCredential, BpnCredential, DataExchangeGovernanceCredential.

Management-API: nur `127.0.0.1:18081` (per SSH-Tunnel), Header `X-Api-Key`. Den Key zeigt:

```sh
docker compose exec controlplane sh -c 'cat /secrets/mgmt_key'
```

## Geheimnisse

Kein Skript nötig: Der Dienst `secrets` erzeugt beim ersten `docker compose up` `vault_token`,
`mgmt_key`, `superuser_key` und `postgres_password` als Dateien im Volume `secrets` und lässt sie
danach unverändert. Postgres liest sein Passwort per `POSTGRES_PASSWORD_FILE`; IdentityHub,
Control- und Data-Plane starten über `scripts/start-java.sh`, das die Geheimnisse in eine
EDC-Konfigurationsdatei schreibt – sie erscheinen daher nicht als Umgebungsvariablen
(`docker inspect`).

## TLS

- `SITE_ADDRESS=edc.partner.example` — Caddy holt ein Let's-Encrypt-Zertifikat; Ports 80 und 443 müssen öffentlich erreichbar sein.
- `SITE_ADDRESS=http://edc.partner.example` — TLS endet an einem vorgelagerten Proxy, der die vier Pfade mit unverändertem Host-Header an Port `HTTP_PORT` weiterreicht.

## Wichtig

- `.env` enthält keine Geheimnisse mehr, nur fachliche Werte.
- Die Volumes `secrets`, `pgdata` und `vaultbackup` **sind** die Identität — für ein Backup alle drei sichern. `docker compose down -v` löscht sie — danach entsteht beim nächsten Start eine neue Identität (neues Onboarding).
- `PUBLIC_HOST` nie ändern: ein anderer Name ist eine andere DID.

Lokal mit Docker Desktop getestet am 29.09.2026 (Start, Identitätsanlage, öffentliche Pfade,
Management-API, Vault-Neustart, kompletter Neustart). Nicht lokal testbar: Let's-Encrypt-TLS und
Datenraum-Verkehr — beides braucht einen öffentlich auflösbaren Namen.
