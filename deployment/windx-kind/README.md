# Wind-X Dataspace auf KinD (DID-Identität, DSP 2025)

Zwei `edc-runtime-windx`-Connectoren (Provider + Consumer) + eigener IdentityHub im lokalen KinD-Cluster
`kind-mvd`, Namespace `windx` — **neben** dem unangetasteten MVD. Rein **DID-basiert** über **DSP 2025**.
Verifiziert bis zum echten Datentransfer (Consumer-Pull, HttpData). Hintergrund & Entscheidungen:
[../../docs/windx/phase-cluster-kind-ergebnisse.md](../../docs/windx/phase-cluster-kind-ergebnisse.md).

> **⚠️ Nur für lokale Entwicklung.** `windx-stack.yaml` enthält **Wegwerf-Dev-Keys** im Klartext
> (Superuser-API-Key, IdentityHub-STS-Seed-Key) — identisch zum `bibas`-Stack. Nicht für Produktion;
> für einen echten Betrieb externalisieren (Vault/Sealed-Secrets) und rotieren. Das **EDR-Signing-Keypair**
> (Stufe 4) liegt bewusst **nicht** in der YAML, sondern in einem zur Laufzeit erzeugten k8s-Secret.

---

## Inhalt

| Datei | Zweck |
|---|---|
| `windx-stack.yaml` | Namespace, ConfigMaps, IdentityHub, Setup-Job, Provider + Consumer (Deployments/Services) |
| `windx-seed.sh` | Provider seeden (Asset + permissive Policy + ContractDef) + Katalog + Negotiation bis `FINALIZED` |
| `transfer.sh` | Stufe 4: `/edrs` → EDR → Daten pullen (mit Gegenprobe ohne Token) |

Drei Runtime-Secrets werden **nicht** aus der YAML angelegt (weil dynamisch), sondern per Kommando (s. u.):
`windx-sts-secrets`, `windx-edr-keys`, `windx-provider-vault-seed`.

---

## Voraussetzungen

- KinD-Cluster `mvd` läuft; Kontext `kind-mvd`. `export KUBECONFIG=C:/Users/luf/Desktop/wind-x/kubernetes/.kube`
- Diese Images lokal vorhanden → per `kind load` in den Cluster:
  ```sh
  kind load docker-image edc-runtime-windx:0.13.0-SNAPSHOT --name mvd
  kind load docker-image lufbiba/fma-identityhub:0.17 --name mvd
  kind load docker-image reg.biba.uni-bremen.de/wind-x/luf-bibas-connector:jira132-es256k --name mvd
  ```
  (`edc-runtime-windx` via `./gradlew :edc-controlplane:edc-runtime-windx:dockerize`.)

---

## Deploy (von Grund auf)

```sh
CTX="--context kind-mvd"; NS="-n windx"

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

## Betriebsregeln (wichtig — hier steckt viel Debugging drin)

- **IdentityHub + Connectoren sind rein in-memory.** Jeder Container-/Pod-Neustart wischt den Zustand:
  - **Provider-Roll** → Asset/Policy/ContractDef weg → `windx-seed.sh` erneut laufen lassen.
  - **IdentityHub-Roll** (z. B. Host über Nacht suspendiert → Liveness-Probe) → Participants + STS-Accounts
    weg → **Setup-Job erneut** laufen lassen, **neue** STS-Secrets ziehen, `windx-sts-secrets` **und**
    `windx-provider-vault-seed` neu bauen, Connectoren neu starten. Symptom sonst: STS `401 invalid_client`.
    *Pod-Alter täuscht* — auf `restartCount` schauen, nicht auf das Alter.
- **ConfigMap-Änderung rollt den Pod nicht.** Nach einer ConfigMap-Änderung explizit
  `kubectl rollout restart deploy/windx-provider` (sonst kopiert der `assemble-config`-Init-Container die alte
  Version weiter).
- **DEBUG-Logs** brauchen das Programm-Argument `--log-level=DEBUG` (bereits in der YAML) — EDCs ConsoleMonitor
  filtert vor Log4j2, über die Config kommt man nicht an DEBUG.
- **Immer DSP 2025** verwenden: Protokoll `dataspace-protocol-http:2025-1`, Pfad `.../protocol/2025-1`.
  DSP 0.8 verlangt eine BPN im MembershipCredential (haben wir nicht) → 401.
- **Public-Endpoint:** `edc.dataplane.api.public.baseurl` muss auf den Service-Namen zeigen
  (`http://windx-provider:19293/api/public`), sonst bewirbt die EDR `localhost` → Pull nicht erreichbar.
