# Standalone Wind-X EDC participant

Copy this entire directory into another repository, or distribute it with `helm package .`. It is a complete application chart: no sibling chart, remote chart dependencies, Helmfile, Gradle build, or source checkout is required to install it. All templates, startup/provisioning scripts, logging configuration, and OpenTelemetry settings are included here.

The default stack matches `edc-participant-compose`:

| Component | Kubernetes implementation |
|---|---|
| Credentials | A generated Kubernetes Secret, reused on upgrades |
| PostgreSQL | Single-replica StatefulSet with a PVC and the `edc` and `ih` databases |
| Vault and vault-keeper | Dev Vault and backup sidecar in a StatefulSet; KV entries restored from a PVC on restart and backed up every 30 seconds |
| IdentityHub | `tractusx/identityhub:0.3.2`, DID document, credential API, and STS |
| Participant provisioning | Idempotent Job; an existing participant keeps its signing key |
| Control Plane and Data Plane | Wind-X images pinned to `sha-9f724fe`, with Compose runtime settings |
| Public routing | Optional Ingress exposing the same four paths as the Compose Caddy proxy |

The chart contains focused connector templates adapted from the Tractus-X connector configuration and the Compose deployment. It does not expose the full upstream connector chart's values API. Connector images, resources, replica counts, log level, registry credentials, and additional environment variables are configured in this chart's `values.yaml`.

## Version alignment with Compose

The Compose README identifies `tractusx-connector 0.12.1` and `tractusx-identityhub v0.3.2` as the source charts for its rendered runtime configuration. Those are chart versions. This standalone chart installs neither upstream chart; its default runtime configuration and image references match the checked-in Compose definition and `.env.example`.

| Component | Default image in both deployments |
|---|---|
| Control Plane | `ghcr.io/wind-x-eu/edc-controlplane-postgresql-hashicorp-vault:sha-9f724fe` |
| Data Plane | `ghcr.io/wind-x-eu/edc-dataplane-windx:sha-9f724fe` |
| IdentityHub | `tractusx/identityhub:0.3.2` |
| PostgreSQL | `postgres:16.4-alpine` |
| Vault | `hashicorp/vault:1.17` |
| Vault keeper and provisioning tools | `curlimages/curl:8.10.1` |

The standalone chart's `version: 0.2.0` identifies this package. Its `appVersion: sha-9f724fe` records the Wind-X connector image tag; actual image references are set explicitly in `values.yaml`. Neither field selects upstream chart `0.12.1` or changes the IdentityHub image version.

Kubernetes-specific differences: `busybox:1.36.1` prepares the IdentityHub log volume; Helm generates credentials without Compose's secrets container; Ingress replaces Compose's `caddy:2.8` proxy. The ingress controller and its version are managed by the target cluster. Local Compose `.env` overrides and Helm values overrides can change these defaults independently.

## Install on a cluster

Use Helm 3 or 4 and a Kubernetes cluster supporting `apps/v1`, `batch/v1`, and `networking.k8s.io/v1`. The cluster needs enough capacity for the configured resources, registry access for the selected images, and persistent volume provisioning. Set both `postgresql.persistence.storageClassName` and `vault.persistence.storageClassName` if there is no default StorageClass. The bundled containers require Linux nodes compatible with the selected images. Cluster admission policies must permit the images' users; the log-directory init container and Vault backup keeper use root to prepare their writable volumes.

For public data-space traffic, configure DNS for a stable hostname, an ingress controller, and a TLS Secret in the installation namespace. Caddy and automatic certificate issuance are replaced by your cluster's ingress/TLS facilities. Only `/.well-known/did.json`, `/api/credentials`, `/api/v1/dsp`, and `/api/public` are routed publicly. The management API stays on a ClusterIP Service.

From this directory:

```shell
cp values-windx.example.yaml values-windx.yaml
kubectl create namespace edc-participant
```

On PowerShell, use `Copy-Item values-windx.example.yaml values-windx.yaml`. Fill in `participant.publicHost`, the assigned `participant.bpn`, and the ingress class/TLS Secret in the copied file. Confirm the operator DID, BDRS URL, and mediator URL. The mediator is an external endpoint, as in Compose; this chart does not deploy one.

The example names a registry Secret `ghcr-pull-secret`. Create it in the target namespace using your registry credential manager if the Wind-X images are private. Remove `imagePullSecrets` from the example if your selected images are public. Create the TLS Secret or arrange for your certificate controller to create it before enabling ingress.

```shell
helm upgrade --install windx-participant . \
  --namespace edc-participant --create-namespace \
  --values values-windx.yaml \
  --wait --wait-for-jobs --timeout 15m
```

In PowerShell, run the command on one line or use backticks for continuation. `--namespace` determines where resources are installed; the `namespace` value is only used by the optional Helmfile wrapper. Helmfile users can still run `helmfile sync` after creating `values-windx.yaml`; it installs this single chart.

No manual workload credentials are needed for a fresh installation. Helm generates four random values and reuses the existing Secret through `lookup` on upgrades. The IdentityHub key has the Compose format `c3VwZXItdXNlcg==.<random>`. The Secret is retained on uninstall, and StatefulSet PVCs are retained by Kubernetes. Do not delete or change these credentials independently of the participant's databases and Vault state. Offline `helm template` cannot look up existing credentials and generates fresh values; use normal Helm installation/upgrades for existing participants, or manage the Secret externally with `secrets.create: false` for GitOps/offline rendering.

Startup ordering is handled inside the release: PostgreSQL initializes both databases; Vault restores its backup and superuser alias; IdentityHub waits for that alias; the provisioning Job creates the participant only if absent; the Control Plane waits for the participant; the Data Plane also waits for the Control Plane. Provisioning and optional external-Vault bootstrap Jobs have revision-specific names and are removed one hour after completion. They run as ordinary resources so `helm --wait` does not deadlock on post-install hooks.

```shell
kubectl get pods,jobs,pvc --namespace edc-participant
kubectl logs --namespace edc-participant job/windx-participant-identityhub-provision-1
kubectl port-forward --namespace edc-participant service/windx-participant-controlplane 18081:8081
```

The provisioning Job suffix is the Helm release revision. Use `X-Api-Key` for management requests to `http://127.0.0.1:18081/management`. Retrieve the key securely from the `management-key` entry of Secret `windx-participant-secrets`, for example in a POSIX shell:

```shell
kubectl get secret windx-participant-secrets -n edc-participant \
  -o jsonpath='{.data.management-key}' | base64 --decode
```

After `https://<publicHost>/.well-known/did.json` responds, register the BPN and DID with SWMS and request all three credentials: `MembershipCredential`, `BpnCredential`, and `DataExchangeGovernanceCredential`. Keep the hostname unchanged for the lifetime of the identity.

## External services and credentials

The bundled Vault deliberately follows Compose's dev-mode plus backup behavior. Its backup is a periodic KV snapshot, not a durable Vault storage backend: abrupt failure can lose changes since the last backup. HashiCorp describes [dev mode](https://developer.hashicorp.com/vault/docs/concepts/dev-server) as a development facility. For a persistent managed Vault, disable the bundled Vault. The external Vault must be initialized, unsealed, and have a KV v2 mount; its workload token must read/write the participant's entries. The optional bootstrap Job writes the stable IdentityHub superuser key to `sup3r$3cr3t`. Disable bootstrap only if that alias is already managed elsewhere.

Example overrides:

```yaml
secrets:
  create: false
  name: edc-participant-secrets
postgresql:
  enabled: false
  host: postgres.database.example
  port: 5432
  username: edc
vault:
  enabled: false
  url: https://vault.example
  kvV2DataPath: secret/data
  secretApiPath: /v1/secret
  bootstrap:
    enabled: true
```

Create the databases `edc` and `ih` with the configured PostgreSQL user as their owner before installing against an external database. The init script for bundled PostgreSQL only runs with an empty data directory. `postgresql.port` is the server's port; keep it at 5432 with bundled PostgreSQL.

The externally managed Secret must contain these keys (override their names under `secrets.keys` if needed):

| Key | Purpose |
|---|---|
| `postgres-password` | Shared PostgreSQL password for connector and IdentityHub |
| `vault-token` | Vault access token |
| `superuser-key` | Stable IdentityHub API key, in the format above |
| `management-key` | Connector management/catalog and Data Plane proxy authentication |

Secrets are mounted as files and passed to Java via a private temporary configuration file, matching Compose. No cluster-wide resources or RBAC grants are required by the workloads. Image pull Secrets apply to all Pods. Resource names derive from the Helm release unless explicitly overridden, allowing multiple participants in one namespace.

## Packaging and moving

```shell
helm lint .
helm package . --destination /path/to/output
helm upgrade --install windx-participant /path/to/output/windx-edc-participant-0.2.0.tgz \
  --namespace edc-participant --create-namespace --values /path/to/values-windx.yaml \
  --wait --wait-for-jobs --timeout 15m
```

Local `values-windx.yaml`, the `secrets/` directory, and test files are excluded from the package. The example values are included. No `helm dependency build` is needed. Moving the chart moves the deployment definition; moving an existing participant also requires its identity state and access to its configured external endpoints.

## Migrating existing participants

Restore both PostgreSQL databases and Vault KV entries before starting an existing participant elsewhere. Preserve its public hostname, BPN, signing keys, STS client secret, PostgreSQL password, IdentityHub superuser key, and management key. Restore the Kubernetes Secret too, or use `secrets.create: false` and supply the original keys. For a new Vault, issue a workload token for the restored entries. Back up the databases, Vault backup volume (or external Vault storage), and Kubernetes Secret together. Empty databases/Vault create a new identity even with the same DID.

The previous Helmfile installed separate `windx-participant` and `windx-connector` releases and used an external Vault. This version combines them and changes default service/Secret names. It is not an in-place ownership transfer of those two releases: plan a state-preserving migration with a maintenance window or restore into a separate namespace, and remove the previous connector release only after preserving its state. Use the external-service overrides above for existing Vault/Secret installations; explicit `postgresql.serviceName`, `identityhub.serviceName`, and `connector.fullnameOverride` values can preserve internal addresses. Do not install overlapping resources owned by the old connector release.

## Verification

Run `node tests/verify.mjs` from this directory (requires Node.js, Helm, and kubectl). It renders a matrix of bundled/external services, checks resource wiring and public routes, packages the chart in an isolated temporary directory, and renders the package. Add `--compose ../edc-participant-compose` to compare all shared image references, Control Plane/Data Plane/IdentityHub runtime variables, logging, and OpenTelemetry settings with Compose (also requires the Docker Compose CLI, but no Docker daemon). These checks do not contact a cluster. An actual cluster deployment and public TLS/data-space traffic still need validation in your target environment.
