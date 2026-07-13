# Zwei echte DCP-Prozesse (Provider + Consumer) — Recherche & Implementierungsplan

**Datum:** 2026-07-08
**Ziel:** Zwei **separate** windx-EDC-Prozesse (Provider + Consumer) lokal betreiben, die eine **echte
Contract-Negotiation über DCP** durchführen und danach **Daten übertragen** (Provider → Consumer), mit unseren
windx-Extensions (Mediator, mms, mms-light, non-finite-Push).
**Status:** Recherche abgeschlossen (3 Code-Analysen). Kein Code. Enthält Aufwandsschätzung + Bauplan.
**Kernaussage:** Machbar, aber **substanziell** — es ist im Kern der Bau eines *Minimum Viable Dataspace (MVD)*.
Die vollständige Verdrahtung existiert bereits **in-process als E2E-Test** (`edc-tests/e2e/dcp-tests`); die Arbeit ist,
diese aus Test-Fixtures in **echte Prozesse + Config + externe Identitäts-Services** zu übersetzen.

---

## 1. Warum das nicht „zwei Dev-Runtimes starten" ist

Eine echte DCP-Negotiation verlangt beidseitig: eine **DID** (did:web), **Verifiable Credentials**
(v.a. `MembershipCredential`), einen **IdentityHub** (baut Presentations), einen **STS** (Secure Token Service,
Self-Issued-Tokens), **BDRS** (BPN↔DID-Auflösung) und einen **vertrauten Issuer**. Unsere
`edc-runtime-windx` + `windx-dev.properties` decken davon nur die **Presence-Checks mit Dummies** ab → sie bootet,
aber jede Negotiation scheitert an der Credential-Prüfung.

---

## 2. Was schon existiert (bootbar) — Inventar

| Baustein | Zustand | Beleg |
|---|---|---|
| **`edc-runtime-windx`** (unser Connector) | ✅ echtes Standalone-JAR (Shadow-JAR, CP+DP + windx) — **aber ohne IH/STS/DCP-Stack** | `edc-runtime-windx/build.gradle.kts` (Shadow), `windx-dev.properties` (Dummy) |
| **`runtime-memory-dcp-ih`** | voller DCP+**eingebetteter IdentityHub**-Stack, aber **test-only** (kein Shadow, `publish=false`, config-los) | `ih.core/keypairs/participants/api.presentation/did` + `sts.account.provisioner` + `cx-dcp`/`tx-dcp` |
| **`runtime-memory-dcp-ih-windx`** | = dcp-ih **+ unsere windx-Extensions**, test-only | `build.gradle.kts` |
| **`runtime-memory-sts`** | Standalone **STS + zentraler IdentityHub/CredentialStore**, test-only | `bundles.edc.sts` + `ih.*` |
| **BDRS-Server** | ⚠️ **kein Repo-Modul** — offizielles Docker-Image `tractusx/bdrs-server-memory:0.5.7` | `BdrsServerExtension` Z.44 |
| **BDRS-Client** (echt) | ✅ im Repo (`edc-extensions/bdrs-client`, `BdrsClientImpl`) — in Tests aber gemockt | `MockBdrsClient` |
| **Issuer** | ❌ nur Test-Klasse `DataspaceIssuer` (signiert VCs, ES256-JWT), **kein Prozess** | `DataspaceIssuer.java` |
| **Produktive Runtimes** | ✅ `edc-controlplane-postgresql-hashicorp-vault` + `edc-dataplane-hashicorp-vault` (Helm-Basis), erwarten **externen** STS/BDRS/IH | `charts/tractusx-connector` |
| **Lokale Mehr-Connector-Referenz (compose/MVD/MXD)** | ❌ existiert **nicht** im Repo | — |

**Fazit:** Alle Runtime-Bausteine sind vorhanden (teils nur test-only, ohne Shadow-JAR/Config). Der einzige Baustein
ohne Repo-Modul ist der **BDRS-Server** (aber als fertiges Docker-Image verfügbar). Es gibt **keine** fertige
Zwei-Prozess-Referenz — die einzige komplette Verdrahtung ist der In-Process-E2E-Test.

---

## 3. Die Blaupause: was der In-Process-E2E-Test hochzieht

`MediatorConsumerPullTest` / `DivConsumerPullTest` (dcp-tests) starten im selben JVM mehrere `EmbeddedRuntime`s:
- **Consumer-Connector** (`runtime-memory-dcp-ih`) + **Provider-Connector** (`runtime-memory-dcp-ih-windx`)
- **STS + zentraler IdentityHub** (`runtime-memory-sts`)
- **DID-Server** (`DidServerExtension`, WireMock, serviert `/{name}/.well-known/did.json`)
- **BDRS** (echt: `BdrsServerExtension`-Container; oder Docker-frei: `MockBdrsClient`)
- **Issuer** (`DataspaceIssuer`, signiert 4 VCs: Membership/Dismantler/Bpn/DataExchangeGovernance)

**Identität** (aus `TractusxParticipantBase` + `TractusxDcpParticipantBase.dcpConfig` + `DcpParticipant`):
`edc.participant.id = DID`, `tractusx.edc.participant.bpn`, `edc.iam.issuer.id = DID`,
`edc.iam.sts.oauth.token.url/client.id/client.secret.alias`, `edc.ih.iam.id/publickey.alias`,
`edc.iam.trusted-issuer.issuer.id`, `edc.iam.did.web.use.https=false`, `web.http.did.*`, `web.http.credentials.*`,
Signer/Verifier-Key-Aliase, DSP-Protokoll `dataspace-protocol-http:2025-1`.

**Ablauf** eines Consumer-Pull (Management-API): Provider legt Asset + Access-Policy (BPN) + Contract-Policy
(Framework/Membership) + ContractDefinition an → Consumer `getCatalog` → `requestAssetFrom(...).withTransferType(...)
.execute()` (Negotiation + Transfer) → auf `STARTED` warten → `edrs().getEdr()` → `data().pullData(edr)`.

---

## 4. Die Lücke: In-JVM-Abkürzungen, die prozessübergreifend brechen

Genau hier steckt die Arbeit. Der Test nimmt vier Abkürzungen, die zwischen zwei OS-Prozessen **nicht** funktionieren:

| # | Test-Abkürzung (in-JVM) | Muss für 2 Prozesse werden |
|---|---|---|
| 1 | **`MockBdrsClient`** via `registerServiceMock(BdrsClient.class, …)` | Echter **BDRS-Server** (Docker-Image) + echter `bdrs-client` im Classpath; Mappings via `/management/bpn-directory` seeden |
| 2 | **`DidPublicKeyResolver`-Mock** + statischer `KeyPool` (löst Keys per keyId auf) | Echtes **did:web-Hosting** je Prozess (IH `web.http.did` serviert `did.json`) + Mock entfernen → `edc.core.did` löst per HTTP auf |
| 3 | **`configureParticipant()`** schreibt via `getService(...)` direkt in `CredentialStore`/`ParticipantContextService`/`KeyPairService`/`StsAccountStore`/`Vault` | **Seeding über HTTP-APIs** (IdentityHub Identity-/Management-API) **oder** ein Provisioning-/Startup-Seeding-Tool |
| 4 | **`DataspaceIssuer`** (Test-Klasse) preloaded signierte VCs direkt | **Issuer-Schritt als Tool/Service**: VCs (ES256-JWT) signieren + über IH-API einspielen |

Dazu: **feste Ports** statt `getFreePort()`, **stabile DIDs/URLs**, **Vault-Secrets beim Start real seeden**
(private Keys unter `did#keyId`/`privateKeyAlias`, `client_secret`, Runtime-Signing-JWK).

---

## 5. Zielarchitektur (empfohlen)

**Topologie „all-in-one Connector" (wenigste Prozesse):** jeder Connector bettet IdentityHub (+ STS) ein — Basis
`runtime-memory-dcp-ih(-windx)`. Damit:

```
                         ┌─────────── BDRS-Server (Docker: tractusx/bdrs-server-memory) ───────────┐
                         │  BPN→DID Directory (/directory, /management)                            │
                         └──────────────▲───────────────────────────────▲─────────────────────────┘
                                        │ resolve                        │ resolve
   ┌────────────────────────────────────┴───┐          ┌────────────────┴────────────────────────┐
   │  CONSUMER-Prozess                        │   DSP    │  PROVIDER-Prozess (+ windx-Extensions)    │
   │  Connector (CP+DP) + IdentityHub + STS   │◄────────►│  Connector (CP+DP) + IdentityHub + STS    │
   │  did:web (eigenes did.json über :did)    │  Nego +  │  did:web (eigenes did.json)               │
   │  Vault (Keys, Secrets, VCs)              │  Transfer│  Vault (Keys, Secrets, VCs)               │
   └──────────────────────────────────────────┘         └───────────────────────────────────────────┘
                    ▲ Seeding (Keys, ParticipantContext, MembershipCredential, STS-Account)  ▲
                    └──────────────── Issuer/Seeding-Tool (einmalig beim Start) ──────────────┘
```

**Offene Design-Entscheidung — STS:** *embedded* (Connector self-issued, kein STS-Prozess — einfachster Weg,
weniger Teile) vs. *remote* (separater `runtime-memory-sts`-Prozess, produktionsnäher wie im dcp-tests-Setup).
Empfehlung fürs erste Ziel: **embedded STS** (falls die IH-Runtime das self-issued unterstützt — verifizieren).

**Prozess-Zahl (Minimal):** 2 Connectoren + 1 BDRS-Container + 1 Seeding-Schritt (Tool, kein Dauerprozess).

---

## 6. Zwei strategische Wege

### Weg X — „from scratch" mit dcp-tests als Vorlage (volle Kontrolle)
Alles selbst verdrahten: Runtime-Packaging, Config, BDRS-Container, did:web, Seeding-Tool. Vorteil: exakt auf unser
windx-Setup zugeschnitten, kein Fremd-Repo. Nachteil: der volle MVD-Aufbau inkl. DCP-Debugging.

### Weg Y — Eclipse **MinimumViableDataspace (MVD)** adaptieren  ⭐ empfohlen zu prüfen
Der Upstream **MVD** (`github.com/eclipse-edc/MinimumViableDataspace`, nicht in diesem Repo) ist **genau** ein
lauffähiger Multi-Prozess-DCP-Dataspace mit Issuer, BDRS, did:web, Seeding und docker-compose. Statt from scratch:
das MVD-Gerüst nehmen und den Connector durch **unser windx-Image** (`edc-runtime-windx` + IH/STS/DCP-Module)
ersetzen. Spart potenziell den Großteil von WP3–WP5 (Seeding/Issuer/did:web sind dort gelöst). **Risiko:**
Versions-/API-Drift MVD ↔ unsere EDC 0.17 + tractus-x-Spezifika (BDRS, cx-Policies). **Erste Aufgabe: MVD-Version
gegen EDC 0.17 prüfen.**

---

## 7. Implementierungsplan (Weg X, in Arbeitspaketen)

| WP | Inhalt | Ergebnis | Aufwand |
|---|---|---|---|
| **WP1 — Runtime-Packaging** | `runtime-memory-dcp-ih-windx` einen `shadowJar` geben (oder `edc-runtime-windx` um `ih.*`/`sts`/`bdrs-client`/`core.did`-Module erweitern) → startfähiges Connector-JAR mit vollem DCP-Stack | 1 lauffähiges windx-DCP-Connector-JAR | 0.5–1 d |
| **WP2 — Config-Dateien** | `provider.properties` + `consumer.properties`: feste Ports (management/dsp/public/control/did/credentials), DID, BPN, STS-Keys, BDRS-URL, Signer/Verifier-Aliase (Key-Liste aus §3) | 2 bootbare Configs | 0.5–1 d |
| **WP3 — BDRS-Server** | Docker-Image `tractusx/bdrs-server-memory` starten; `bdrs-client` real aktivieren (Mock raus); Mappings (Provider-BPN→DID, Consumer-BPN→DID) via `/management/bpn-directory` seeden | laufender BDRS + Mappings | 0.5–1 d |
| **WP4 — did:web-Hosting + Auflösung** | Jeder Connector serviert sein `did.json` über `web.http.did`; DIDs als `did:web:host%3Aport…`; `edc.iam.did.web.use.https=false`; verifizieren, dass Gegenseite per HTTP auflöst | gegenseitige DID-Auflösung | 1–2 d |
| **WP5 — Identitäts-Seeding** (Kernstück) | Seeding-Tool/Skript: pro Participant Keypair erzeugen, ParticipantContext anlegen, `MembershipCredential` (+ ggf. Framework) als **ES256-JWT** signieren (Vorlage `DataspaceIssuer`), in IdentityHub laden, STS-Account anlegen, Vault-Secrets setzen — über die **IH-HTTP-APIs** statt In-JVM | beide IHs haben gültige VCs/Keys | 3–5 d |
| **WP6 — STS + Trusted-Issuer** | `edc.iam.trusted-issuer.issuer.id` beidseitig setzen; STS-OAuth (embedded oder remote) verdrahten; SI-Token-Fluss testen | Token-Fluss steht | 1–2 d |
| **WP7 — Vault** | Entscheidung `single-participant-vault` (In-Mem, Seeding bei jedem Boot) vs. HashiCorp-Dev-Vault; Secret-Seeding automatisieren | reproduzierbare Secrets | 0.5–1 d |
| **WP8 — Orchestrierung + Walkthrough** | Start-Reihenfolge als Skript/`docker-compose` (BDRS → Seeding → Connectoren); curl-/Postman-Walkthrough: Asset+Policy+ContractDef (Provider) → Catalog → Negotiation → Transfer → EDR → `pullData` | reproduzierbarer End-to-End-Lauf | 1–2 d |
| **WP9 — Integration & DCP-Debugging** | Puffer: Credential-Format/Scopes, DID-Encoding, trusted-issuer, Clock-Skew, Presentation-Definitionen, cx-Policy-Constraints | grüner Zwei-Prozess-Transfer | 2–4 d |

---

## 8. Aufwandsschätzung

| Variante | Umfang | Schätzung |
|---|---|---|
| **Happy-Path-Minimal** (Weg X) | embedded STS, In-Mem-Vault mit Boot-Seeding, 1 BDRS-Container, geskriptetes Seeding, HttpData-Demo-Transfer | **~6–9 Personentage** |
| **Robust/produktionsnah** (Weg X) | remote STS, HashiCorp-Vault, Issuer als Service, sauberes did:web, windx-Transfertypen (Mms-PULL etc.) end-to-end, docker-compose | **~15–22 Personentage** |
| **Weg Y (MVD adaptieren)** | *Falls* MVD gegen 0.17 passt: Gerüst wiederverwenden, Connector-Image tauschen | **~4–8 Personentage** (+ Risiko Versions-Drift) |

**Dominante Kosten & Risiken:** WP5 (Identitäts-Seeding) und WP9 (DCP-Debugging) — zusammen ~50–60 % des Aufwands.
DCP ist bekannt fehleranfällig (Credential-Format VC1_0_JWT, Presentation-Scopes, did:web-Port-Encoding,
trusted-issuer-Abgleich, Uhr-Differenzen). Der In-Process-E2E-Test ist ein **enormer De-Risking-Faktor**: er liefert
für jeden Baustein die exakte, funktionierende Referenz-Config.

---

## 9. Empfehlung

1. **Zuerst Weg Y bewerten** (0.5 d): MVD-Version ↔ EDC 0.17 prüfen. Passt es, ist es der schnellste Weg.
2. Sonst **Weg X, Happy-Path-Minimal** als erstes Ziel: embedded STS + In-Mem-Vault-Boot-Seeding + BDRS-Container +
   ein **einfacher HttpData-Transfer** (nicht gleich Mms) — um die DCP-Kette zwischen zwei Prozessen grün zu bekommen.
3. Erst danach die **windx-Transfertypen** (Mms-PULL, mms-light, continuous-Push) auf den Zwei-Prozess-Aufbau heben
   und die Härtung (WP6/7 robust, docker-compose) nachziehen.
4. **Nicht vergessen:** dass die Funktionen *fachlich* funktionieren, ist bereits bewiesen (In-Process-E2E, 5/5 grün).
   Dieser Aufwand kauft **Betreibbarkeit als getrennte Prozesse**, nicht neue Funktionalität.

---

## 9b. MVD-/MXD-Abgleich (durchgeführt 2026-07-13)

**Ziel des Abgleichs:** Punkt 1 der Empfehlung — passt eine fertige Referenz-Dataspace-Vorlage zu unserer
Version, sodass wir das Gerüst wiederverwenden statt WP3–WP5 selbst zu bauen?

### Versionslage (Äpfel-mit-Äpfeln)
| Aufbau | tractusx-edc-Version | Upstream-EDC-Version | Identitätsmodell | Start |
|---|---|---|---|---|
| **Unser Workspace** | 0.13.0-SNAPSHOT | **0.17.0** (edc-build 1.5.2) | tractus-x (BDRS-Client verdrahtet) | Java/Test |
| **Upstream-MVD Tag `0.17.0-dps`** | — (plain EDC) | **0.17.0** (edc-build 1.5.2) ✅ **exakt** | plain-EDC **did:web direkt, kein BDRS** | k8s (KinD + Kustomize) |
| **Upstream-MVD Tag `0.17.0`** | — | 0.17.0 | plain-EDC did:web | k8s |
| **MXD** (`tutorial-resources/mxd`) | **0.8.0** ❌ (weit hinter uns) | ~0.8-era (alte SPIs) | **tractus-x voll: BDRS + cx-policies + Issuer** | Terraform + KinD + Helm |
| MVD `main` | — | 0.19.0-SNAPSHOT ❌ | did:web | k8s |

### Kernbefund
Es gibt einen **versionsgenau passenden** getaggten MVD-Release: **`0.17.0-dps`** (12. Mai 2026) mit
Upstream-EDC **0.17.0** und edc-build **1.5.2** — beides **identisch** zu unserem Stack. „dps" = Data-Plane-
Signaling, also **genau unser 0.17-Modell**. Damit ist die im Plan als offener Risikofaktor genannte
**Versions-/API-Drift für den MVD-Weg praktisch beseitigt** (unsere SPIs kommen aus Upstream-EDC 0.17.0, MVD
`0.17.0-dps` kompiliert gegen dieselben SPIs).

Das **Trade-off** ist damit klar und nicht mehr „Version", sondern **Identitätsmodell vs. EDC-Version**:

- **Upstream-MVD `0.17.0-dps`** = **richtige EDC-Version, aber plain-EDC-Identität** (did:web direkt,
  **kein BDRS**, keine cx-Policies). Löst aber **genau die 4 In-JVM-Abkürzungen** auf: echtes did:web-Hosting,
  echter **Issuer-Service**, automatisiertes **Seed** (k8s-Batch-Jobs) und echte **IdentityHub↔IdentityHub**-
  Auflösung. Start via KinD + Kustomize (kein docker-compose im Repo).
- **MXD** = **richtiges Identitätsmodell** (BDRS + cx-Policies + Issuer, passt zu unserem BDRS-Client), aber
  **tractusx-edc 0.8.0** → alte EDC-SPIs, große Drift zu unserem 0.17-Code. MXD baut Runtimes aus Quelltext
  (`./gradlew dockerize`), d. h. ein Hochziehen auf 0.17 wäre möglich, aber substanziell — genau die Arbeit,
  die wir vermeiden wollten.

### Konsequenz für die Empfehlung
**Weg Y ist bestätigt der schnellste Pfad — aber mit Upstream-MVD `0.17.0-dps` als Basis, nicht MXD.** Begründung:
Die dominante Drift-Quelle ist die **Upstream-EDC-Version** (daher kommen die SPIs, gegen die unser windx-Code
kompiliert). MVD `0.17.0-dps` = null SPI-Drift; MXD 0.8.0 = große SPI-Drift. Das fehlende Stück bei MVD (BDRS)
ist ein **additives** Problem, kein Umbau: für einen ersten grünen E2E MVDs **direktes did:web** nutzen
(BDRS weglassen, `MockBdrsClient`-Pfad entfällt), später optional den fertigen Container
`tractusx/bdrs-server-memory:0.5.7` in MVDs Compose/k8s ergänzen, um tractus-x-Prod-Parität herzustellen.

**Konkreter Fahrplan (ersetzt Empfehlungs-Punkt 1/2):**
1. MVD Tag `0.17.0-dps` klonen, unverändert per KinD hochziehen → verifizieren, dass der Referenz-Dataspace
   grün läuft (Katalog + Negotiation + HttpData-Transfer zwischen zwei Prozessen). *De-Risking, ~0.5–1 d.*
2. **Connector-Runtime tauschen:** MVDs Standard-Connector-Image durch unser `edc-runtime-windx`
   (+ IH/STS/DCP-Module) ersetzen. Beide auf EDC 0.17.0 → SPI-kompatibel. *~1–2 d.*
3. Erst **plain did:web** (kein BDRS) für den ersten grünen windx-E2E; **danach** optional BDRS-Container +
   `BdrsClientImpl` für tractus-x-Parität. *BDRS additiv, ~1 d.*
4. Dann die **windx-Transfertypen** (Mms-PULL, mms-light, continuous-Push) draufheben.

**Revidierte Schätzung Weg Y:** durch den versionsgenauen Tag rutscht der Aufwand an das **untere Ende
(~4–6 PT)** und der Posten „Risiko Versions-Drift" **entfällt weitgehend**. MXD bleibt nur **Sekundär-Referenz**
für die tractus-x-spezifische Verdrahtung (BDRS-Chart, cx-Policies), nicht als Basis.

**Offener Rest-Check (klein, ~0.5 d):** Ob unser tractus-x-geprägtes `edc-runtime-windx` sauber in MVDs
plain-EDC-did:web-Kontext bootet (Vault/STS/Trusted-Issuer-Config), oder ob einzelne tractus-x-Defaults
(z. B. erzwungener BDRS-Client) für den ersten Lauf deaktiviert werden müssen.

**Quellen des Abgleichs:** MVD Tags-Seite (`0.17.0`, `0.17.0-dps` vom 12.05.2026); MVD `0.17.0-dps`
`gradle/libs.versions.toml` (edc=0.17.0, edc-build=1.5.2) + `README.md` (KinD/Kustomize, did:web, Seed-Jobs,
Issuer, kein BDRS); MXD `mxd-runtimes/gradle.properties` (tractusx-edc 0.8.0), `mxd/README.md` (Terraform+KinD+
Helm, BDRS via bpn-did-resolution-service); unser `tractusx-edc/gradle.properties` (0.13.0-SNAPSHOT) +
`gradle/libs.versions.toml` (edc=0.17.0).

---

## 10. Quellen (code-belegt)
- Blaupause: `edc-tests/e2e/dcp-tests` — `MediatorConsumerPullTest`, `AbstractDcpConsumerPullTest`, `StsConsumerPullTest`,
  `dcp/runtime/Runtimes.java`, `DcpParticipantRuntimeExtension`, `extension/BdrsServerExtension`, `extension/DidServerExtension`.
- Fixtures: `edc-tests/e2e-fixtures` — `TractusxParticipantBase`, `TractusxDcpParticipantBase`, `DcpParticipant`,
  `DataspaceIssuer`, `MockBdrsClient`, `TestRuntimeConfiguration`; compat: `IdentityHubParticipant`, `DcpHelperFunctions`.
- Runtimes: `edc-tests/runtime/dcp/{runtime-memory-dcp-ih, -windx, -div-ih, runtime-memory-sts}`; `edc-controlplane/edc-runtime-windx`.
- Identität-Config-Oberfläche: `charts/tractusx-connector/values.yaml` (participant/dcp/sts/bdrs/token/vault).
- BDRS: `edc-extensions/bdrs-client` (`BdrsClientImpl`), Image `tractusx/bdrs-server-memory:0.5.7`.
