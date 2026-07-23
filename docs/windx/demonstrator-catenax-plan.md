# Plan: Wind-X als offizieller Catena-X-Teilnehmer + Demonstrator „Eclipse-EDC ⇄ Tractus-X EDC"

**Datum:** 2026-07-17
**Auftrag (Chef):** (1) Tractus-X EDC mit persistierten Assets, (2) Version mit echter Authentifizierung,
(3) Demonstrator: zwischen **Eclipse Dataspace Connector** und **Tractus-X EDC** umschalten, Use Cases über beide.
**Entscheidungen:** Catena-X-Umbau **zuerst** · beide Stacks im **Uni-K3s** (`try1`) · Use Cases = **Grundfluss + MMS** ·
**bei der offiziellen Architektur bleiben**.

> **Namens-Warnung:** „Weg A/B/C" sind in `docs/windx/` als *Identitäts*-Wege belegt. Dies ist der
> **Demonstrator-Weg**; identitätsseitig bedeutet er die **Abkehr von Weg C** (DID/DSP-2025) hin zu **Catena-X (BPN)**.

---

## 1. Kernbefund: das meiste läuft schon (K3s `try1`)

| Namespace | Läuft | Bedeutung |
|---|---|---|
| `umbrella` | `edc-controlplane-postgresql-hashicorp-vault:0.11.2` + `edc-dataplane-hashicorp-vault:0.11.2`, je HashiCorp-Vault + Postgres | **Exakt was der Chef verlangt** — nur ohne unsere Use Cases |
| `umbrella` | `ssi-dim-wallet-stub:0.0.8` + `wallet-postgres` | **Der ganze Identitätsstack in einem** |
| `bibas` | `bibas-provider`, `-consumer`, `-identityhub` | Die **Eclipse-EDC-Seite** des Umschalters |
| `aas` | `iecsim`, `iecsimweb` | Das **MMS-Backend** |

Helm-Release **`umbrella`** (Chart `umbrella-3.15.1`, **Revision 1**, 2026-03-17). Verwendete Werte u. a.:
`bdrs-server-memory: enabled=false` (der Stub liefert das Directory), `ssi-credential-issuer: enabled=false`
(der Stub ist Issuer), `tx-data-provider` + `dataconsumerOne` aktiv, **`dataconsumerTwo: enabled=false`**
(ein freier Teilnehmer-Slot!).

## 2. Die offizielle Architektur — der zentrale Befund

**Alle windx-Extensions sind Data-Plane** (verifiziert per Klassenanalyse):

| Extension | Ebene |
|---|---|
| `MediatorDataPlaneExtension`, `WindxMmsDataPlaneExtension`, `WindxMmsLightDataPlaneExtension`, `NonFiniteCapablePipelineServiceExtension` | **Data-Plane** |
| `ParticipantIdLogExtension`, `FinitenessEvaluatorExtension` | neutral |
| `WindxStaticBdrsExtension` | Control-Plane — **entfällt** (war nur Ersatz für `bdrs-client`) |

Daraus folgt die Zielarchitektur:

| Ebene | Artefakt |
|---|---|
| **Control-Plane** | `edc-controlplane-postgresql-hashicorp-vault` aus **unserem Baum, unverändert** (null windx-Code) |
| **Data-Plane** | **ein** eigenes Image: `edc-dataplane-hashicorp-vault` + windx-DP-Extensions |
| **Persistenz** | Postgres + HashiCorp-Vault aus dem offiziellen Chart — **kein Eigenbau** |
| **Identität** | Wallet-Stub des Umbrella (DID, STS, OAuth, BDRS-Directory, Credential-Service) |
| **Deployment** | offizielles **`charts/tractusx-connector`** im **eigenen Namespace `windx`** |

**Warum eigener Namespace statt Umbrella-Slot?** Das Umbrella hat nur **drei** Teilnehmer-Slots
(`tx-data-provider`, `dataconsumerOne`, `dataconsumerTwo`), davon **einer frei** — für zwei windx-Connectoren
müsste man einen vierten ins `Chart.yaml` einbauen, also **das Umbrella-Chart forken**. Das Gegenteil von
„offizielle Architektur". Stattdessen tun wir, was ein **echter Catena-X-Teilnehmer** tut: eigene Connectoren
per offiziellem Chart, **gemeinsame** Identitätsdienste des Datenraums nutzen.

**Warum CP aus unserem Baum statt `tractusx/…:0.11.2`?** Das Umbrella fährt 0.11.2, unser Code ist
0.13.0-SNAPSHOT (EDC 0.17). CP und DP versionsgleich zu halten ist sicherer als ein 0.11.2-CP mit unserem
0.13-DP zu paaren. Der CP-Code bleibt dabei **unverändertes Upstream** — „offiziell" meint den Inhalt, nicht die
Registry.

**Konsequenz — ehrlich benannt:** Die kombinierte Runtime `edc-runtime-windx(-postgresql)` und der hand-gebaute
Stack (`windx-stack.yaml`, `windx-postgres.yaml`, `windx-secrets.sh`) werden davon **abgelöst**. Ihr Wert bleibt als
Verifikation (die SQL-Umstellung ist bewiesen, s. WP1/WP2) und als Weg-C-Fallback. Neu gebraucht wird nur noch
**ein** Data-Plane-Modul.

## 3. Phase 1 — Spike: ERLEDIGT (2026-07-17)

Gegen den Wallet-Stub im K3s getestet (read-only, nichts verändert):

| Prüfung | Ergebnis |
|---|---|
| STS-Token geseedete BPN (`BPNL00000003AYRE`) | **HTTP 200** |
| STS-Token **neue** BPN (`BPNL00000000WNDX`) | **HTTP 200**, `kid: did:web:…:BPNL00000000WNDX` |
| DID-Dokument neue BPN | **HTTP 200** — Stub legt Keys + DID **automatisch** an (`key_pair`, `did_document`) |
| BDRS `/api/v1/directory/bpn-directory` | **HTTP 401** (nicht 404) → existiert, verlangt VP-Token |
| **Credentials der neuen BPN** | ❌ **keine** |

`edc-wallet-secret` = **`changeme`** (Klartext im Vault-Setup-Job des Umbrella).

**Der entscheidende Befund:** Der Stub legt für *jede* BPN Keys + DID an, stellt aber **Credentials nur für
BPNs aus `seeding.bpnList`** aus. Geseedete BPNs haben je `BpnCredential`, `DataExchangeGovernanceCredential`,
`MembershipCredential`, `StatusList2021Credential` — unsere Test-BPN **nichts**. Ohne MembershipCredential kein
VP → BDRS-Aufruf (401) und Policy-Prüfung der Gegenseite scheitern.

**Verdikt:** Weg gangbar. Die Kernannahme hält (beide Seiten am selben Wallet → kein externes `did:web` →
**Jira-132-Blocker greift nicht**). Es fehlt genau **die offizielle Aufnahme unserer BPN in das Umbrella-Chart**.

## 4. Phase 2 — ERLEDIGT (2026-07-20): Wind-X im Wallet-Stub onboarden

> **Wichtige Korrektur zur ursprünglichen Annahme:** Der Helm-Wert `seeding.bpnList` **reicht nicht**.
> Das Seeding des Stubs läuft **nur bei leerer Datenbank** — es gibt keinen `ApplicationRunner`, der es bei
> einem Neustart wiederholt. Der Helm-Upgrade lief zwar sauber (Revision 2, ConfigMap trägt jetzt vier BPNs),
> bewirkte aber **nichts**: keine Wallets, keine Credentials.
>
> **Der offizielle Weg** sind die beiden APIs, die auch das Catena-X-Portal beim Firmen-Onboarding nutzt —
> gefunden in der OpenAPI des Stubs unter **`/docs/api-docs`** (nicht `/v3/api-docs`, deshalb liefen erste
> Sondierungen ins Leere):
>
> 1. `POST /api/dim/setup-dim?companyName=..&bpn=..` → Keypair + DID-Dokument.
>    **Asynchron:** sofort HTTP 201, die Anlage passiert erst nach `PORTAL_WAIT_TIME` (60 s).
> 2. `POST /api/v2.0.0/dcp/requestCredentials/catena-x-portal` → MembershipCredential & Co.
>    Muss mit dem **Holder-Token** aufgerufen werden (Issuer-Token → *„Caller BPN does not match the holder
>    wallet DID"*).
>
> Reproduzierbar als [windx-wallet-onboarding.sh](../../deployment/windx-catenax/windx-wallet-onboarding.sh)
> (`--verify-only` prüft nur). Schlüssel/DIDs werden deterministisch aus der BPN abgeleitet → erneuter Lauf
> unschädlich.
>
> **Ergebnis (in der Wallet-DB verifiziert):** `BPNL00000003AVTH` (Provider) und `BPNL00000003AWSS` (Consumer)
> haben je `MembershipCredential`, `BpnCredential`, `DataExchangeGovernanceCredential`. Credentials gesamt 9 → 15;
> bestehende Wallets (AYRE, AZQP, CRHK) unverändert.
>
> **Nebenfunde für die Config:** Client-Secret = **die BPN selbst** (`"clientID":"BPNL…","clientSecret":"BPNL…"`);
> die DID-Dokumente führen bereits `CredentialService → /api` und `IssuerService → /api/v1.0.0/dcp/<BPN>`.
>
> **Achtung Reproduzierbarkeit:** Die Credentials liegen nur in der Wallet-DB. Nach einem DB-Verlust bringt
> `seeding.bpnList` sie **nicht** zurück (dann greift zwar wieder das Startup-Seeding, aber nur für die dort
> gelisteten BPNs) — im Zweifel das Onboarding-Skript erneut laufen lassen.

### Historisch: der Helm-Eingriff (durchgeführt, aber nicht hinreichend)

Ausschließlich über Chart-Werte + `helm upgrade` — **niemals** ConfigMaps von Hand patchen (Hand-Patches werden
beim nächsten Upgrade überschrieben; die ConfigMap trägt `managed-by: Helm`, Release `umbrella`).

- `identity-and-trust-bundle.seeding.bpnList`: `"BPNL00000003AZQP,BPNL00000003AYRE"` **+ unsere zwei BPNs**.
  Danach stellt der Stub ihnen dieselben vier Credentials aus (Bpn-, DataExchangeGovernance-, Membership-,
  StatusList2021Credential).
- **BPN-Wahl** aus dem Chart-Pool (technisch akzeptiert der Stub jede BPN — es ist eine Namenswahl):
  Provider `BPNL00000003AVTH` (der freie `dataconsumerTwo`-Slot-BPN, semantisch unbelastet),
  Consumer z. B. `BPNL00000003AWSS`. **Vergeben:** `AYRE` (tx-data-provider), `AZQP` (dataconsumerOne),
  `CRHK` (Issuer/Base-Wallet).
- **Sonst nichts** am Umbrella: kein Slot aktivieren, kein Chart-Fork.
- **Nebenwirkung, gewollt:** Der Jira-132-Live-Patch (`EDC_IAM_TRUSTED-ISSUER_1`) verschwindet beim Upgrade,
  da nicht in den Werten. Für diesen Plan gegenstandslos (die EDCs reden nicht miteinander) und in
  `jira-tasks/132/` dokumentiert. `TRUSTED-ISSUER_0` (Base-Wallet) kommt aus den Chart-Defaults und bleibt.

## 5. Phase 3 — Die zwei windx-Images

**Data-Plane** (neu, das einzige Modul mit windx-Code):
- Neues Modul `edc-dataplane/edc-dataplane-windx`: `edc-dataplane-hashicorp-vault`
  (bringt SQL + Vault + `connector-migration` mit) **+** `windx-mediator-proxy`, `windx-mms`,
  `non-finite-provider-push-core`, `windx-participant-log`.
- **Keine `exclude`-Zeilen** — `bdrs-client`/`cx-dcp` sind Control-Plane-Themen. `windx-bdrs-static` entfällt.
- **Hauptrisiko:** Die windx-DP-Extensions liefen bisher **immer in einer JVM zusammen mit der Control-Plane**.
  Ob sie in einer eigenständigen Data-Plane booten, ist die zentrale offene Frage → **früh testen**.

**Control-Plane:** `edc-controlplane-postgresql-hashicorp-vault` aus unserem Baum **unverändert** bauen.

**Status Phase 3: ERLEDIGT (2026-07-20).** Beide Images gebaut und nach Harbor gepusht:

| Image | Inhalt |
|---|---|
| `reg.biba.uni-bremen.de/wind-x/windx-edc-controlplane-postgresql-hashicorp-vault:0.13.0-SNAPSHOT` | **unverändertes Upstream** (kein windx-Code), 312 MB |
| `reg.biba.uni-bremen.de/wind-x/windx-edc-dataplane-windx:0.13.0-SNAPSHOT` | offizielle DP + windx-Extensions, 338 MB |

Verifiziert per `docker manifest inspect`. Im Cluster via `imagePullSecrets: harbor-pull` (K3s kann kein `kind load`).

**Boot-Nachweis der eigenständigen Data-Plane** (lokaler Smoke-Test, Hauptrisiko der Umstellung):
```
Wind-X Mediator Data Plane Extension initialized: context 'med' … transfer type 'MediatorHttpData'
Wind-X MMS Data Plane Extension initialized: source type 'Mms', transfer type 'Mms-PULL'
Wind-X MMS-Light Data Plane Extension initialized: type 'MmsLight', transfer type 'MmsLight-PULL'
```
Alle windx-Extensions initialisieren **ohne Control-Plane in derselben JVM** → die Architektur trägt.
Weiterer Befund: Die Data-Plane braucht **selbst** `edc.iam.sts.oauth.*` (sie nimmt am DCP teil) und
`edc.participant.context.id`; ohne `data-plane-selector-client` + `edc.dpf.selector.url` registriert sie sich
nicht bei der Control-Plane (in der alten kombinierten Runtime war das unnötig).

## 5b. Phase 3b — ERLEDIGT (2026-07-20): Deployment via offiziellem Chart

Beide Connectoren laufen im K3s (`try1`), Namespace `windx`, je 4 Pods (Control-Plane, Data-Plane, PostgreSQL, Vault):

```
windx-provider-*  (BPNL00000003AVTH)      windx-consumer-*  (BPNL00000003AWSS)
```

Werte: [values-windx-provider.yaml](../../deployment/windx-catenax/values-windx-provider.yaml) /
[values-windx-consumer.yaml](../../deployment/windx-catenax/values-windx-consumer.yaml).

**Boot-Nachweis** — alle windx-Extensions in der eigenständigen Data-Plane, beide Runtimes `ready`:
```
Initialized Wind-X Mediator / MMS / MMS-Light / Participant Id Log Data Plane Extension
Runtime windx-provider-tractusx-connector-dataplane ready
Runtime windx-provider-tractusx-connector-controlplane ready
```

**Erkenntnisse aus der Umsetzung:**
- **Der Key-Drift war kein Problem:** Das Chart rendert bereits die neuen 0.13-Namen
  (`TX_EDC_IAM_DCP_BDRS_SERVER_URL`), passend zu unseren Images. Nur die *Umbrella-Env* zeigt die alten
  `tx.iam.iatp.*` — die darf man nicht als Vorlage kopieren.
- `participant.contextId` existiert als Chart-Wert → das Pflicht-Setting der SQL-Runtime ist abgedeckt.
- `EDC_DPF_SELECTOR_URL` setzt das Chart selbst → die DP registriert sich bei der CP.
- `EDC_IAM_DID_WEB_USE_HTTPS=false` hat **keinen** Chart-Wert → über `controlplane.env`/`dataplane.env`.
- **CredentialService-URL bewusst nicht gesetzt:** Der `bdrs-client` fällt auf die URL aus dem DID-Dokument
  zurück, und die steht dort korrekt (`http://ssi-dim-wallet-stub.tx.test/api`).
- `*.tx.test` löst dank `coredns-custom` **cluster-weit** auf (auch aus ns `windx`) → keine DNS-Sonderbehandlung.
- Vault-Secret `edc-wallet-secret` via `vault.server.postStart` geseedet (Muster aus dem Umbrella übernommen).
- Chart-Dependencies (vault, postgres) sind im Repo nicht vendored → einmalig `helm dependency build` nötig.
- Storage unkritisch: `local-path` (default), bestehende PVCs je 10 Gi.
- Pull-Secret `harbor-pull` aus ns `bibas` in ns `windx` kopiert.

### Historisch: ursprüngliche Planung

`charts/tractusx-connector` zweimal installieren (Release `windx-provider`, `windx-consumer`) im Namespace `windx`:
- `install.postgresql=true`, `install.vault=true` → Persistenz + Vault kommen aus dem Chart.
- Images: CP = unser unveränderter CP-Build, DP = `edc-dataplane-windx`.
- Identität auf den Wallet-Stub des Umbrella (Cross-Namespace-FQDN bzw. `*.tx.test` via CoreDNS):
  `participant.bpn`, STS-Token-URL `…/oauth/token`, DIM-URL `…/api/sts`,
  BDRS `…/api/v1/directory`, Credential-Service `…/api`, Trusted Issuer = `did:web:…:BPNL00000003CRHK`,
  `edc.iam.did.web.use.https=false`, Vault-Secret `edc-wallet-secret` = `changeme`.
- ⚠️ **Key-Namen aus UNSEREM Code verwenden** (`tx.edc.iam.dcp.bdrs.server.url`,
  `tx.edc.iam.dcp.credentialservice.url`) — die Umbrella-Env zeigt die **alten** 0.11.2-Namen
  (`tx.iam.iatp.*`). Nicht blind kopieren.

## 6. Phase 4 — Grundfluss verifizieren ✅ ERLEDIGT

**Ergebnis: GRÜN.** Katalog → Negotiation `FINALIZED` → EDR → Transfer **HTTP 200 mit / 401 ohne Token**,
und zwar gegen eine **echte Membership-Policy** (`Membership eq active`), nicht die permissive Weg-C-Policy.
Damit ist die „echte Authentifizierung" des Auftrags belegt: der Zugriff hängt am MembershipCredential
aus dem Wallet-Stub.

Skripte: [windx-cx-seed.sh](../../deployment/windx-catenax/windx-cx-seed.sh) (Asset + Policy + ContractDef),
[windx-cx-transfer.py](../../deployment/windx-catenax/windx-cx-transfer.py) (Flow + 401-Gegenprobe),
[windx-cx-dataplane-keys.sh](../../deployment/windx-catenax/windx-cx-dataplane-keys.sh) (Signierschlüssel).

### Drei Fallen, die dabei je einen halben Tag gekostet haben

**(1) Der `assigner` in der Policy ist die BPN, nicht die DID.**
Mit der DID hängt jede Negotiation in `REQUESTED`, der Provider meldet
„Policy in the contract agreement is not equal to the one in the contract offer".
Ursache (EDC 0.17.0, `ContractValidationServiceImpl`):

```java
policyEquality.test(agreement.getPolicy().withTarget(latestOffer.getAssetId()), latestOffer.getPolicy())
```

`PolicyEquality` serialisiert beide Policies nach JSON und entfernt vor dem Vergleich **nur auf oberster
Ebene** `"@type"`, `"assignee"`, `"target"`. Der `assigner` wird also **mitverglichen**, und im Agreement
steht dort die BPN. Bisektiert mit einer komplett leeren Policy (`odrl:Set` ohne Rules) — die scheiterte
genauso, womit Permission/Constraint als Ursache ausgeschlossen waren.

Merke zur Identität: `EDC_PARTICIPANT_ID` ist bei Tractus-X die **DID** (so dokumentiert das Chart es auch),
die BPN steht separat in `TRACTUSX_EDC_PARTICIPANT_BPN`. In Policies und Agreements zählt die **BPN**, weil
Tractus-X die Agent-Identität aus dem MembershipCredential ableitet. Der MVD (`kind-mvd`) kennt dagegen
**ausschließlich** DIDs und gar keine BPN.

**(2) Target gehört *nicht* in die einzelnen Rules.**
`Policy.withTarget()` setzt das Target nur auf Policy-Ebene und propagiert **nicht** in die Rules. Ein
Rule-Target im eigenen Offer erzeugt also erst recht Ungleichheit.

**(3) Der Signierschlüssel der Data-Plane stand auf `CHANGEME`.**
Das Chart lässt `dataplane.token.signer.privatekey_alias` auf dem Platzhalter. Folge: Negotiation wird noch
`FINALIZED`, aber jeder Transfer terminiert mit
„Could not generate access token: JWSSigner cannot be generated for private key 'CHANGEME'" — und es gibt
schlicht **keine EDR**. `windx-cx-dataplane-keys.sh` erzeugt ein EC-P-256-Paar und legt es als
**gitignoriertes values-Overlay** ab, das `vault.server.postStart` vollständig überschreibt (und deshalb
auch das Wallet-Secret mitseedet).

> **Betriebshinweis:** Die Vault-StatefulSets fahren mit `OnDelete`. Ein `helm upgrade` allein startet den
> Vault **nicht** neu, der `postStart` läuft also nicht — die Pods müssen von Hand gelöscht werden
> (`kubectl -n windx delete pod windx-{provider,consumer}-vault-0`). Da der Vault im dev-mode in-memory
> läuft, ist der `postStart` die einzige Re-Seed-Quelle.

## 7. Phase 5 — MMS über beide EDCs

### Tractus-X-Seite ✅ ERLEDIGT (2026-07-20)

**Ergebnis: GRÜN.** Der komplette MMS-Use-Case läuft gegen den `iecsim` (ns `aas`): Katalog (bewirbt
`Mms-PULL` **und** `HttpData-PUSH`) → Negotiation gegen die Membership-Policy → Transfer
`Mms-PULL` → `STARTED` → EDR → **HTTP 200 nach ~9,4 s** mit einer JSON-Aggregation aus 10 Polls
echter Live-Telemetrie (Windgeschwindigkeit fiel im Lauf sichtbar von 2,21 auf 2,05 m/s;
`Running=FALSE` bei 2 m/s korrekt — unterhalb der Anlaufgeschwindigkeit) → **401 ohne Token**.
Gegenprobe im iecsim-Log: die Reads kamen von der Provider-Data-Plane-Pod-IP, nicht vom `iecsimweb`.

Skripte: [windx-cx-seed.sh](../../deployment/windx-catenax/windx-cx-seed.sh) (Abschnitt 3b/3c:
MMS-Asset + ContractDef, gleiche Membership-Policy wie Phase 4),
[windx-cx-mms-transfer.py](../../deployment/windx-catenax/windx-cx-mms-transfer.py) (Zwei-Schritt-Flow).

**Erkenntnisse (ersparen dem Nächsten die Sucherei):**

1. **`POST /v3/edrs` kann kein `Mms-PULL`.** Der Phase-4-Weg verdrahtet den Transfer fest auf
   `HttpData-PULL` + Destination `HttpProxy` (`edr-callback/ContractNegotiationCallback.java:39-40`).
   Für MMS zwingend der Zwei-Schritt-Weg: `POST /v3/contractnegotiations` → `FINALIZED` →
   `POST /v3/transferprocesses` mit `transferType: "Mms-PULL"`, `dataDestination: {"type": "Mms"}`.
2. **Die `mms:*`-Properties überleben die JSON-LD-Expansion unverändert** — `mms` ist kein
   definierter Präfix, JSON-LD behandelt `mms:serverHost` als absolute IRI und lässt sie durch.
   Die Sorge, dass `MMSDataSource.buildConfig()` stillschweigend auf die Defaults
   (`WPPS`/`LCCS1$ST$Mod$stVal`) zurückfällt, hat sich **nicht** bestätigt.
3. **iecsim-Namensraum** (weicht vom E2E-Test ab, der gegen `agilevt-dev` geschrieben wurde):
   Logical Device `WPPSLD2` (nicht `WPPS`), Sensoren als roher MMS-ItemId `LN$FC$DO$DA`.
   **Mit** DA-Suffix (`$mag$f` bei Messwerten/FC=MX, `$stVal` bei Zuständen/FC=ST) liefert iecsim
   den reinen Skalar, **ohne** Suffix die Struktur „Wert, Quality-Bits, Zeitstempel". Beides gültig.
   Kuratierte Liste im Seed: `WGEN1$MX$W$mag$f` (Leistung), `WNAC1$MX$WdSpd$mag$f` (Wind),
   `WROT1$MX$RotSpd$mag$f` (Rotor), `WGBX1$MX$OilTmp$mag$f` (Getriebeöl), `WTUR1$ST$Running$stVal`.
4. **Timing:** `AGGREGATION_SIZE=10` × `mms:updateInterval=1000 ms` ≈ 10 s pro Pull, bei 120 s
   Source-Timeout. Der HTTP-Client des Abrufers braucht entsprechend Geduld (>15 s Timeout).
5. Diagnose-Hebel: iecsim quittiert **jeden** Read mit `LD/LN/DO/FC` + Client-IP im Log —
   damit lässt sich jede Sensor-/Routing-Frage empirisch klären.

### Nachtrag: `Mms-PUSH` nachgerüstet ✅ (2026-07-21)

Die Data-Explorer-UI sendet für ihre Diagnose-Station fest `transferType: 'Mms-PUSH'` (BIBA-Vorlage) —
bisher bewarb unsere Data-Plane den Typ nicht (`supportsMmsPush: false` im Tractus-X-Profil der UI).
Nachgerüstet als Option B des Push-Plans (Details: [phase-mms-push-continuous-plan.md](phase-mms-push-continuous-plan.md) §9):

- **`MmsPushSink`/`MmsPushSinkFactory`** (Modul `windx-mms`, Sink-Typ `Mms`) → Self-Registration bewirbt
  `Mms-PUSH`; eigener Daemon-Thread je Transfer, POST pro Aggregation, kein `asyncAllOf`.
- **Forwarding-Service-Nachbildung** in ns `windx` als Push-Ziel (`windx-forwarding.py`/`.yaml`/`-deploy.sh`;
  Ingress `windx-forwarding.tx.test` als späterer UI-Lesepfad). In-memory, API-kompatibel zum
  BIBA-Original (`POST/GET /log?signal=…`, `GET /log/queues`).
- **Seed §3d:** eigenes Live-Asset `windx-mms-live-1` mit `edc:isNonFinite=true` (das PULL-Asset bleibt
  finit — dasselbe Flag dort würde den Ein-Aggregations-Pfad unendlich machen).
- **Verifikation:** [windx-cx-push-transfer.py](../../deployment/windx-catenax/windx-cx-push-transfer.py) —
  STARTED bleibt STARTED, ≥2 Aggregationen in der Queue, Terminate stoppt den Strom.

**Kontrakt für die UI-Anpassung (steht noch aus, macht der User):** `transferType: 'Mms-PUSH'` mit
`dataDestination: {type: 'Mms', baseUrl: 'http://windx-forwarding:4000/log?signal=<sensor>'}`
(Typ `Mms`, nicht `HttpData` — Sink-Dispatch läuft über den Destination-Typ); von der UI angelegte
Assets brauchen zusätzlich `isNonFinite: 'true'` in der dataAddress; `supportsMmsPush: true` +
Leseroute über `windx-forwarding.tx.test`.

### Vorführbare Demo ✅ (2026-07-20)

[windx-cx-demo.sh](../../deployment/windx-catenax/windx-cx-demo.sh) (Git Bash) bzw.
[windx-cx-demo.ps1](../../deployment/windx-catenax/windx-cx-demo.ps1) (PowerShell, `-Auto`) führt den
Use Case in **fünf Akten** mit Pausen und Erzähltext vor (`--auto` für den Selbsttest ohne Pausen);
die API-Aufrufe macht
[windx-cx-demo.py](../../deployment/windx-catenax/windx-cx-demo.py) stufenweise in einem Demo-Pod
(Zustand in `/tmp/demo-state.json` im Pod):

1. **Die Anlage** — iecsimweb per Port-Forward auf `http://localhost:8080` (startet das Skript selbst)
2. **Das Angebot** — Katalog: Asset, Transferarten, Membership-Constraint lesbar extrahiert
3. **Der Vertrag** — Negotiation bis `FINALIZED`
4. **Die Daten** — `Mms-PULL`, Aggregation mit echten Werten (Wind, Leistung, Rotor, Öltemperatur,
   Running) + **Beweis**: iecsim-Log gefiltert auf die Data-Plane-Pod-IP
5. **Das Schloss** — 401 ohne Token, danach zweiter Abruf mit *neuen* Werten („live, kein Playback")

Stolpersteine für Nachbauer: `dataArrays` in der Aggregation ist verschachtelt —
`{sensor: {"keys": […], "values": […]}}`, nicht `{sensor: […]}`. Und `kubectl cp` unter Windows
braucht einen **relativen** Quellpfad — den Doppelpunkt in `C:\…` missversteht kubectl als
Pod-Trenner („one of src or dest must be a local file specification"); die Demo liefe dann
stillschweigend mit dem alten Skriptstand im Pod weiter.

### Noch offen in Phase 5 (bewusst zurückgestellt, Ansage vom 2026-07-20)

- **Semantic Mediator:** existiert nirgends als lauffähiges Artefakt (der `aas-mediation-service`
  in ns `aas` ist ein AAS-Shell/Submodel-Downloader, **nicht** der Mediator — `/forwardRealDataSource`
  → 404). `MediatorHttpData`-Transfers bleiben blockiert, bis ein Mediator beschafft ist.
- **Eclipse-EDC-Seite (`bibas`):** nutzt den alten BIBA-Konfigurationsstil (`web.http.mms.port` etc.),
  kein MMS-Asset geseedet; „auf beiden EDCs" heißt dort eigenes Skript gegen dessen Management-API.

## 8. Phase 6 — Web-Demonstrator ✅ ERLEDIGT (2026-07-20)

**Ergebnis: GRÜN, im Cluster (`try1`, ns `windx`) verifiziert.** Ein webbasierter Demonstrator bildet die
Zwei-Rollen-Pipeline des Wind-X-Dashboards nach — bewusst als eigenständige Nachbildung, nicht als Klon.

**Rollen-Pipeline (Vorbild: dashboard.windx.biba.uni-bremen.de):**
- **Betreiber** sieht eine **Live-Preview seiner Anlagendaten über einen Direktpfad** (nicht über den
  Datenraum) und kann die Telemetrie **freigeben** (Asset + Membership-Policy + ContractDefinition anlegen)
  oder die Freigabe **zurückziehen** (ContractDefinition löschen → verschwindet aus dem Katalog).
- **Dienstleister** sieht **zunächst nur die Freigaben** (Katalogabfrage), kann sich **verbinden**
  (Negotiation → FINALIZED → Transfer `Mms-PULL` → EDR) und bekommt die Daten dann **im Graphen**,
  fortlaufend (je Abruf 10 Messwerte, ~10 s). Plus 401-Gegenprobe.

Rollenwechsel (Betreiber/Dienstleister) und EDC-Umschalter (Tractus-X/Eclipse) sind **orthogonal** —
beides koexistiert. Der Eclipse-Tab ist ausgegraut, bis die bibas-Seite steht.

**Der MMS-Direktpfad** (Kern der Betreiber-Preview): eigener minimaler IEC-61850/MMS-Client in
**Python-stdlib**, portiert aus dem Java-Client (`AasIecConnector2`/`MMSClient`) — statischer COTP-Connect +
Associate (Capture-Templates), BER-kodierter Read-Request, Tag-Scanner-Decoder. Läuft am Datenraum vorbei
direkt gegen `iecsim.aas:102`. **Kein Java, kein Image-Build.**

**Artefakte** (alle in `deployment/windx-catenax/`):
- [windx-cx-dashboard.py](../../deployment/windx-catenax/windx-cx-dashboard.py) — Backend: MMS-Direktpfad +
  EDC-Kette + HTTP-API (`/api/{preview,status,freigabe,zurueckziehen,freigaben,verbinden,daten,gegenprobe,log}`)
- [windx-cx-dashboard.html](../../deployment/windx-catenax/windx-cx-dashboard.html) — Frontend (viel Weiß,
  ruhig, sachlich; Rollen-Tabs, Live-Graph, Protokoll)
- [windx-cx-dashboard.yaml](../../deployment/windx-catenax/windx-cx-dashboard.yaml) — Deployment + Service
- [windx-cx-dashboard-deploy.sh](../../deployment/windx-catenax/windx-cx-dashboard-deploy.sh) — Code als
  ConfigMap (`python:3.12-alpine` mountet sie), `--port` startet gleich den port-forward

**Zugriff:** `kubectl -n windx port-forward svc/windx-dashboard 8090:80` → `http://localhost:8090`

**Die `/api/*`-Schnittstelle ist der Andockpunkt für die UI des Kollegen** — sein Frontend ersetzt dann nur
die HTML-Seite, Backend/EDC-Kette bleiben. Der Interim ist kein Wegwerf-Code.

**Erkenntnisse:**
1. **Freigaben nicht über die Transferart filtern.** `Mms-PULL` steht in der Katalog-Distribution *jedes*
   Assets (Data-Plane-Fähigkeit, kein Asset-Merkmal) — sonst erscheinen fremde Assets als Freigaben.
   Gezielt nach der Asset-ID suchen.
2. **Der portierte MMS-Decoder muss defensiv sein.** iecsim bedient parallel Data-Plane (10 Polls/Abruf),
   Web-UI und unseren Direktpfad; dabei kommen Fremdpakete und Timeouts. Der Decoder darf **nie werfen**
   (sonst reißt ein Fremdpaket den Pfad ab), und die Preview-Schleife toleriert einzelne Aussetzer
   (erst 3 Fehlschläge in Folge = „gestört"). Nach dem Härten: 180 Punkte am Stück, **0 Fehlerzeilen**.

**Lesart des Umschalters:** Beide EDCs laufen **parallel und reden nicht miteinander**. Die
Identitäts-Inkompatibilität (DID-Welt vs. BPN-Welt) ist für den Demonstrator irrelevant. Ein Transfer
*zwischen* beiden wäre Jira-132 — nicht Teil dieses Plans.

---

## Risiken

| Risiko | Umgang |
|---|---|
| **`helm upgrade` auf Revision 1** (seit 2026-03-17 nie aktualisiert) | Vorher `helm diff`/Dry-Run; lokales Chart == deployte Version (3.15.1) verifiziert |
| **Jira-132-Live-Patch geht verloren:** `EDC_IAM_TRUSTED-ISSUER_1-ISSUER_ID=did:web:bibas-identityhub.tx.test:provider` ist **live gesetzt, aber NICHT in den Helm-Werten** → ein Upgrade entfernt ihn | Bewusst entscheiden: in die Werte aufnehmen (dann offiziell + upgrade-fest) oder verwerfen (Jira-132 ruht) |
| **Upgrade trifft laufende Umbrella-EDCs** (gemeinsam genutzt) | Additive Wertänderung; Wallet-Daten liegen in `wallet-postgres` und überleben einen Stub-Neustart |
| **windx-DP-Extensions ohne kombinierte Runtime** | Hauptrisiko Phase 3 — früh testen |
| Cluster-Storage knapp | Der Slot bringt eigene Postgres-PVC mit → Storage vorher klären |
| Version-Drift: Umbrella-EDC 0.11.2 ↔ unser Code 0.13/EDC 0.17 | Config-Keys wurden umbenannt (`tx.iam.iatp.*` → `tx.edc.iam.dcp.*`). **Unser DP-Image ist 0.13** — ob es mit der 0.11.2-Control-Plane des Slots harmoniert, ist zu prüfen; ggf. CP-Image des Slots auf eine passende Version heben |

## Offene Fragen

1. **Wie viele Teilnehmer?** Ein windx-Connector (Provider) reicht, wenn der Umbrella-`dataconsumerOne` als
   Gegenstelle dient — dann ist Wind-X ein *echter* Teilnehmer in einem *fremden* Datenraum. Oder zwei eigene
   (Provider+Consumer), analog zum bisherigen Aufbau?
2. **BPN-Vergabe:** eigene Wunsch-BPN oder eine offizielle CX-Test-BPN aus dem Chart-Pool?
3. **Wer fährt das `helm upgrade`?** Das Umbrella ist geteilte Infrastruktur.

## Nicht Teil dieses Plans

- **Jira-132** (Transfer *zwischen* Eclipse-EDC und Tractus-X-EDC)
- **WP3** (Persistenz des `fma-IdentityHub`) — für die Tractus-X-Seite gegenstandslos; nur relevant, falls die
  **Eclipse-EDC-Seite** neustart-fest werden soll (Build-Blocker 0.18-SNAPSHOT-Drift bleibt bestehen)
