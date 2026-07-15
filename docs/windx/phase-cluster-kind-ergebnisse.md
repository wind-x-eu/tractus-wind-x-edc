# KinD-Cluster-Deployment — Ergebnisse & offene Blocker

**Datum:** 2026-07-15
**Scope:** `edc-runtime-windx` als Provider + Consumer im lokalen **KinD** (`kind-mvd`, Namespace `windx`)
betreiben — eigener windx-Stack neben dem unangetasteten MVD.
**Status:** Stack läuft, DCP-Kette bis kurz vor dem Ziel. **Katalogabfrage noch nicht grün** — aber aus einem
**anderen** Grund als zuvor: der Scope-Namensraum-Fix ist verifiziert, dahinter liegen zwei neue Blocker.

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

## 4. Offener Blocker (nächste Untersuchung)

Katalogabfrage weiterhin **HTTP 401 / 502**, aber die Ursache liegt jetzt **hinter** dem Scope-Problem. Zwei
neue Meldungen im Provider-DEBUG-Log bei jedem Request:

```
WARNING [BpnExtractionFunction] Required credential type 'MembershipCredential' not present in ClaimToken,
        cannot extract property 'holderIdentifier'
DEBUG   Unauthorized: The JWT id '…' was already used.
DEBUG   DSP: Service call failed: Unauthorized
```

- **MembershipCredential fehlt in der Presentation:** die vom Consumer zurückgegebene VP enthält kein
  MembershipCredential. Zu klären: ob der Setup-Job es tatsächlich im (plain-EDC-)IdentityHub ablegt, ob der
  Credential-Typ/Namensraum zur Query passt, und ob `bpn-validation`/`agreements-bpns` (Tractus-X) hier andere
  Annahmen als der plain-EDC-IdentityHub treffen.
- **JWT id already used:** die JTI ist pro Request **verschieden**, „already used" tritt also **innerhalb eines
  Flows** auf (kein Cache-Artefakt) → der Provider validiert dasselbe Token doppelt. Kandidat:
  JTI-Replay-Schutz vs. Presentation-Flow. Am IH war beim Boot vermerkt:
  `JWT Token ID validation is not active … edc.iam.accesstoken.jti.validation=true`.

Beides ist DCP-internes Terrain und eine **eigene** Untersuchung — bewusst nicht Teil dieses Schritts
(Scope war: Scope-Fix verifizieren).

---

## 5. Fazit

Der `cx-dcp`-Ausschluss war die richtige und **verifizierte** Korrektur für den Scope-Namensraum-Konflikt.
Der Stack ist betriebsbereit bis unmittelbar vor die erfolgreiche Credential-Prüfung. Offen: MembershipCredential
in der VP + JTI-Replay — der nächste, klar abgegrenzte Untersuchungsschritt.
