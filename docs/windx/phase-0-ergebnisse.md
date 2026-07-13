# Phase 0 — Ergebnisse (Toolchain-Proof)

**Datum:** 2026-06-24
**Ziel:** Erste BIBA-Extension (`ParticipantIdLog`) in den tractus-x-edc-Stack portieren und eine
kombinierte CP+DP-Custom-Runtime (`edc-runtime-windx`) bauen, die bootet und die Extension lädt.
**Status:** ✅ **erfolgreich abgeschlossen** — Build grün, Runtime bootet bis `Runtime ... ready`,
die portierte Extension loggt ihre Zeile.

---

## 1. Was angelegt / geändert wurde

### Neue Dateien (2 neue Module)

| Datei | Zweck |
|---|---|
| `edc-extensions/windx/windx-participant-log/build.gradle.kts` | Build des Extension-Moduls (`java-library`, `libs.edc.spi.core`) |
| `edc-extensions/windx/windx-participant-log/src/main/java/org/eclipse/tractusx/edc/windx/participantlog/ParticipantIdLogExtension.java` | Die portierte BIBA-Extension |
| `edc-extensions/windx/windx-participant-log/src/main/resources/META-INF/services/org.eclipse.edc.spi.system.ServiceExtension` | ServiceLoader-Registrierung (FQN der Extension) |
| `edc-controlplane/edc-runtime-windx/build.gradle.kts` | Kombinierte CP+DP-Runtime (Kopie von `edc-runtime-memory` + windx-Extension) |
| `edc-controlplane/edc-runtime-windx/windx-dev.properties` | Minimale Boot-Config (Dummy-Werte + getrennte Web-Ports) |

### Geänderte Datei (1)

| Datei | Änderung |
|---|---|
| `settings.gradle.kts` | 2 neue `include(...)`: `:edc-extensions:windx:windx-participant-log` und `:edc-controlplane:edc-runtime-windx` |

> Es wurde **keine** bestehende tractus-x-Quelldatei außer `settings.gradle.kts` angefasst — die
> Migration läuft rein additiv. Das hält den Upstream-Merge-fähig.

---

## 2. Die portierte Extension — was sich ggü. BIBA geändert hat

| Aspekt | BIBA (0.14.1) | Wind-X / tractus-x (0.17.0) |
|---|---|---|
| Package | `org.eclipse.edc.sample.extension` | `org.eclipse.tractusx.edc.windx.participantlog` |
| Setting lesen | `context.getSetting("edc.participant.id", "UNKNOWN")` | `@Setting`-**Feld-Injection** (modernes Idiom) |
| Klassen-Metadaten | nur `name()`-Override | zusätzlich `@Extension(value = NAME)` |

**Hintergrund `getSetting`-Frage (offener Punkt aus dem Plan, jetzt geklärt):**
In 0.17 ist die Feld-Injection per `@Setting` der Standardweg (so machen es z. B. `EventSubscriberExtension`
und `did-document-service-div`, letztere liest sogar denselben Key `edc.participant.id`). Wir folgen diesem
Idiom statt des alten `context.getSetting(...)`.

Kernstück:
```java
@Extension(value = NAME)
public class ParticipantIdLogExtension implements ServiceExtension {
    public static final String NAME = "Wind-X Participant Id Log Extension";

    @Setting(key = "edc.participant.id", description = "EDC Participant Id", defaultValue = "UNKNOWN")
    private String participantId;

    @Override public String name() { return NAME; }

    @Override public void initialize(ServiceExtensionContext context) {
        context.getMonitor().info("==> Loaded property edc.participant.id = " + participantId);
    }
}
```

---

## 3. Wichtigster Befund: DCP-Stack erzwingt Config schon beim Boot

`edc-controlplane-base` verdrahtet den **kompletten DCP/SSI-Identitätsstack fest**
(`runtimeOnly(libs.edc.bom.controlplane.dcp)` + `cx-dcp`, `tx-dcp`, `tx-dcp-sts-div`, `bdrs-client`,
`did-document:*`). Folge: Die Runtime verlangt bereits in der **Dependency-Injection-Phase** (vor `initialize`)
Pflicht-Settings:

- `tractusx.edc.participant.bpn`
- `tx.edc.iam.dcp.bdrs.server.url`
- `edc.transfer.proxy.token.signer.privatekey.alias` / `...verifier.publickey.alias`
- `edc.iam.sts.oauth.token.url` / `client.id` / `client.secret.alias`

Ohne diese Werte bricht der Boot mit `Problems occurred during dependency injection` ab — **noch bevor**
unsere Extension läuft.

**Konsequenz / Entscheidung:** Für Phase 0 reichen **Dummy-Werte** in `windx-dev.properties` (die
Inject-Phase prüft nur Vorhandensein, nicht Erreichbarkeit). Es findet kein echter Transfer und keine echte
Identitätsprüfung statt. Die **echte** Identitäts-Entscheidung (Mock vs. DCP — Weg A/B/C im Integrationsplan,
Abschnitt 7.5) fällt erst in Phase 1/2, wenn der erste echte Datentransfer ansteht.

Zusätzlich nötig: getrennte **Web-Ports**, da die kombinierte CP+DP-Runtime sonst auf Port 8181 kollidiert
(`A binding for port 8181 already exists`). Gelöst in `windx-dev.properties` (8080/8081/8083/8084/8085/8086).

---

## 4. Verifikation (reproduzierbar)

```bash
export JAVA_HOME=/home/flo/.sdkman/candidates/java/17.0.13-tem
cd tractusx-edc

# 1) Build
./gradlew :edc-controlplane:edc-runtime-windx:shadowJar      # BUILD SUCCESSFUL

# 2) Boot
java -Dedc.fs.config=edc-controlplane/edc-runtime-windx/windx-dev.properties \
     -jar edc-controlplane/edc-runtime-windx/build/libs/edc-runtime-windx.jar
```

**Beobachtetes Ergebnis (Log):**
```
INFO ... ==> Loaded property edc.participant.id = WINDX_TEST     <-- unsere Extension
INFO ... Runtime <uuid> ready                                    <-- Runtime vollständig hochgefahren
INFO ... Shutdown complete / Cleanup complete                    <-- sauberes Beenden
```

Web-Endpunkte beim Lauf u. a.: Default `:8080/api`, Management `:8081/management`, DSP `:8084/api/v1/dsp`,
DataPlane-Public `:8085/api/public`.

### In Eclipse testen
- Build/Run wie bei den anderen Modulen über **Buildship** (Gradle-Task `:edc-controlplane:edc-runtime-windx:shadowJar`),
  danach Run-Configuration „Java Application“ mit Main-Class `org.eclipse.edc.boot.system.runtime.BaseRuntime`
  und VM-Arg `-Dedc.fs.config=.../windx-dev.properties`.
- Nach Anlegen der neuen Module zunächst **Rechtsklick aufs Projekt → Gradle → Refresh Gradle Project**,
  damit Eclipse die zwei neuen Module sieht.

---

## 5. Status der offenen Phase-0-Punkte (aus Plan 7.8)

| Punkt | Status |
|---|---|
| `iam-mock` in tx-Runtime — Konflikt DCP vs. mock? | ✅ geklärt: DCP fest verdrahtet → Phase 0 ohne Mock; Mock-Frage erst Phase 1/2 |
| Minimale `*.properties` (Ports + `edc.participant.id`) | ✅ erstellt: `windx-dev.properties` |
| `getSetting`-Deprecation in 0.17 | ✅ geklärt: `@Setting`-Feld-Injection statt `context.getSetting(...)` |

---

## 6. Nächster Schritt (Phase 1 — noch nicht begonnen)

Migration der `proxy`-Extensions (Custom- + Mediator-Public-API-Proxies) als nächstes Modul unter
`edc-extensions/windx/`. **Achtung — bekannte Konflikte** (siehe Integrationsplan):
- doppeltes `addGeneratorFunction("HttpData")` (Custom- vs. Mediator-Proxy),
- mehrere `DataFlowController` @ Priorität 10,
- hartkodierte BIBA-URLs + `alice/alice-secret`-Credentials.

Ab Phase 1/2 wird außerdem die Identitäts-Entscheidung (Weg A/B/C) relevant, sobald ein echter Transfer
zwischen zwei Connectoren getestet wird.
