# Tractus-X EDC — Tech-Stack & Extension-Aufbau

> Zusammenfassung der eingelesenen Dokumentation (eclipse-tractusx/tractusx-edc & tutorial-resources/mxd, Branch `main`).
> Stand: 2026-06-23.

---

## 1. Was ist Tractus-X EDC?

Produktionsreife Implementierung eines **EDC-Connectors** (Eclipse Dataspace Components) für den
**Catena-X-Datenraum**. Implementiert das **Dataspace Protocol (DSP)** der IDSA und nutzt durchgängig
**JSON-LD** für alle Payloads. Erlaubt Teilnehmern, souverän und policy-kontrolliert Daten auszutauschen.

Zwei Deployment-Setups:
- **Setup 1 (nur Dev):** komplett in-memory, seedbarer KeyVault — nicht produktionstauglich.
- **Setup 2 (Prod):** PostgreSQL (Persistenz) + HashiCorp Vault (Secrets), je für Control & Data Plane.

---

## 2. Tech-Stack

| Bereich | Technologie |
|---|---|
| Sprache | **Java 17** |
| Build | **Gradle** (Version Catalogs, single root `gradle.properties`) |
| Code-Style | **Checkstyle** (per Decision Record aktiviert) |
| Auslieferung | **Docker**-Images + **Helm**-Charts (Maven Central / Docker Hub) |
| Persistenz | In-Memory (Dev) oder **PostgreSQL** (Prod) |
| Secrets | In-Memory-Vault (Dev, unsicher) oder **HashiCorp Vault** (Prod) |
| Identität (Prod) | **DCP**: IdentityHub + BDRS (BPN↔DID) + Issuer-Service (`did:web`) |
| Git-Flow | `main` = aktive Entwicklung, `releases` = Releases; Conventional Commits |

> ⚠️ Die maßgebliche **Property-/Config-Doku** liegt im Helm-Chart `charts/tractusx-connector/README.md`,
> nicht in den Modul-READMEs.

---

## 3. Architektur: Control Plane / Data Plane

### Control Plane
Verwaltet die Geschäftslogik: Assets, Policies, Contract Definitions, **Contract Negotiation**,
Transfer-Steuerung, Katalog. Enthält die **Management API** (admin, geschützt per `X-Api-Key` →
`web.http.management.auth.key`) und den **DSP-Endpoint** (Connector-zu-Connector).

Varianten:
- `edc-controlplane-base` — gemeinsame Basis.
- `edc-runtime-memory` — In-Memory + unsicherer In-Memory-Vault (Klartext!). **Nur Test/Demo.**
  Secrets via `TX_EDC_VAULT_SECRETS="key1:secret1;key2:secret2"`.
- `edc-controlplane-postgresql-hashicorp-vault` — **Produktiv** (PostgreSQL + HashiCorp Vault).

### Data Plane
Führt den **eigentlichen Datentransfer** aus, gesteuert über **Data Plane Signaling (DPS)**.

Varianten:
- `edc-dataplane-base` — Basis.
- `edc-dataplane-hashicorp-vault` — mit HashiCorp Vault. *(Keine eigene PostgreSQL-Variante.)*

---

## 4. Kernkonzepte (Management-API-Walkthrough, `/v3`)

Ablauf eines Datenaustauschs:

```
Asset → Policy (ODRL) → Contract Definition → Catalog (DCAT)
      → Contract Negotiation → Contract Agreement → Transfer Process → EDR
```

- **Asset** (`POST /v3/assets`): Backend-Repräsentation; `dataAddress` (z. B. `HttpData`, `baseUrl`,
  Proxy-/OAuth2-Parameter), `properties` (öffentlich) vs. `privateProperties` (nur Anbieter).
- **Policy (ODRL)** (`POST /v3/policydefinitions`): `permission`/`prohibition`/`obligation`,
  Constraints (`leftOperand`/`operator`/`rightOperand`), `access` vs. `use`.
  - **Catena-X-Pflicht:** Usage-Policies brauchen `FrameworkAgreement` **und** `UsagePurpose` (per `and`).
- **Contract Definition** (`POST /v3/contractdefinitions`): verbindet Assets (`assetsSelector`) mit
  Access- + Contract-Policy.
- **Catalog** (DCAT, über DSP): Datasets je Asset → Distributions (`HttpData-PULL`, `AmazonS3-PUSH`,
  `AzureStorage-PUSH`) + `hasPolicy` (das Angebot).
- **Contract Negotiation** (`POST /v3/contractnegotiations`): Zustandsmaschine bis `FINALIZED`.
- **Contract Agreement**: formalisierte Vereinbarung (`contractSigningDate`) — Audit/Verifikation.
- **Transfer Process** (`POST /v3/transferprocesses`):
  - **Provider Push** → Data Plane überträgt aktiv.
  - **Consumer Pull** → Data Plane erzeugt **EDR**, Transfer bleibt `STARTED`.
- **EDR** (`/v3/edrs`): kurzlebiges Bearer-Token (`tx-auth:expiresIn` ~300s) + Refresh-Token/-Endpoint;
  gecacht solange Transfer `STARTED`.

Spezialthemen: **Response Channels** (bidirektional, `transferType` z. B. `HttpData-PULL-HttpData`),
**Non-Finite Provider Push** (`"isNonFinite": "true"`, Streaming/Events via DataFlow API).

---

## 5. Extension-Aufbau (Referenzmuster: `edc-extensions/bpn-validation`)

Der Connector ist **"extensible by design"**; eigene Extensions leben unter `edc-extensions/`.
Das BPN-Validation-Modul ist das kanonische Vorbild für eine eigene Policy-Extension:

| Submodul | Aufgabe |
|---|---|
| **`*-spi`** | Service Provider Interface — Schnittstellen / Extensibility-Points (z. B. Store-Interface) |
| **`*-core`** | Implementierung: Policy-`*Function`, registriert auf den drei **Scopes** `catalog` (Access), `contract.negotiation` (Contract), `transfer.process`. Neue ODRL-Constraints binden an einen `leftOperand`. |
| **`*-api`** | CRUD-REST-API |
| **`*-store-sql`** | SQL-Persistenz (Schema in `docs/schema.sql`); In-Memory-Alternative möglich |

**BPN-Policy-Typen** (Beispiel):
- **BusinessPartnerGroup** (empfohlen): BPN→Gruppen-Mapping in DB, per REST-API pflegbar; Operatoren
  `isPartOf` / `isAllOf` / `isAnyOf` / `isNoneOf` (`eq`/`neq` deprecated).
  - `leftOperand`: `https://w3id.org/catenax/2025/9/policy/BusinessPartnerGroup`
- **BusinessPartnerNumber** (legacy): BPNs hartkodiert, nur `eq`; nach Verhandlung nicht änderbar.

---

## 6. Coding-Prinzipien (für Extension-Code)

- **Config**: früh beim Start validieren; minimal & explizit per Konstruktor injizieren; Keys mit
  `@Setting` annotieren.
- **Exceptions**: nur unchecked (von `org.eclipse.edc.spi.EdcException`), nur für fatale Fälle.
  Sonst `Result`/Subklassen zurückgeben; Validierungsfehler **melden statt werfen**.
- **Stil**: `var` nutzen; `final` nur für Felder/Konstanten; `static` minimieren; camelCase ohne Präfixe;
  `null` statt `Optional` (außer Fluent-APIs); `@Nullable`/`@NotNull`.
- **Builder-Pattern** bei optionalen Params oder >3 Konstruktorargumenten.
- **Tests**: pro Unit gemockte Dependencies; Namensschema `[METHOD]_when[CONDITION]_should[EXPECTATION]`;
  Services konstruktor-komponiert (Mockbarkeit).
- **Security**: Secrets nur im Vault; nie sensible Daten loggen.
- **JSON**: `TypeManager` für (De)Serialisierung.
- **Module**: Extensions = Runtime-Features, SPI = Extensibility-Points, Libraries = Utilities;
  strikte hierarchische Modul-Dependencies.

---

## 7. MXD — Minimum Tractus-X Dataspace (lokale Testumgebung)

Vorkonfigurierter "Minimum Viable Dataspace" zum lokalen Testen von Connectoren/Extensions.

- **Teilnehmer**: **Alice** (+ eigener Catalog Server, Seed-Daten), **Bob**; optional **Trudy** (Tutorial).
- **Komponenten** (je Teilnehmer eigene): EDC-Connector (CP+DP), **IdentityHub** (DIDs/Credentials/STS),
  **Vault**, **PostgreSQL**; zentral: **BDRS** (BPN↔DID), **Dataspace Issuer Service** (`did:web`,
  stellt VCs via DCP aus), **Azurite** (Azure-Blob-Emulator), **MinIO** (S3).
- **Deployment** (IaC, Namespace `mxd`):
  1. `kind create cluster -n mxd --config kind.config.yaml` + NGINX-Ingress
  2. `./gradlew dockerize` (in `mxd-runtimes`) → `kind load docker-image --name mxd ...`
  3. `terraform init` → `terraform apply`  *(ARM/Apple Silicon bei SIGILL: `-var="useSVE=true"`)*
- **Voraussetzungen**: JDK 17+, KinD (o. ä.), Terraform, POSIX-Shell; optional `stern`, k9s/Lens, Postman, `jq`.
- **Verifizieren**: `curl http://localhost/alice/health/api/check/liveness` → `"isSystemHealthy": true`;
  Assets via `POST http://localhost/alice/management/v3/assets/request` (Header `x-api-key: password`).
- **Tutorials**: Access Policies, Business Partner Group Policy, EDR Transfer, File Transfers
  (Azure↔S3-Kombinationen), Credential Issuance, Trudy-Connector, Remote Deployment.

---

## 8. Migration — wichtigste Breaking Changes

| Sprung | Kernänderung |
|---|---|
| 0.3.4 → **0.4.0** | **IDS → DSP**, Management API auf JSON-LD + `/v2/`, `edc.participant.id` (BPN) Pflicht |
| 0.4 → **0.5** | Auth **DAPS → SSI/MIW** (Verifiable Credentials), Consumer-Pull-Umbau |
| 0.5 → **0.7** | **Data Plane Signaling**, EDR-Redesign, **IATP/DIM**, **BDRS** (BPN↔DID), ODRL-Änderungen |
| 0.7 → **0.8** | Datasource entkoppelt, **SQL-Schema-Migrationen**, SPI-Cleanup, Agreement-Retirement |
| 0.9 → **0.10** | Übergang DCP: `edc.dcp.v08.forced=true` |
| 0.10 → **0.11** | **DCP 1.0 Default**, DSP 0.8 + 2025-1 parallel, **CX-0152**-Policy-Validierung + neuer Namespace |
| 0.11 → **0.12** | Connector Discovery, **`EDC_PARTICIPANT_CONTEXT_ID` Pflicht**, VP-Caching, `TX_EDC_`-Präfix |
| 0.12 → **0.13** | **Federated Catalog entfernt**, API-/Config-Renames, **PostgreSQL 18** erforderlich |

> Bei neuen Policies beachten: Left-Operands liegen im Namespace
> `https://w3id.org/catenax/2025/9/policy/context.jsonld`; alte Access Policies werden sonst nicht
> mehr ausgewertet (Contract Definitions im Katalog unsichtbar).

---

## 9. Quellen (Branch `main`)

- `eclipse-tractusx/tractusx-edc/docs/` — Usage / Management-API-Walkthrough (Assets, Policies,
  Contract Definitions, Catalog, Negotiations, Transfers, EDRs, Agreements, Response Channels, Non-Finite Push)
- `eclipse-tractusx/tractusx-edc/docs/migration/` — Versions-Migrationsguides
- `eclipse-tractusx/tractusx-edc/docs/development/` — Coding Principles, Project-/Repository-Structure,
  Release, Decision Records, Bidirectional Transfer, Dataplane-Signaling
- `eclipse-tractusx/tractusx-edc/edc-controlplane/` & `edc-dataplane/` — Runtime-Varianten
- `eclipse-tractusx/tractusx-edc/edc-extensions/bpn-validation/` — Policy-Extension (Referenzmuster)
- `eclipse-tractusx/tutorial-resources/mxd/` — Minimum Tractus-X Dataspace
