# Wind-X Dataspace auf KinD (DID-Identität, DSP 2025)

Zwei `edc-runtime-windx`-Connectoren (Provider + Consumer) + eigener IdentityHub im lokalen KinD-Cluster
`kind-mvd`, Namespace `windx` — **neben** dem unangetasteten MVD. Rein **DID-basiert** über **DSP 2025**.
Verifiziert bis zum echten Datentransfer (Consumer-Pull, HttpData). Hintergrund & Entscheidungen:
[../../docs/windx/phase-cluster-kind-ergebnisse.md](../../docs/windx/phase-cluster-kind-ergebnisse.md).

> **⚠️ Nur für lokale Entwicklung.** Die Geheimnisse stehen seit WP4 **nicht mehr im YAML** — sie werden
> von [`windx-secrets.sh`](windx-secrets.sh) erzeugt (Krypto-Material frisch generiert, API-Keys als
> Dev-Default `password`). Ein Wert im committeten YAML wäre Klartext in Git; base64 ist keine
> Verschlüsselung. Für echten Betrieb fehlt weiterhin echtes Secret-Management (SealedSecrets/SOPS/Vault)
> und Rotation — die Dev-API-Keys sind bewusst schwach, damit Demo und Skripte laufen.

---

## Inhalt

| Datei | Zweck |
|---|---|
| `windx-stack.yaml` | Namespace, ConfigMaps, IdentityHub, Setup-Job, Provider + Consumer (Deployments/Services) |
| `windx-postgres.yaml` | **PostgreSQL** (Deployment/Service/**PVC**) + DBs `provider`/`consumer`/`ihub` — die Persistenz-Basis |
| `windx-secrets.sh` | Erzeugt alle statischen Geheimnisse als k8s-Secrets (stehen bewusst nicht im YAML) |
| `windx-seed.sh` | Provider seeden (Asset + permissive Policy + ContractDef) + Katalog + Negotiation bis `FINALIZED` |
| `transfer.sh` | Stufe 4: `/edrs` → EDR → Daten pullen (mit Gegenprobe ohne Token) |
| `windx-demo.sh` | **Demonstrator 1:** die vier Schritte oben als eine erzählte Terminal-Vorführung (Story- + Technik-Ebene) |
| `run-demo.sh` / `run-demo.ps1` | Host-Launcher für `windx-demo.sh` (Bash bzw. PowerShell; Preflight-Checks + Pod-Start) |
| `windx-dashboard.py` | **Demonstrator 2:** derselbe Fluss als Web-Dashboard (Browser, live aufleuchtende Stufen) |

Drei Runtime-Secrets werden **nicht** aus der YAML angelegt (weil dynamisch), sondern per Kommando (s. u.):
`windx-sts-secrets`, `windx-edr-keys`, `windx-provider-vault-seed`.

---

## Voraussetzungen

- KinD-Cluster `mvd` läuft; Kontext `kind-mvd`. `export KUBECONFIG=C:/Users/luf/Desktop/wind-x/kubernetes/.kube`
- Diese Images lokal vorhanden → per `kind load` in den Cluster:
  ```sh
  kind load docker-image edc-runtime-windx-postgresql:0.13.0-SNAPSHOT --name mvd
  kind load docker-image lufbiba/fma-identityhub:0.17 --name mvd
  kind load docker-image reg.biba.uni-bremen.de/wind-x/luf-bibas-connector:jira132-es256k --name mvd
  ```
  (`edc-runtime-windx-postgresql` via `./gradlew :edc-controlplane:edc-runtime-windx-postgresql:dockerize`.)
  Das ist die **persistente** Runtime: gleiche windx-Extensions wie `edc-runtime-windx`, aber mit SQL-Stores.
  Die reine In-Memory-Variante `edc-runtime-windx` bleibt für schnelle Demos erhalten.

---

## Deploy (von Grund auf)

```sh
CTX="--context kind-mvd"; NS="-n windx"

# 0) PostgreSQL + Geheimnisse. windx-secrets.sh ist idempotent (vorhandene Secrets bleiben
#    unangetastet); es generiert das Krypto-Material und setzt die Dev-API-Keys auf `password`.
kubectl $CTX apply -f windx-postgres.yaml
kubectl $CTX $NS rollout status deploy/windx-postgres --timeout=120s
./windx-secrets.sh

# 1) Stack ausrollen (Provider/Consumer bleiben Init, bis ihre Vault-Seeds existieren)
kubectl $CTX apply -f windx-stack.yaml

# 2) IdentityHub + Setup-Job abwarten; Setup legt Participants an und gibt die STS-Secrets ins Log
kubectl $CTX $NS rollout status deploy/windx-identityhub --timeout=180s
kubectl $CTX $NS wait --for=condition=complete job/windx-setup --timeout=240s
kubectl $CTX $NS logs job/windx-setup | grep BIBAS_OUTPUT
#   -> provider_sts_client_secret=XXXX   consumer_sts_client_secret=YYYY

# 3) STS-Secret anlegen (Consumer nutzt es direkt via assemble-config)
kubectl $CTX $NS create secret generic windx-sts-secrets \
    --from-literal=provider=XXXX --from-literal=consumer=YYYY

# 4) EDR-Signing-Keypair erzeugen (P-256/ES256) -- nur der Provider signiert beim Consumer-Pull
openssl ecparam -name prime256v1 -genkey -noout -out ec.pem
openssl pkcs8 -topk8 -nocrypt -in ec.pem -out priv.pem
openssl ec -in ec.pem -pubout -out pub.pem

# 5) Provider-Vault-Seed bauen: STS-Secret + echte PEM-Keys in EINEN String (echte Zeilenumbrueche!)
#    -> als Env-Var TX_EDC_VAULT_SECRETS (EDCs kanonischer Weg; Env behaelt Zeilenumbrueche).
printf 'provider-sts-client-secret:%s;signer-private:%s;verifier-public:%s' \
    "XXXX" "$(cat priv.pem)" "$(cat pub.pem)" > provseed.txt
kubectl $CTX $NS create secret generic windx-provider-vault-seed --from-file=seed=provseed.txt
#    (der Consumer behaelt Dummy-Keys via assemble-config -- er signiert in dieser Richtung nicht)

# 6) Provider/Consumer neu starten, damit sie ihre Seeds ziehen
kubectl $CTX $NS rollout restart deploy/windx-provider deploy/windx-consumer
kubectl $CTX $NS rollout status deploy/windx-provider --timeout=180s
```

Boot-Check (Provider): `seeded 3 secret(s)` (STS + signer + verifier), `2 BPN/DID mapping(s)`, `Runtime ... ready`.

---

## Walkthrough (Katalog → Negotiation → Transfer)

```sh
run() { B64=$(base64 -w0 "$1"); kubectl --context kind-mvd -n windx run t --rm -i --restart=Never \
        --image=curlimages/curl:8.10.1 --command -- sh -c "echo $B64 | base64 -d | sh"; }

run windx-seed.sh    # -> ... FINALIZED. agreementId=...
run transfer.sh      # -> MIT Token: {"isSystemHealthy":true} [HTTP 200] ; OHNE Token: [HTTP 401]
```

---

## Demonstrator (narrativer Walkthrough)

`windx-demo.sh` fasst Seed → Katalog → Negotiation → Transfer zu **einer** durchgängigen
Vorführung in 4 Akten zusammen. Höhepunkt ist der Tor-Kontrast: dieselbe Datenadresse liefert
**mit** Vertrag HTTP 200, **ohne** Vertrag HTTP 401. Für gemischtes Publikum gibt es zwei
Ebenen — eine Story-Ebene (Klartext) und eine Technik-Ebene (echte Requests/IDs/HTTP-Codes).

```sh
deployment/windx-kind/run-demo.sh                 # volle Demo (Story + Technik)
STORY_ONLY=1 deployment/windx-kind/run-demo.sh    # nur die Story-Ebene (Nicht-Techniker)
PAUSE=4      deployment/windx-kind/run-demo.sh     # längere Pausen zum Mitmoderieren
```

Voraussetzung wie bei `windx-seed.sh`/`transfer.sh` (Stack deployt, STS-Secrets, EDR-Keys).
Fehlen die EDR-Signing-Keys, laufen die Akte 1–3 trotzdem; Akt 4 bricht vor dem Datenabruf
sauber ab. Der Launcher warnt im Preflight, wenn ein Secret fehlt.

### Demonstrator 2 — Web-Dashboard

`windx-dashboard.py` zeigt denselben Fluss im Browser: vier Stufen, die live grün aufleuchten,
mit dem 200/401-Tor-Kontrast als Höhepunkt. Da der Cluster kein Ingress hat, öffnet das Skript
selbst drei `kubectl port-forward` (Consumer-Mgmt, Provider-Mgmt, Provider-Public), fährt den
Ablauf host-seitig und streamt jeden Schritt per Server-Sent-Events an die Seite. Nur
Python-Standardbibliothek — kein `pip` nötig.

```sh
python deployment/windx-kind/windx-dashboard.py     # öffnet http://localhost:8088 im Browser
```

Steuerung im Browser: **„Demo starten"**-Button, Umschalter **„Technik-Details"** (Story- vs.
Technik-Ebene, wie bei Demonstrator 1). Env-Optionen: `NO_BROWSER=1` (Fenster nicht öffnen),
`ONESHOT=1` (Server nach einem Durchlauf beenden, für CI), `DASH_PORT=<port>`. Strg+C beendet
und räumt die Port-Forwards auf.

**UI-Vorschau ohne Cluster:** `http://localhost:8088/?preview` spielt den Ablauf mit
Beispieldaten ab — praktisch zum Zeigen der Oberfläche oder für Screenshots.

---

## Betriebsregeln (wichtig — hier steckt viel Debugging drin)

- **Connectoren sind persistent, der IdentityHub noch nicht.** Stand der Persistenz-Umstellung:
  - **Provider-/Consumer-Roll** → **unkritisch**. Assets, Policies, ContractDefs, Negotiations, Agreements
    und der EDR-Cache liegen in PostgreSQL (DBs `provider`/`consumer`) und überleben den Neustart.
    Kein erneutes `windx-seed.sh` nötig.
  - **IdentityHub-Roll** (z. B. Host über Nacht suspendiert → Liveness-Probe) → Participants + STS-Accounts
    weg → **Setup-Job erneut** laufen lassen, **neue** STS-Secrets ziehen, `windx-sts-secrets` **und**
    `windx-provider-vault-seed` neu bauen, Connectoren neu starten. Symptom sonst: STS `401 invalid_client`.
    *Pod-Alter täuscht* — auf `restartCount` schauen, nicht auf das Alter.
    Das behebt erst WP3 (IH auf SQL-Stores); aktuell blockiert, weil sich der `fma-IdentityHub`
    nicht mehr bauen lässt (0.18-SNAPSHOT-Drift — Details im Projekt-Memory).
- **ConfigMap-Änderung rollt den Pod nicht.** Nach einer ConfigMap-Änderung explizit
  `kubectl rollout restart deploy/windx-provider` (sonst kopiert der `assemble-config`-Init-Container die alte
  Version weiter).
- **DEBUG-Logs** brauchen das Programm-Argument `--log-level=DEBUG` (bereits in der YAML) — EDCs ConsoleMonitor
  filtert vor Log4j2, über die Config kommt man nicht an DEBUG.
- **Immer DSP 2025** verwenden: Protokoll `dataspace-protocol-http:2025-1`, Pfad `.../protocol/2025-1`.
  DSP 0.8 verlangt eine BPN im MembershipCredential (haben wir nicht) → 401.
- **Public-Endpoint:** `edc.dataplane.api.public.baseurl` muss auf den Service-Namen zeigen
  (`http://windx-provider:19293/api/public`), sonst bewirbt die EDR `localhost` → Pull nicht erreichbar.
