# KinD-Cluster-Deployment — Ergebnisse (Weg C: DID-Identität)

**Datum:** 2026-07-15
**Scope:** `edc-runtime-windx` als Provider + Consumer im lokalen **KinD** (`kind-mvd`, Namespace `windx`)
betreiben — eigener windx-Stack neben dem unangetasteten MVD.
**Status:** ✅ **Katalog + Contract-Negotiation grün** (`FINALIZED`), rein **DID-basiert über DSP 2025**.
Offen: nur noch der eigentliche Daten-Byte-Transfer (Stufe 4, braucht echte EC-Keys). Verlauf: Scope-Fix
(`cx-dcp`) → BPN-vs-DID-Befund → Entscheidung DID/DSP-2025 → verifiziert bis Vertragsabschluss.

---

## 1. Aufbau

`kubernetes/windx/windx-stack.yaml` — portiert von `kubernetes/bibas/bibas-stack.yaml` (K3s → KinD), mit
getauschtem Connector-Image (`edc-runtime-windx` statt BIBA-Connector). Drei bewusste Abweichungen:

1. **Kein Registry-Push.** Alle drei Images liegen lokal → `kind load docker-image … --name mvd`
   (`edc-runtime-windx:0.13.0-SNAPSHOT`, `lufbiba/fma-identityhub:0.17`, `luf-bibas-connector:jira132-es256k`
   nur für den Setup-Job).
2. **Kein Ingress / kein `*.tx.test`.** DIDs zeigen direkt auf den K8s-Service (`did:web:windx-identityhub:provider`);
   der IdentityHub-Service bekommt zusätzlich **Port 80 → 10100**, damit `did.json` ohne Port-Encoding auflöst.
   Verifiziert: HTTP 200.
3. **Vault-Seeding** über `tx.edc.vault.secrets` (AP1), **BDRS** über `tx.edc.windx.bdrs.mappings` (AP2) — beide
   im Cluster nachweislich aktiv (`seeded 3 secret(s)`, `2 BPN/DID mapping(s) configured`).

---

## 2. Verifiziert: `cx-dcp`-Ausschluss löst den Scope-Namensraum-Konflikt ✅

**Symptom vorher** (Provider-DEBUG-Log):
```
Presentation Query failed: HTTP 403,
"Scope alias MUST be org.eclipse.dspace.dcp.vc.type but was org.eclipse.tractusx.vc.type"
```
Unser Tractus-X-Connector schickte Catena-X-Scopes; der plain-EDC-`fma-IdentityHub` akzeptiert nur den
DSPACE-Namensraum. Der Namensraum ist eine Compile-Zeit-Konstante (`spi/core-spi/.../TxDcpConstants.java:28`),
nicht per Config übersteuerbar.

**Fix:** `cx-dcp` in `edc-controlplane/edc-runtime-windx/build.gradle.kts` per `exclude` aus
`edc-controlplane-base` entfernt (gleiches Muster wie `bdrs-client` in AP2). Danach liefert `tx-dcp` den
Default-Scope aus der Config (`tx.edc.iam.dcp.default-scopes.membership.*` = DSPACE-Namensraum).

**Ergebnis:** Nach dem Fix ist die Scope-Fehlerzeile **verschwunden**. Der Konflikt ist gelöst.
Trade-off: der constraint-basierte `CxCredentialScopeExtractor` entfällt — nur relevant bei Policies mit
Credential-Constraints; für spätere echte Catena-X-Interop (Umbrella/Jira-132) neu zu betrachten.

---

## 3. Zwei Betriebs-Erkenntnisse (wichtig für jeden, der den Stack anfasst)

### 3.1 DEBUG-Logging ist ein **Programm-Argument**, kein Setting
EDCs `ConsoleMonitor` filtert **vor** Log4j2 (Level aus `LEVEL_PROG_ARG = "--log-level"`). Über
`configuration.properties` oder eine Log4j2-ConfigMap kommt man **nicht** an DEBUG heran — ohne das sieht man
von einem DCP-Fehlschlag **gar nichts**. In K8s: `args: ["--log-level=DEBUG"]` am Container (jetzt im YAML für
alle drei Deployments).

### 3.2 Der IdentityHub ist **rein in-memory** — fragil gegen jeden Neustart
`Using the InMemoryVault …`. Participants + STS-Accounts entstehen **nur** durch den Setup-Job. Jeder
IH-**Container**-Neustart wischt sie → der Connector bekommt beim STS `401 invalid_client`.

Belegter Vorfall: Host über Nacht suspendiert → Liveness-Probe startet den IH-Container beim Aufwachen neu
(Setup-Job lief 14.07. 14:11, IH-JVM-Neustart 15.07. 10:35) → STS-Accounts weg. **Pod-Alter ist irreführend**
(der Pod „lebt", nur der Container-JVM ist neu — `restartCount` prüfen, nicht das Alter).

Zweite Ursache für IH-Rolls: `kubectl apply` rollte den IH, weil der `--log-level`-Live-Patch **nicht im YAML**
stand. Behoben — die `args` sind jetzt im YAML, `apply` ist damit ein No-op für den IH.

**Reset-Prozedur nach IH-Neustart** (ohne den IH erneut zu rollen):
```bash
kubectl -n windx delete job windx-setup
kubectl apply -f kubernetes/windx/windx-stack.yaml         # IH rollt nicht mehr (args == live)
kubectl -n windx wait --for=condition=complete job/windx-setup --timeout=240s
kubectl -n windx logs job/windx-setup | grep BIBAS_OUTPUT   # neue STS-Client-Secrets
kubectl -n windx delete secret windx-sts-secrets
kubectl -n windx create secret generic windx-sts-secrets --from-literal=provider=<P> --from-literal=consumer=<C>
kubectl -n windx rollout restart deploy/windx-provider deploy/windx-consumer
```
> Perspektivisch: dem IdentityHub Persistenz geben (Postgres statt In-Memory), dann entfällt diese Fragilität.

---

## 4. Nach dem Scope-Fix: der BPN-vs-DID-Befund (untersucht & gelöst)

Nach dem `cx-dcp`-Fix blieb die Katalogabfrage **401** — aber aus neuem Grund. DEBUG-Trace des Providers:

```
SEVERE  BpnExtractionFunction: Required credential type 'MembershipCredential' not present in ClaimToken,
        cannot extract property 'holderIdentifier'   (in ProtocolTokenValidatorImpl.verify)
DEBUG   Unauthorized: The JWT id '…' was already used.
DEBUG   DSP: Service call failed: Unauthorized
```

**Kausalität (belegt):** `BpnExtractionFunction` läuft **innerhalb der Token-Validierung** und verlangt eine
BPN im `holderIdentifier`-Claim des MembershipCredentials. Sie scheitert → verbraucht dabei die JTI → der
Retry stolpert über „JWT id already used". Also: **Credential = Wurzel, JTI = Folge.**

**Warum das Credential nicht passt:** Der Setup-Job (plain-EDC) signiert ein MembershipCredential mit
`credentialSubject = {id, memberOf}` — **ohne** `holderIdentifier`. Der Namensraum ist **nicht** das Problem
(`CredentialTypePredicate` matcht schon beim nackten Typ-String). Es fehlt allein die BPN.

**Warum nur DSP 0.8:** `BpnExtractionFunction` ist **ausschließlich** für DSP 0.8 registriert
(`cx-dataspace-protocol`). Für DSP **2025** registriert der EDC-Core einen `DefaultDcpParticipantIdExtractionFunction`
— **ohne** BPN-Zwang.

**Der IdentityHub ist DID-nativ:** im `fma-IdentityHub`-Code **0** BPN-Treffer (vs. 190 DID); die Participants
tragen `participantContextId` + `did`, **kein** BPN-Feld. Der BPN ist reine Connector-/Catena-X-Schicht
(`tractusx.edc.participant.bpn`, `windx-bdrs-static`, `BpnExtractionFunction`). Deshalb ist **DID die natürliche
Identität** dieses Datenraums.

---

## 5. Lösung: Weg C — DID-Identität über DSP 2025 (verifiziert grün)

**Entscheidung:** Wind-X ist ein eigenständiger Datenraum ohne Catena-X-Anbindung → **DID statt BPN**.
Umgesetzt durch Nutzung von **DSP 2025** (`dataspace-protocol-http:2025-1`, Pfad `…/protocol/2025-1`).

**Was Weg C braucht — am Connector nichts.** Der komplette 2025-Stack (`dspcatalog2025`, `dspnegotiation2025`,
`dsptransferprocess2025` je mit HTTP-API) ist im Classpath. Nötig sind nur:
1. **Permissive Policy** am Provider (leere `permission`/`prohibition`/`obligation`) — **keine** BPN/Membership-
   Constraints, sonst bräuchte man wieder Credentials.
2. DSP-2025-Protokoll-String + `/protocol/2025-1`-Pfad in jedem Management-Request.

**Verifiziert (2026-07-15), rein DID-basiert, kein BPN, kein Credential-Umbau:**

| Stufe | Ergebnis |
|---|---|
| Asset + permissive Policy + ContractDefinition seeden | 3× HTTP 200 |
| Katalog (DSP 2025) | HTTP 200 mit Dataset `windx-asset-1` + Offer; beworbene Distributionen inkl. **`Mms-PULL`, `MediatorHttpData-PULL`, `MmsLight-PULL`, `HttpData-PUSH`** (unsere windx-Extensions sind im Cluster live) |
| Contract-Negotiation (DSP 2025) | **`REQUESTED → AGREED → FINALIZED`**, `contractAgreementId` ausgestellt, `counterPartyId: did:web:windx-identityhub:provider` |

Damit trägt die **gesamte** Identitäts- und Vertragskette DID-basiert: AP1-Vault → AP2-BDRS → cx-dcp-Scope-Fix →
DSP-2025-Default-Extraction → Agreement. **Weg A (Credential mit `holderIdentifier` anreichern + Image-Rebuild)
ist damit nicht nötig.**

**Reproduktion:** `kubernetes/windx/windx-seed.sh` (seedet + Katalog + Negotiation bis FINALIZED, extrahiert die
Offer-ID dynamisch). Ausführung im Skriptkopf dokumentiert.

---

## 6. Offen: Stufe 4 — echter Datentransfer

Nur der Byte-Transfer steht aus (`POST /edrs` → EDR → Daten ziehen). Er braucht ein **echtes EC-Schlüsselpaar**
für `signer-private`/`verifier-public` (aktuell Dummies in `tx.edc.vault.secrets`) — plus die Feinheit, dass das
Seed-Format an `;`/`:` splittet, ein PEM/JWK also sauber reinpassen muss. Katalog + Negotiation brauchen die Keys
**nicht** (deshalb bereits grün). Nebenbei: schon der bibas-Stack hat diese Keys nie geseedet (Transfers liefen
dort über den MMS-Light-EDR-Pfad) — also ohnehin Neuland.

---

## 7. Fazit

Zwei windx-Connectoren verhandeln im KinD-Cluster einen **echten Vertrag über DCP** — DID-basiert, DSP 2025, mit
eigenem IdentityHub. Der Scope-Namensraum-Konflikt (`cx-dcp`) und die BPN-Erwartung (DSP 0.8) sind verstanden und
gelöst; der Weg (DID/2025) ist verifiziert grün bis `FINALIZED`. Offen bleibt allein der Daten-Byte-Transfer
(Stufe 4, echte EC-Keys).
