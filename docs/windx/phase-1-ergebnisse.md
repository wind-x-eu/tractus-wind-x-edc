# Phase 1 — Ergebnisse (Mediator-Migration, nur GET)

**Datum:** 2026-06-29
**Ziel:** BIBA-`MediatorDataPlaneExtension` (nur GET-Pfad) als additives Modul `windx-mediator-proxy`
in den tractus-x-edc-Stack portieren, in die `edc-runtime-windx` einhängen, Build + Boot + 401-Smoke-Test.
**Status:** ✅ **erfolgreich abgeschlossen** — Build grün, Runtime bootet, Mediator-Route registriert,
Smoke-Tests bestanden, keine Kollision mit dem Standard-Data-Plane.

> **⚠️ Aktualität (2026-07-07):** Die unten unter „Offen/Backlog" gelisteten Punkte sind **inzwischen erledigt**:
> PUT-Pfad (`proxyPut`→POST `/createVirtualDataSource`) ✅, EDR-Service-Registry/echter Transfer via **Weg A** ✅,
> Identität (Weg A, DCP-E2E `MediatorConsumerPullTest`) ✅, `mmsLight` + `mms` ✅.

---

## 1. Was angelegt / geändert wurde

### Neue Dateien (1 neues Modul, 4 Dateien)

| Datei | Zweck |
|---|---|
| `edc-extensions/windx/windx-mediator-proxy/build.gradle.kts` | Build (`java-library`; deps: spi.core, spi.web, spi.dataplane, jakarta.rsApi) |
| `…/src/main/java/org/eclipse/tractusx/edc/windx/mediator/MediatorDataPlaneExtension.java` | Extension: `med`-Kontext + `addGeneratorFunction("MediatorHttpData", …)` + Controller-Registrierung |
| `…/src/main/java/org/eclipse/tractusx/edc/windx/mediator/MediatorController.java` | JAX-RS-GET → POST `…/forwardRealDataSource` (bereinigt) |
| `…/src/main/resources/META-INF/services/org.eclipse.edc.spi.system.ServiceExtension` | ServiceLoader-Registrierung |

### Geänderte Dateien (3, rein additiv)

| Datei | Änderung |
|---|---|
| `settings.gradle.kts` | `include(":edc-extensions:windx:windx-mediator-proxy")` |
| `edc-controlplane/edc-runtime-windx/build.gradle.kts` | `implementation(project(":edc-extensions:windx:windx-mediator-proxy"))` |
| `edc-controlplane/edc-runtime-windx/windx-dev.properties` | `web.http.med.port=8087`, `…med.path=/api/med`, `tx.edc.windx.mediator.base.url=…` (Dummy) |

> Wie in Phase 0: außer den 3 Verdrahtungsdateien wurde **keine** Upstream-Quelldatei angefasst.

---

## 2. Umsetzung der 4 Entscheidungen (aus `phase-1-recherche.md` §6)

| # | Entscheidung | Im Code |
|---|---|---|
| 1 | Custom-Proxy weg | nicht angelegt |
| 2 | Typ `MediatorHttpData` | `MEDIATOR_HTTP_DATA = "MediatorHttpData"`, `addGeneratorFunction(...)` |
| 3 | zentrale Mediator-URL | `@Setting tx.edc.windx.mediator.base.url` (Pflicht, klare `EdcException` bei Fehlen); per-Asset-`MEDIATOR-URL` + Hardcode entfernt |
| 4 | nur GET | nur `@GET proxyGet`; PUT bleibt Backlog |

**Weitere Bereinigungen ggü. BIBA:** `System.out` (~25×) → `Monitor`; verschachteltes try/catch mit totem
`return` entwirrt; sauberer `502` bei Mediator-Status `!= 200`; `InterruptedException` reicht das Interrupt-Flag weiter.

---

## 3. Verifikation (reproduzierbar)

```bash
export JAVA_HOME=/home/flo/.sdkman/candidates/java/17.0.13-tem
cd tractusx-edc
./gradlew :edc-controlplane:edc-runtime-windx:shadowJar          # BUILD SUCCESSFUL
java -Dedc.fs.config=edc-controlplane/edc-runtime-windx/windx-dev.properties \
     -jar edc-controlplane/edc-runtime-windx/build/libs/edc-runtime-windx.jar
```

**Bootlog (Auszug):**
```
INFO ... Wind-X Mediator Data Plane Extension initialized: context 'med' on http://localhost:8087/api/med,
         transfer type 'MediatorHttpData', mediator base url 'http://localhost:3051'
INFO ... Runtime <uuid> ready
```
Keine `A binding for port … already exists`-Meldung → Port 8087 frei, kein Kontext-Konflikt.

**Smoke-Tests (`curl`):**

| Test | Erwartet | Ergebnis |
|---|---|---|
| `GET :8087/api/med/…` ohne Token | 401 | **401** ✅ |
| `GET :8087/api/med/…` mit Dummy-Token | 403 | **403** ✅ (Autorisierungspfad läuft, Token abgelehnt) |
| `GET :8085/api/public/…` (Gegenprobe Standard) | 401 | **401** ✅ (Standardkontext koexistiert, keine Kollision) |

Der 403 belegt, dass `authorizationService.authorize(...)` im Controller tatsächlich ausgeführt wird —
nicht nur die Route existiert. Damit ist die Registrierung **ohne** echte Identität/EDR verifiziert.

---

## 4. Checkstyle-Hinweis (für künftige Module)

Erster Build schlug an `OperatorWrap` fehl: bei umgebrochener String-Konkatenation muss das `+` **ans
Zeilenende der Vorzeile** (nicht an den Zeilenanfang). Sonst gilt die Phase-0-Toolchain unverändert.

---

## 5. Offen / nächste Schritte

- **🔴 Backlog (Pflicht):** PUT-Pfad (`createVirtualDataSource`) migrieren (siehe `phase-1-recherche.md` §6).
- **EDR-Service-Registry:** Für einen **echten** `MediatorHttpData-PULL`-Transfer ggf.
  `edrServiceRegistry.register(...)` nötig (Standard tut das, BIBA nicht) — beim ersten echten Transfer prüfen.
- **Identität (Weg A/B/C)** und ein **End-to-End-Test** (Bauplan: `MMS.json`, mit `transferType=MediatorHttpData-PULL`)
  sind weiterhin offen — bewusst nach hinten geschoben.
- Nächste Extension laut Integrationsplan: `mmsLight` (TCP-Proxy), danach `mms` (IEC-61850/MMS).
</content>
