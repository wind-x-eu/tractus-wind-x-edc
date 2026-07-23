# Cluster-Deployment: `edc-runtime-windx` als Provider + Consumer — Implementierungsplan

**Datum:** 2026-07-14
**Ziel:** Zwei Prozesse unserer `edc-runtime-windx` (Provider + Consumer) im K8s-Cluster betreiben, mit echter
DCP-Negotiation und Datentransfer — durch **Austausch des Connector-Images im bestehenden, erprobten
`bibas`-Stack**.
**Status:** **AP1 + AP2 umgesetzt** (2026-07-14, Commit s. u.) — Unit-Tests 20/20, Classpath-Nachweis und Boot-Smoke grün.
Der In-Process-DCP-Beweis und der Regressionsanker stehen aus: sie laufen **nur in der Debian-VM** →
[uebergabe-ap0-ap2-debian-vm.md](uebergabe-ap0-ap2-debian-vm.md). AP3–AP8 offen.
**Kernentscheidung:** **Option (a)** — BDRS wird durch eine eigene, config-getriebene `BdrsClient`-Implementierung
(`windx-bdrs-static`) ersetzt, **kein BDRS-Server**. Der `fma-IdentityHub` bleibt unverändert.

---

## 0. Warum das kein „from scratch" ist

Der schwierige Teil ist **bereits einmal gelöst und deployt**: Der BIBA-Connector (EDC 0.17) lief als Provider +
Consumer + IdentityHub im K3s (Namespace `bibas`), mit echter DCP-Identitätsprüfung
(Katalogabfrage → HTTP 200, `participantId: did:web:bibas-identityhub.tx.test:provider`, Stand 26.05.2026) und
anschließendem Datenfluss (MMS-Light-Demo 01.06.2026, EDC-Vertrag als Gate).

> **Wichtig:** Der Zwei-Prozess-Plan ([zwei-prozess-dcp-recherche-und-plan.md](zwei-prozess-dcp-recherche-und-plan.md))
> führt unter „Lokale Mehr-Connector-Referenz: ❌ existiert nicht" — das gilt **nur repo-bezogen**. Im Workspace
> existiert sie sehr wohl (`kubernetes/bibas/bibas-stack.yaml` + `jira-tasks/126`). Dieser Plan nutzt sie als
> Vorlage und ist deshalb **kürzer und risikoärmer als der dort skizzierte MVD-Weg**.

Was **nicht** übernommen werden kann: Der BIBA-Connector ist **Vanilla-EDC**, unserer ist der **Tractus-X-Flavor**
(`edc-controlplane-base` zieht `bdrs-client`, `cx-dcp`, `tx-dcp`, `cx-policy` fest mit). Daraus folgen exakt
**zwei Pflicht-Code-Änderungen** (AP1, AP2). Alles Übrige ist Config.

### Was sich als unkritisch erwiesen hat (verifiziert)

| Thema | Befund | Beleg |
|---|---|---|
| **STS** | Ohne `tx.edc.iam.sts.div.url` fällt die Runtime **automatisch auf den Standard-EDC-Remote-STS-Client** zurück (`RemoteSecureTokenService`, Keys `edc.iam.sts.oauth.*`) — genau die Schnittstelle, die der `fma-IdentityHub` auf :8184 bedient. **Kein DIM/DIV-Problem.** | `RemoteTokenServiceClientExtension#secureTokenService()` |
| **DCP-Scopes / trusted-issuer / did:web** | Keys stammen aus Upstream-EDC → **identisch** zur bibas-ConfigMap | `bibas-provider-config` |
| **Docker-Image** | `dockerize`-Task wird **jedem Shadow-Projekt automatisch** angehängt → kein Dockerfile nötig | Root-`build.gradle.kts` Z. 114–160 |
| **Vault** | `single-participant-vault` ist in `edc-runtime-windx` bereits eingebunden — es fehlt **nur** die Seed-Klasse | `edc-runtime-windx/build.gradle.kts` Z. 33 |

> Der DIM/DIV-Blocker gilt **nur** für den Umbrella-`ssi-dim-wallet-stub`, **nicht** für unseren eigenen
> IdentityHub. Ein späterer Umbrella-Anschluss (Jira-132) bleibt davon unberührt und ist **nicht** Teil dieses Plans.

---

## 1. Zielarchitektur

Neuer Namespace `windx`, strukturgleich zu `bibas`, mit **getauschtem Connector-Image** und **ohne BDRS-Server**:

```
Windows-Host (hosts → 10.103.45.212)
      │ HTTP
      ▼
NGINX Ingress
      ├── windx-consumer-mgmt.tx.test  ──► Service windx-consumer:28181
      ├── windx-provider-{mgmt,dsp,dp}.tx.test ──► Service windx-provider:{18181,18182,19293}
      └── windx-identityhub.tx.test    ──► Service windx-identityhub:10100   (did:web)

Namespace `windx`
  ┌────────────────────────────────────────────────────────────────────┐
  │ Pod windx-identityhub   (Image UNVERÄNDERT: luf-fma-identityhub)   │
  │   8182 identity · 8183 credentials · 8184 STS · 10100 did:web      │
  │                                                                     │
  │ Pod windx-provider  ┐                                              │
  │ Pod windx-consumer  ┘ (Image NEU: edc-runtime-windx)               │
  │   · Standard-Remote-STS  ───────────► IdentityHub :8184            │
  │   · CredentialService    ───────────► IdentityHub :8183            │
  │   · BdrsClient = windx-bdrs-static  (Config-Map BPN→DID, KEIN HTTP)│
  │   · Vault = InMemory + VaultSeedExtension (tx.edc.vault.secrets)    │
  │                                                                     │
  │ Job windx-setup  (one-shot; Image: ALTES bibas-connector-Image,     │
  │                   ruft nur die IdentityHub-HTTP-API)                │
  └────────────────────────────────────────────────────────────────────┘
```

**Kein BDRS-Server.** Begründung: `BdrsClientImpl.resolveDid(bpn)` holt das Directory per HTTP und **reicht DIDs
nicht durch**; der echte `tractusx/bdrs-server-memory` würde seinerseits Verifiable Presentations validieren →
zusätzliche Identitätsverdrahtung. Bei **zwei bekannten Teilnehmern** ist eine statische BPN↔DID-Abbildung
funktional äquivalent und kostet ~70 LOC.

---

## 2. Arbeitspakete

Jedes AP hat ein **Abnahmekriterium, das ohne das jeweils folgende AP prüfbar ist**. Reihenfolge = Abhängigkeit.

---

### AP0 — Baseline festnageln

**Inhalt:** Vor jeder Änderung den Ist-Zustand als Referenz sichern.

```bash
./gradlew :edc-controlplane:edc-runtime-windx:build
./gradlew :edc-tests:e2e:dcp-tests:test --tests "*MediatorConsumerPullTest*"
```

**✅ Abnahme:** Build grün; `MediatorConsumerPullTest` grün. Der Test ist der Regressionsanker für AP1–AP3 —
er muss nach *jedem* der folgenden APs weiterhin grün sein.

> **⚠️ Nachtrag 2026-07-14 (gemessen, nicht angenommen):** Der Test benötigt `-DincludeTags="EndToEndTest"` und
> läuft **nur unter Linux**. Unter Windows bricht schon die **STS-Runtime** (Upstream-Modul) beim Boot in der
> Dependency-Injection ab — ebenso der Upstream-Test `StsConsumerPullTest`; auf unverändertem `HEAD` identisch.
> Die windx-Entwicklung fand in einer **Debian-VM** statt (Commit-Autor `flo@debian.myguest.virtualbox.org`),
> dort war der Test laut Doku grün.
>
> Die Angabe „**5/5**" in einer früheren Fassung dieses Plans war **nicht gemessen**: Die Zahl 5 stammt aus der
> Commit-Message von `f80f7b11`, das „grün" aus `phase-identitaet-weg-a.md` (dort protokolliert:
> `SUCCESS (1 tests, 1 passed)` — damals gab es erst einen Test). Der Anker ist also erst in der VM zu
> etablieren, s. [uebergabe-ap0-ap2-debian-vm.md](uebergabe-ap0-ap2-debian-vm.md).

---

### AP1 — `VaultSeedExtension` in die windx-Runtime holen  *(Pflicht-Code-Änderung 1)*

**Problem:** `InMemorySingleParticipantVault` startet **leer**. Die Klasse, die `tx.edc.vault.secrets` liest, liegt
in `edc-runtime-memory/src/main/java/.../vault/memory/VaultSeedExtension.java` — also **im Nachbar-Runtime, nicht
in einem geteilten Modul**. Ohne sie sind STS-Client-Secret und Signer-Keys nicht auflösbar → **jede Negotiation
scheitert**, und zwar mit einem irreführenden Fehlerbild (Token-Signatur statt „Secret fehlt").

**Umsetzung:**
1. `VaultSeedExtension.java` nach `edc-controlplane/edc-runtime-windx/src/main/java/org/eclipse/tractusx/edc/windx/vault/` kopieren.
2. Eintrag in `edc-runtime-windx/src/main/resources/META-INF/services/org.eclipse.edc.spi.system.ServiceExtension`.

Die Kombination `single-participant-vault` (liefert den Vault) + `VaultSeedExtension` (injiziert ihn, seedet ihn,
gibt ihn zurück) ist **exakt die, die `edc-runtime-memory` produktiv fährt** — also keine neue Verdrahtung, nur
dieselbe an neuer Stelle.

> **Format-Falle:** Der Code splittet auf **`;`** (Paare) und **`:`** (Key/Value) — `seedSecrets.split(";")`,
> `kvp.split(":", 2)`. Die `@Setting`-Beschreibung sagt fälschlich „comma-separated". Also
> `tx.edc.vault.secrets=alias1:secret1;alias2:secret2`.

**🧪 Test:**
- **Unit** (neu, EDC-`DependencyInjectionExtension`): Extension mit `tx.edc.vault.secrets=a:1;b:2` booten →
  `vault.resolveSecret("a") == "1"`, `resolveSecret("b") == "2"`; ohne die Property → kein Fehler.
- **Boot-Smoke:** Runtime mit `windx-dev.properties` + `tx.edc.vault.secrets=test-alias:hello` starten →
  Log enthält `Runtime ... ready`, kein `Cannot get the participant context`.

**✅ Abnahme:** Unit-Test grün **und** `MediatorConsumerPullTest` weiter grün (nur in der Linux-VM messbar).

---

### AP2 — Modul `windx-bdrs-static`  *(Pflicht-Code-Änderung 2, Kernstück)*

**Problem:** `tx.edc.iam.dcp.bdrs.server.url` ist in `BdrsClientExtension` ein **Pflicht-Setting** (kein
`required = false`), und der Client wird bei **jedem ausgehenden DSP-Request** zur Audience-Auflösung gerufen.

**Wichtig — es sind zwei Services, nicht einer.** Das Modul `bdrs-client` liefert:
- `BdrsClientExtension` → `@Provider BdrsClient`
- `BdrsClientMapperExtension` → `@Provider AudienceResolver` (= `BdrsClientAudienceMapper`)

Wer `bdrs-client` ausschließt, verliert **beide** und muss **beide** ersetzen. (Das ist der häufigste Stolperstein
bei diesem Umbau — ein fehlender `AudienceResolver` äußert sich erst zur Laufzeit beim ersten DSP-Call.)

**Umsetzung:**

| Datei | Inhalt |
|---|---|
| `edc-extensions/windx/windx-bdrs-static/build.gradle.kts` | analog `windx-participant-log`; zusätzlich `core-spi` (für `BdrsClient`) |
| `.../windx/bdrs/StaticBdrsClient.java` | `implements BdrsClient` — zwei `Map`s (BPN→DID, DID→BPN), `resolveDid`/`resolveBpn`. Fachlicher Zwilling von `MockBdrsClient` |
| `.../windx/bdrs/StaticAudienceResolver.java` | `implements AudienceResolver` — `counterPartyId` ist bereits DID → durchreichen; sonst `resolveDid()` |
| `.../windx/bdrs/WindxStaticBdrsExtension.java` | `@Provider BdrsClient` + `@Provider AudienceResolver`; `@Setting tx.edc.windx.bdrs.mappings` |
| `META-INF/services/...ServiceExtension` | Registrierung |
| `settings.gradle.kts` | `include(":edc-extensions:windx:windx-bdrs-static")` |

Config-Format (bewusst wie beim Vault, `;`/`:`-getrennt — aber DIDs enthalten `:`, deshalb **`=` als Trenner**):

```properties
tx.edc.windx.bdrs.mappings=BPNL000000000PROV=did:web:windx-identityhub.tx.test:provider;BPNL000000000CONS=did:web:windx-identityhub.tx.test:consumer
```

In `edc-runtime-windx/build.gradle.kts`:

```kotlin
implementation(project(":edc-controlplane:edc-controlplane-base")) {
    exclude("org.eclipse.tractusx.edc", "bdrs-client")   // ersetzt durch windx-bdrs-static
}
implementation(project(":edc-extensions:windx:windx-bdrs-static"))
```

> Das `exclude`-Muster auf einer Projekt-Dependency **nutzt unsere Runtime bereits** (`data-plane-selector-client`
> beim `edc-dataplane-base`) — es ist also erprobt, aber für ein *internes* Projekt neu. Deshalb der explizite
> Nachweis unten.

**🧪 Test (dreistufig — hier steckt der eigentliche Wert):**

1. **Unit:** `StaticBdrsClient` — Hin- und Rückauflösung, unbekannter BPN → `null`, leere/kaputte Config → sauberer
   Fehler. `StaticAudienceResolver` — DID wird durchgereicht, BPN wird aufgelöst.
2. **Classpath-Nachweis** (dass das `exclude` wirklich greift):
   ```bash
   ./gradlew :edc-controlplane:edc-runtime-windx:dependencies --configuration runtimeClasspath | grep -i bdrs
   ```
   → **darf `bdrs-client` nicht mehr enthalten**. Zusätzlich im Boot-Log: die Zeile
   `BPN/DID Resolution Service Client Extension` **darf nicht erscheinen**, stattdessen unsere Extension.
3. **In-Process-DCP-E2E — der stärkste Test:**
   `MediatorConsumerPullTest` registriert heute in Z. 130–133 auf **beiden** Runtimes
   `.registerServiceMock(BdrsClient.class, mockBdrs())` + `bdrsDummyConfig()`. Unsere Extension ist der
   **produktive Zwilling genau dieses Mocks**. Also: eine Testvariante, die den Mock **entfernt** und stattdessen
   `windx-bdrs-static` mit echter Mapping-Config lädt.
   → Bleiben die **5 Tests grün** (Negotiation + Transfer + EDR + Pull), ist die Extension **in einer echten
   DCP-Kette bewiesen — ohne einen einzigen Pod anzufassen.**

**✅ Abnahme:** Alle drei Stufen grün. Damit sind beide Code-Änderungen erledigt und verifiziert; alles Weitere ist
Betrieb.

**Fallback,** falls das `exclude` auf dem internen Projekt nicht greift: `bdrs-client` drinlassen und den echten
`tractusx/bdrs-server-memory` deployen (Option b, +2–3 d) — oder die BDRS-URL auf einen WireMock zeigen lassen, der
ein statisches Directory liefert.

---

### AP3 — Image bauen & in die Harbor-Registry

```bash
./gradlew :edc-controlplane:edc-runtime-windx:dockerize          # → edc-runtime-windx:0.13.0-SNAPSHOT + :latest
docker tag edc-runtime-windx:latest reg.biba.uni-bremen.de/wind-x/edc-runtime-windx:0.1.0
docker push reg.biba.uni-bremen.de/wind-x/edc-runtime-windx:0.1.0
```

Der Entrypoint erwartet die Config unter **`/app/configuration.properties`** (`-Dedc.fs.config=...`) — der
`bibas`-Stack montiert seine `edc.properties` an anderer Stelle, das muss in AP5 angeglichen werden.

**🧪 Test:** Container **ohne Cluster** starten:
```bash
docker run --rm -v $PWD/windx-dev.properties:/app/configuration.properties reg.biba.uni-bremen.de/wind-x/edc-runtime-windx:0.1.0
```
**✅ Abnahme:** Log endet auf `Runtime ... ready`; die windx-Extensions (`ParticipantIdLog`, Mediator, MMS) und
`windx-bdrs-static` erscheinen in der Extension-Liste; `BdrsClientExtension` **nicht**.

---

### AP4 — Config-Übersetzung: `provider.properties` / `consumer.properties`

Basis: die bestehenden `bibas-provider-config` / `bibas-consumer-config`. **Unverändert übernehmbar** sind Ports,
`edc.participant.id` (= DID), `edc.iam.issuer.id`, `edc.iam.did.web.use.https=false`, der komplette
`edc.iam.sts.oauth.*`-Block, `edc.iam.dcp.scopes.membership.*` und `edc.iam.trusted-issuer.*`.

**Neu / zu ergänzen:**

| Property | Wert | Warum |
|---|---|---|
| `tractusx.edc.participant.bpn` | `BPNL000000000PROV` / `…CONS` | Tractus-X-Pflicht |
| `tx.edc.iam.dcp.credentialservice.url` | `http://windx-identityhub:8183/api/credentials/v1/participants/<did-b64>` | sonst Auflösung übers DID-Dokument |
| `tx.edc.windx.bdrs.mappings` | s. AP2 | ersetzt BDRS |
| `tx.edc.vault.secrets` | `provider-sts-client-secret:<secret>;private-key:…;public-key:…` | AP1 |
| `web.http.med.port` / `.path`, `tx.edc.windx.mediator.base.url` | Mediator | windx-spezifisch |
| **entfällt** | `tx.edc.iam.sts.div.url` | **nicht setzen** → Standard-STS (s. §0) |

**Achtung DSP-Pfad:** bibas nutzt `/protocol`, unsere Dev-Config `/api/v1/dsp`. Beide Seiten sind unsere — Hauptsache
**konsistent**; `edc.dsp.callback.address` muss exakt dazu passen.

**🧪 Test:** Beide Configs einzeln per `docker run` booten (wie AP3), gegen **Dummy-Hostnamen**.
**✅ Abnahme:** Beide Runtimes booten sauber; im Log erscheinen die erwarteten Ports und keine
`Setting … not found`-Warnungen für die Pflicht-Keys.

---

### AP5 — K8s-Stack `windx-stack.yaml`

`kubernetes/bibas/bibas-stack.yaml` → `kubernetes/windx/windx-stack.yaml` kopieren, dann:

- Namespace `bibas` → `windx`, alle Namen/Hostnamen `bibas-*` → `windx-*`
- **IdentityHub-Deployment unverändert** (gleiches Image, gleiche Ports)
- Provider/Consumer: Image → `edc-runtime-windx`, ConfigMaps aus AP4, Mount-Pfad → `/app/configuration.properties`
- Init-Container `assemble-config` (merged STS-Secret in die Properties) bleibt im Prinzip — **oder** wird durch
  `tx.edc.vault.secrets` aus einem k8s-`Secret` ersetzt (sauberer)
- **`windx-setup`-Job:** ruft `org.eclipse.edc.sample.setup.IdentityHubSetup` — eine Klasse **aus dem alten
  Connector-JAR**. Sie spricht **ausschließlich die IdentityHub-HTTP-API** und ist vom Connector-Runtime
  unabhängig. → **Das alte `luf-bibas-connector`-Image für diesen Job weiterverwenden.** Spart die Portierung;
  ein späteres Nachziehen ins windx-Repo ist Backlog, kein Blocker.
- Ingress-Hosts `windx-*.tx.test` + `coredns-custom` + Windows-`hosts`

**🧪 Test (aufsteigend, jeder Schritt einzeln):**
```bash
kubectl -n windx get pods                                  # alle Running, Job Completed
kubectl -n windx logs deploy/windx-provider | tail -20     # "Runtime ... ready"
curl http://windx-identityhub.tx.test/provider/did.json    # did:web auflösbar → 200 + JSON
```
**✅ Abnahme:** 3 Pods `Running`, Setup-Job `Completed`, beide `did.json` per HTTP abrufbar.

---

### AP6 — Erster echter DSP-Call: Katalogabfrage

Das ist der Moment, in dem die **komplette Identitätskette** zum ersten Mal im Cluster arbeitet: Consumer holt
SI-Token beim STS → baut VP beim IdentityHub → Provider prüft VP gegen trusted-issuer → antwortet.

```bash
curl -X POST http://windx-consumer-mgmt.tx.test/management/v3/catalog/request \
  -H "X-Api-Key: password" -H "Content-Type: application/json" \
  -d '{
        "@context": {"@vocab":"https://w3id.org/edc/v0.0.1/ns/"},
        "counterPartyAddress": "http://windx-provider:18182/protocol",
        "counterPartyId": "BPNL000000000PROV",
        "protocol": "dataspace-protocol-http"
      }'
```

**✅ Abnahme:** **HTTP 200** mit Katalog-Body und `participantId: did:web:windx-identityhub.tx.test:provider` —
das exakte Erfolgskriterium, das der BIBA-Connector am 26.05.2026 erreicht hat.

**Wichtig:** `counterPartyId` ist hier ein **BPN** → genau hier greift `windx-bdrs-static` produktiv zum ersten Mal.
Schlägt der Call fehl, ist die Fehlersuche eng eingegrenzt (AP2-Mappings, trusted-issuer, VC-Format).

---

### AP7 — Negotiation + Transfer (HttpData zuerst, nicht gleich MMS)

Bewusst **erst der einfachste Transfertyp**, um die DCP-Kette von den windx-Spezifika zu trennen.

1. Provider: Asset (`HttpData`, Ziel z. B. der HttpLogger) + Access-Policy + Contract-Policy + ContractDefinition
   über `POST /management/v3/assets|policydefinitions|contractdefinitions`. Policies **permissiv** halten —
   cx-Constraints sind nicht zwingend.
2. Consumer: `POST /management/v3/contractnegotiations` → auf `FINALIZED` pollen.
3. Consumer: `POST /management/v3/transferprocesses` (`HttpData-PULL`) → auf `STARTED` pollen.
4. Consumer: `GET /management/v3/edrs/{id}/dataaddress` → EDR ziehen → mit dem EDR-Token die Provider-Public-API
   abrufen.

**✅ Abnahme:** Negotiation `FINALIZED`, Transfer `STARTED`, EDR vorhanden, Datenabruf liefert den erwarteten Body.
**Damit ist „Provider + Consumer verhandeln und übertragen im Cluster" erreicht** — das Kernziel.

---

### AP8 — windx-Transfertypen im Cluster

Erst jetzt die eigenen Typen draufheben, jeder einzeln, jeder mit dem AP7-Muster:

| Typ | Asset-`dataAddress.type` | Abnahme |
|---|---|---|
| Mediator | `MediatorHttpData` (Transfer `MediatorHttpData-PULL`) | GET **und** PUT über den `med`-Controller |
| MMS-PULL | `Mms` | Aggregation vom echten IEC-61850-Server (`agilevt-dev…:102`) |
| MMS-Light | `MmsLight` | roher TCP-Tunnel; Gegenprobe: **ohne** aktiven Transfer ist der Port **zu** (das war das Gate in der 01.06.-Demo) |
| MMS-PUSH continuous | `HttpData`-PUSH, non-finite | Strom läuft bis `terminate` |

**✅ Abnahme:** Für jeden Typ existiert der In-Process-Beweis bereits (`MediatorConsumerPullTest`,
`NonFiniteSinkConsumptionTest`, Hardware-ITs) — im Cluster wird also **nur die Betreibbarkeit** nachgewiesen, nicht
die Fachlogik. Fehler hier sind Config-/Netzwerkfehler, keine Logikfehler.

---

## 3. Teststrategie im Überblick

Der Plan ist so geschnitten, dass **jeder Fehler in dem AP auffällt, der ihn verursacht** — statt am Ende als
diffuses „Negotiation schlägt fehl".

| Ebene | Wo | Was sie beweist | Kosten |
|---|---|---|---|
| Unit | AP1, AP2 | Vault-Seeding, BPN↔DID-Auflösung, Audience-Resolver | Sekunden |
| Classpath/Boot | AP2, AP3, AP4 | `exclude` greift; richtige Extensions geladen; Config vollständig | Sekunden |
| **In-Process-DCP-E2E** | **AP2** | **beide Code-Änderungen in echter Negotiation + Transfer — ohne Cluster** | Minuten |
| Cluster-Smoke | AP5 | Pods, did:web, IdentityHub | Minuten |
| Cluster-DSP | AP6 | volle Identitätskette über Prozessgrenzen | Minuten |
| Cluster-E2E | AP7 | Negotiation + Transfer + EDR + Pull | Minuten |
| Fachtypen | AP8 | Betreibbarkeit der windx-Typen | Stunden |

**Regressionsanker:** `MediatorConsumerPullTest` muss nach AP1, AP2 und AP4 unverändert grün bleiben — **nur in der Linux-VM lauffähig**, s. [uebergabe-ap0-ap2-debian-vm.md](uebergabe-ap0-ap2-debian-vm.md).

---

## 4. Risiken

| Risiko | Wahrsch. | Wirkung | Gegenmaßnahme |
|---|---|---|---|
| **VC-Format**: `cx-dcp`/`cx-policy` erwarten ggf. cx-geprägte Claims (BPN/`holderIdentifier`), der IdentityHub hält plain-EDC-`MembershipCredential` | **mittel** | AP6 schlägt fehl | Erst mit dem vorhandenen VC testen; falls Ablehnung → VC im Setup-Job um BPN-Claim erweitern (~0,5–1 d). **Größtes Einzelrisiko.** |
| `exclude` auf internem Projekt greift nicht | niedrig | AP2 blockiert | Nachweis in AP2/Stufe 2; Fallback: echter BDRS-Server |
| Fehlender `AudienceResolver` nach dem Exclude | mittel | erster DSP-Call scheitert | In AP2 explizit mit-provided; In-Process-E2E deckt es ab |
| DSP-Pfad/-Version inkonsistent (`/protocol` vs. `/api/v1/dsp`) | mittel | AP6 scheitert | In AP4 fixieren; beide Seiten sind unsere |
| `credentialservice.url` cross-namespace nicht erreichbar | niedrig | VP-Bau scheitert | FQDN statt Kurzname (der Fix aus Jira-132 ist im bibas-Job dokumentiert) |

---

## 5. Aufwand

| AP | Inhalt | Aufwand |
|---|---|---|
| AP0 | Baseline | 0,25 d |
| AP1 | `VaultSeedExtension` | **0,5 d** |
| AP2 | `windx-bdrs-static` inkl. In-Process-E2E | **1 d** |
| AP3 | Image + Harbor | 0,5 d |
| AP4 | Config-Übersetzung | 1 d |
| AP5 | `windx-stack.yaml` | 0,5 d |
| AP6 | Katalogabfrage grün | 0,5 d |
| AP7 | Negotiation + Transfer (HttpData) | 0,5 d |
| AP8 | windx-Transfertypen | 1 d |
| — | DCP-Debugging-Puffer (v. a. VC-Format) | 1–2 d |
| | **Summe** | **~5,5–7 d** (Happy Path ~4 d) |

**Code-Anteil: ~1,5 d (AP1+AP2).** Der Rest ist Config, Deployment und Debugging — Posten, die auf *jedem* Weg
anfallen. Der Kernwert dieses Plans: Die teuersten Bausteine des MVD-Wegs (Issuer, did:web-Hosting,
Identitäts-Seeding) **existieren bereits und laufen** — wir fassen sie nicht an.

---

## 6. Nicht in diesem Plan

- **Umbrella-Anschluss (Jira-132):** unser Connector ↔ der im BIBA deployte Tractus-X-EDC. Dort gilt der
  **DIM/DIV-Konflikt** (Umbrella fährt Chart 0.11.2 + `ssi-dim-wallet-stub`; unser 0.13 kennt DIM nicht mehr,
  nur noch `tx-dcp-sts-div`). Eigene Analyse nötig.
- **Produktions-Härtung:** PostgreSQL statt In-Memory, HashiCorp-Vault statt Seed-Vault, echter BDRS-Server,
  Helm-Chart statt `windx-stack.yaml`.
- **Portierung von `IdentityHubSetup`** ins windx-Repo (Backlog; solange läuft der Job auf dem alten Image).

---

## 7. Belege

- STS-Fallback: `edc-extensions/dcp/tx-dcp-sts-div/.../RemoteTokenServiceClientExtension.java` (`@Provider secureTokenService()`)
- BDRS-Pflicht + kein DID-Durchreichen: `edc-extensions/bdrs-client/.../BdrsClientExtension.java` (`@Setting BDRS_SERVER_URL_PROPERTY`), `BdrsClientImpl#resolveDid`
- Zweiter Service am BDRS-Modul: `BdrsClientMapperExtension.java` (`@Provider AudienceResolver`)
- Vault: `edc-extensions/single-participant-vault/.../InMemorySingleParticipantVault.java` (leer beim Start), `edc-controlplane/edc-runtime-memory/.../VaultSeedExtension.java` (`tx.edc.vault.secrets`, Split auf `;`/`:`)
- Mock als Vorlage: `edc-tests/e2e-fixtures/.../MockBdrsClient.java`; Registrierung: `MediatorConsumerPullTest` Z. 130–133, `Runtimes.java` Z. 57
- `dockerize`: Root-`build.gradle.kts` Z. 114–160; `resources/Dockerfile` (Entrypoint `/app/configuration.properties`)
- Cluster-Vorlage: `kubernetes/bibas/bibas-stack.yaml`, `kubernetes/bibas.md` (Stand 26.05.2026 „Läuft"), `jira-tasks/126/mms-light-demo.md` (01.06.2026)
