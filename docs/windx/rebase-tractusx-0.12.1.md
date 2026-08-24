# Wind-X auf tractusx-edc 0.12.1

## Warum dieser Branch

Die Wind-X-Data-Plane lief bis dahin gegen `main`, also gegen tractusx-edc
`0.13.0-SNAPSHOT` mit EDC `0.17.0`. Betrieben wurde sie aber neben einer
Control-Plane aus dem Release `0.12.1`, die auf EDC `0.15.1` steht.

Zwischen diesen beiden EDC-Ständen hat sich die JSON-LD-Serialisierung der
`DataAddress` geändert: bis EDC 0.15 sind die DSpace-Schlüssel fest auf
`https://w3id.org/dspace/v0.8/` verdrahtet, ab 0.17 gibt es nur noch
`https://w3id.org/dspace/2025/1/`. Die Control-Plane schickt beim Flow-Start
also `…/v0.8/endpointType`, die Data-Plane sucht `…/2025/1/endpointType`,
findet nichts, und `DataAddress.Builder.type(null)` wirft eine
NullPointerException. Ergebnis: HTTP 500 auf `/api/control/v1/dataflows/start`
und jeder Transfer endet in TERMINATED — unabhängig vom Asset-Typ und
unabhängig von der DSP-Version zur Gegenstelle.

Dieser Branch setzt die Wind-X-Erweiterungen deshalb auf das **Release 0.12.1**
statt auf einen Snapshot. Damit sprechen Control-Plane und Data-Plane wieder
dieselbe EDC-Version.

## Was der Branch enthält

Basis ist der Upstream-Tag `0.12.1` (EDC 0.15.1), unverändert. Darauf liegt
ausschließlich der Wind-X-Anteil:

| Pfad | Inhalt |
|---|---|
| `edc-extensions/windx/windx-mms` | MMS/IEC-61850-Anbindung (~5.300 Zeilen) |
| `edc-extensions/windx/windx-mediator-proxy` | Mediator-Proxy-Data-Source |
| `edc-extensions/windx/windx-bdrs-static` | statischer BDRS-Client |
| `edc-extensions/windx/windx-participant-log` | Participant-ID im Log |
| `edc-dataplane/edc-dataplane-windx` | Runtime-Modul, baut das Image |
| `deployment/windx-catenax` | Deployment-Manifeste |
| `.github/workflows/build-and-publish-ghcr.yaml` | Build und Push nach GHCR |

Am Upstream geändert wurden nur zwei Dateien:

* `settings.gradle.kts` — fünf `include`-Zeilen für die Module oben
* `gradle.properties` — `version=0.12.1-windx-SNAPSHOT`, damit Artefakte nicht
  mit dem unveränderten Upstream-Release verwechselt werden

Die Control-Plane bleibt reines Upstream. Alle vier Wind-X-Module sind reine
Data-Plane-Extensions.

## Was geprüft ist – und was nicht

Geprüft:

* Alle `project(...)`- und `libs.*`-Referenzen der Wind-X-Module existieren in
  der 0.12.1-Basis und sind in `settings.gradle.kts` registriert.
* Alle 32 `org.eclipse.edc.*`-Importe des Wind-X-Codes existieren als Klassen
  im ausgelieferten 0.12.1-Runtime-Jar (EDC 0.15.1).
* Der GHCR-Workflow findet alle Voraussetzungen: `.github/actions/setup-java`,
  `resources/Dockerfile`, beide Matrix-Module.

**Nicht geprüft:** ob der Code tatsächlich kompiliert. Der Importabgleich zeigt
nur, dass die Klassen vorhanden sind, nicht dass die Methodensignaturen gleich
geblieben sind. Erwartbare Reibungspunkte sind `dataplane.spi.edr` und
`dataplane.spi.iam` — dort hat sich zwischen EDC 0.15 und 0.17 am meisten
bewegt. Das entscheidet der erste CI-Lauf.

## Bauen

```bash
./gradlew -p edc-dataplane/edc-dataplane-windx shadowJar
```

In der CI läuft das automatisch bei jedem Push auf diesen Branch. Der
`latest`-Tag wird dabei **nicht** gesetzt (nur der Default-Branch tut das), das
Image erscheint unter dem Branchnamen und der Commit-SHA:

```
ghcr.io/wind-x-eu/edc-dataplane-windx:windx-on-tractusx-0.12.1
ghcr.io/wind-x-eu/edc-dataplane-windx:sha-<kurz-sha>
```

## Ausrollen

```bash
helm upgrade dave-edc tractusx/tractusx-connector --version 0.12.1 \
  -n windx-dave --reuse-values --server-side=false \
  --set dataplane.image.repository=ghcr.io/wind-x-eu/edc-dataplane-windx \
  --set dataplane.image.tag=windx-on-tractusx-0.12.1
```

Die Data-Plane braucht `tx.edc.windx.mediator.base.url`, sonst startet sie
nicht.
