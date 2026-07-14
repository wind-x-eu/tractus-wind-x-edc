# Übergabe: AP0–AP2 abschließen (Debian-VM)

**Datum:** 2026-07-14
**Für:** den nächsten Agenten / Entwickler, der in der **Debian-VM** weiterarbeitet.
**Vorgeschichte:** [phase-cluster-deployment-plan.md](phase-cluster-deployment-plan.md) — AP1 und AP2 sind
**implementiert und committed**. Was fehlt, sind genau die Prüfschritte, die **unter Windows nicht laufen**.

---

## 1. Warum diese Übergabe existiert

Die gesamte windx-Entwicklung (Commit `f80f7b11`) entstand in der **Debian-VM**
(`flo@debian.myguest.virtualbox.org`). Die DCP-E2E-Tests waren dort grün (Doku: 2026-06-30, 2026-07-07).

AP0–AP2 wurden auf **Windows** (Git Bash / MINGW64) umgesetzt. Dort gilt:

> **Die DCP-E2E-Suite (`edc-tests/e2e/dcp-tests`) läuft unter Windows nicht.**
> Schon die STS-Test-Runtime (`runtime-memory-sts`, **Upstream-Modul**) bricht beim Boot in der
> Dependency-Injection ab — es fehlen EDC-Kernservices (`TypeManager`, `PrivateKeyResolver`,
> `CriterionOperatorRegistry`, `KeyPairService`). Ursache: EDCs `EmbeddedRuntime` baut den Classpath der
> Test-Runtimes selbst zusammen (sie sind bewusst nur `testCompileOnly`), und das schlägt unter Windows fehl.

**Abgesichert, dass es nicht an unserem Code liegt:**
1. Änderungen ge­stasht → auf unverändertem `HEAD` **derselbe** Fehler.
2. Der **Upstream**-Test `StsConsumerPullTest` scheitert **identisch**.
3. Der in der Doku protokollierte Befehl (`phase-identitaet-weg-a.md:43`) scheitert wortgleich.

→ In der VM ist das aller Voraussicht nach **kein Problem**. Bitte als Erstes verifizieren (Schritt 3.1).

---

## 2. Was fertig ist (bereits committed)

### AP1 — `VaultSeedExtension` in `edc-runtime-windx`
- `edc-controlplane/edc-runtime-windx/src/main/java/org/eclipse/tractusx/edc/windx/vault/VaultSeedExtension.java`
- `.../src/main/resources/META-INF/services/org.eclipse.edc.spi.system.ServiceExtension`
- `.../src/test/java/.../VaultSeedExtensionTest.java` — **5 Tests**

Liest `tx.edc.vault.secrets` (Format `alias:secret;alias:secret`, Trennung am **ersten** `:`).

> **⚠️ Abweichung vom Plan (bewusst):** Das Seeding passiert in **`initialize()`**, nicht — wie im Original
> aus `edc-runtime-memory` — in einem zweiten `@Provider` für `Vault`. Grund: Der Provider-Weg erzeugte im
> Boot-Log die Warnung *„A service of the type Vault was already registered and has now been replaced with a
> InMemorySingleParticipantVault instance"*, **nach** dem Seeding. Ob dabei dieselbe Instanz re-registriert
> oder eine frische erzeugt wird, hängt an der Registrierungsreihenfolge — im zweiten Fall wären die Secrets
> **still verloren** und der Fehler tauchte erst als kryptischer Token-/Signatur-Fehler bei der ersten
> Negotiation auf. Mit `initialize()` ist der Vault final aufgelöst; die Warnung ist verschwunden.

### AP2 — Modul `windx-bdrs-static` (ersetzt den BDRS-Server)
- `edc-extensions/windx/windx-bdrs-static/` — `StaticBdrsClient` (BPN↔DID aus Config, Format `BPN=DID;BPN=DID`),
  `StaticAudienceResolver`, `WindxStaticBdrsExtension`
- Tests: **11 + 4**
- Verdrahtung: `settings.gradle.kts` (`include`) und in `edc-runtime-windx/build.gradle.kts`:
  ```kotlin
  implementation(project(":edc-controlplane:edc-controlplane-base")) {
      exclude("org.eclipse.tractusx.edc", "bdrs-client")
  }
  implementation(project(":edc-extensions:windx:windx-bdrs-static"))
  ```

Das Modul liefert **beide** `@Provider`, die sonst `bdrs-client` beisteuert: `BdrsClient` **und**
`AudienceResolver`. Das Pflicht-Setting `tx.edc.iam.dcp.bdrs.server.url` entfällt damit ersatzlos.

### Auf Windows bereits verifiziert
| Prüfung | Ergebnis |
|---|---|
| Unit-Tests (3 Klassen) | **20/20 grün** |
| `exclude` greift (`dependencies --configuration runtimeClasspath`) | `:edc-extensions:bdrs-client` **nicht mehr enthalten** |
| Boot der Runtime (Shadow-JAR) | `Runtime … ready` |
| Boot-Log | `Wind-X Vault Seed Extension: seeded 3 secret(s)` · `Wind-X Static BDRS Extension: 2 BPN/DID mapping(s) configured` · alte BDRS-Extension **0×** · Vault-Replace-Warnung **0×** |

---

## 3. Was in der VM zu tun ist

### 3.1 Zuerst: läuft die E2E-Suite hier überhaupt? (5 Min)

```bash
./gradlew :edc-tests:e2e:dcp-tests:test --tests "*MediatorConsumerPullTest" \
    -DincludeTags="EndToEndTest" -PverboseTest=true
```

- **Grün (5 Tests)** → weiter mit 3.2. Damit ist auch der **Regressionsanker** etabliert: AP1/AP2 haben
  nichts kaputtgemacht.
- **Rot mit „Problems occurred during dependency injection" in der STS-Runtime** → dann ist es **kein**
  Windows-Problem, sondern ein echter Repo-/Umgebungsdefekt. Dann bitte **stoppen** und melden; nicht in
  die Classpath-Mechanik von `EmbeddedRuntime` einsteigen, ohne das abzustimmen.

> **Fallstrick:** `./gradlew … | tail` **maskiert Gradles Exit-Code** (der Exit-Code kommt dann von `tail`).
> Entweder nicht pipen oder `set -o pipefail` / `${PIPESTATUS[0]}` prüfen. Mich hat das eine Runde gekostet:
> ein fehlgeschlagener Lauf sah wie „exit 0" aus.
>
> **Zweiter Fallstrick:** Ohne `-DincludeTags="EndToEndTest"` findet Gradle **null Tests** (die Klasse ist
> `@EndToEndTest`-getaggt) und meldet trotzdem nicht offensichtlich einen Fehler.

### 3.2 AP0/AP1-Regression: Unit-Tests + Anker

```bash
./gradlew :edc-controlplane:edc-runtime-windx:test :edc-extensions:windx:windx-bdrs-static:test
# erwartet: 5 + 11 + 4 = 20 Tests, 0 failures
```

### 3.3 AP2 / Stufe 3 — **der eigentlich offene Punkt**: In-Process-DCP-Beweis

**Ziel:** Beweisen, dass `windx-bdrs-static` in einer **echten DCP-Kette** funktioniert (Negotiation →
Transfer → EDR → Pull), nicht nur im Unit-Test.

**Warum das der stärkste Test ist:** `MediatorConsumerPullTest` registriert heute in **Z. 130–133** auf
**beiden** Runtimes einen `MockBdrsClient` (plus `bdrsDummyConfig()`, Z. 388–392). Unser `StaticBdrsClient`
ist der **produktive Zwilling genau dieses Mocks**. Wer den Mock durch die echte Implementierung ersetzt und
die 5 Tests bleiben grün, hat die Extension in einer echten DCP-Negotiation bewiesen — ohne einen Pod.

**Empfohlenes Vorgehen (klein anfangen):**

*Variante A — Klassen beweisen (minimal-invasiv, empfohlen als Erstes):*
In `MediatorConsumerPullTest` den Mock durch unsere echten Klassen ersetzen:
```java
// statt: .registerServiceMock(BdrsClient.class, mockBdrs())
private static final String BDRS_MAPPINGS =
        CONSUMER.getBpn() + "=" + CONSUMER.getDid() + ";" + PROVIDER.getBpn() + "=" + PROVIDER.getDid();

// beide Runtimes:
.registerServiceMock(BdrsClient.class, StaticBdrsClient.parse(BDRS_MAPPINGS))
.registerServiceMock(AudienceResolver.class, new StaticAudienceResolver(StaticBdrsClient.parse(BDRS_MAPPINGS)))
```
→ beweist Parsing, Auflösung und Audience-Resolver im echten DCP-Fluss.
Dafür braucht `edc-tests/e2e/dcp-tests/build.gradle.kts` ein
`testImplementation(project(":edc-extensions:windx:windx-bdrs-static"))`.

*Variante B — zusätzlich die Verdrahtung beweisen (produktionsgetreu):*
`edc-tests/runtime/dcp/runtime-memory-dcp-ih-windx/build.gradle.kts` genauso umbauen wie die
Produktions-Runtime (`bdrs-client` ausschließen, `windx-bdrs-static` aufnehmen) und im Test die
`registerServiceMock`-Zeile **für den Provider** entfernen; die Mappings kommen dann aus der Config
(`tx.edc.windx.bdrs.mappings`). Die **Consumer**-Runtime (`runtime-memory-dcp-ih`) ist ein **geteiltes
Upstream-Modul** — dort bitte **nichts** ändern (andere Tests hängen daran); für den Consumer Variante A
verwenden.

**Abnahme:** `MediatorConsumerPullTest` bleibt **5/5 grün**.

### 3.4 Danach: AP3 (Image) im Plan weiter

```bash
./gradlew :edc-controlplane:edc-runtime-windx:dockerize   # → edc-runtime-windx:0.13.0-SNAPSHOT + :latest
```
Weiter gemäß [phase-cluster-deployment-plan.md](phase-cluster-deployment-plan.md) AP3–AP8.

---

## 4. Boot-Smoke-Test (funktioniert auch ohne E2E-Suite)

Config (die beiden neuen Settings sind das Interessante):
```properties
edc.participant.id=did:web:windx-identityhub.tx.test:provider
tractusx.edc.participant.bpn=BPNL000000000PROV
tx.edc.windx.bdrs.mappings=BPNL000000000PROV=did:web:windx-identityhub.tx.test:provider;BPNL000000000CONS=did:web:windx-identityhub.tx.test:consumer
tx.edc.vault.secrets=signer-private:dummy;verifier-public:dummy;client-secret:dummy
# tx.edc.iam.dcp.bdrs.server.url wird NICHT mehr gebraucht
```
(Rest analog `edc-controlplane/edc-runtime-windx/windx-dev.properties`; Ports ggf. freie wählen.)

```bash
./gradlew :edc-controlplane:edc-runtime-windx:shadowJar
java -Dedc.fs.config=<config> -jar edc-controlplane/edc-runtime-windx/build/libs/edc-runtime-windx.jar
```

**Erwartet im Log:**
- `Wind-X Vault Seed Extension: seeded 3 secret(s) …`
- `Wind-X Static BDRS Extension: 2 BPN/DID mapping(s) configured`
- **kein** `BPN/DID Resolution Service Client Extension` (das wäre das alte, ausgeschlossene Modul)
- **keine** Warnung `A service of the type Vault was already registered …`
- `Runtime … ready`

---

## 5. Offene Punkte / Risiken

| Punkt | Status |
|---|---|
| In-Process-DCP-Beweis für `windx-bdrs-static` (3.3) | **offen** — der einzige echte Rest von AP2 |
| Regressionsanker `MediatorConsumerPullTest` 5/5 | **nie gemessen** (Windows). Die „5 Tests" stammen aus der Commit-Message, das „grün" aus den Phasendokumenten |
| VC-Format (`cx-dcp`/`cx-policy` erwarten evtl. BPN-Claims) | unverändert das **größte Risiko** des Gesamtplans — schlägt erst in AP6 (Katalogabfrage) zu |
| `windx-dev.properties` enthält noch `tx.edc.iam.dcp.bdrs.server.url` | harmloses Überbleibsel; kann bei AP4 raus |
