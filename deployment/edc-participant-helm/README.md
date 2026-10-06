# Wind-X EDC participant on Kubernetes

This package deploys the IdentityHub and its persistent PostgreSQL database, bootstraps the IdentityHub API key into Vault, provisions the participant DID, and exposes the same four public paths as the Compose setup. Helmfile installs this chart first and the repository's `tractusx-connector` chart second, so the connector starts after IdentityHub provisioning finishes.

The chart expects a **persistent, initialized HashiCorp Vault**. It does not run Vault in development mode. By default, Vault must have a KV v2 mount named `secret`, and the token supplied to the workloads must be able to read and write the participant's entries under `secret/data/*` (and list metadata if required by the Vault policy). The bootstrap Job writes the stable IdentityHub superuser key to the alias `sup3r$3cr3t`. If the mount path differs, update `vault.kvV2DataPath` and `vault.secretApiPath` together. If an administrator already bootstraps this alias, set `vault.bootstrap.enabled: false`.

## Prepare values and secrets

Copy `values-windx.example.yaml` to `values-windx.yaml` (PowerShell: `Copy-Item values-windx.example.yaml values-windx.yaml`). Set the participant's stable public hostname and BPN, then confirm the operator DID, BDRS endpoint, mediator URL, namespace, ingress class, and TLS Secret name. The hostname is used for `did:web`, the DID document, the callback URLs, and ingress routing, so keep it unchanged after onboarding.

Create the Kubernetes Secret named by `secrets.name` (default `edc-participant-secrets`) in the target namespace before installing. It must contain these keys:

| Secret key | Used for |
|---|---|
| `postgres-password` | PostgreSQL `edc` user password, shared by the connector and IdentityHub |
| `vault-token` | Vault KV v2 access for IdentityHub, connector, and the bootstrap Job |
| `superuser-key` | IdentityHub API key; use the Compose format `c3VwZXItdXNlcg==.<random>` |
| `management-key` | Connector management and internal Data Plane proxy authentication |

Use a secret manager or create this Secret from protected local files. Do not commit the Secret manifest, its source files, or credentials in a values file. If the Secret name or key names differ, update the `secrets` section in `values.yaml` and copy those settings into your site values file.

For a fresh deployment, this chart can create a single-replica PostgreSQL instance with a PVC. It initializes `edc` and `ih` on the first start. For an existing or managed PostgreSQL service, set `postgresql.enabled: false`, set `postgresql.host` and `postgresql.port`, and create both databases with `edc` as their owner before installing. The PostgreSQL init script only runs when the data directory is empty.

The connector images in `values-connector.yaml.gotmpl` use the Compose tag `sha-9f724fe`. Create the GHCR pull Secret named by `connector.imagePullSecret` (default `ghcr-pull-secret`) in the same namespace. Create the ingress TLS Secret before installation when ingress TLS is enabled.

## Install

Install Helmfile and Helm, then run these commands from this directory after creating `values-windx.yaml` and the Kubernetes Secrets:

```shell
helmfile deps
helmfile sync
```

Helmfile waits for the participant release, including its post-install provisioning Job, before installing the connector. The Job checks for an existing participant before creating keys, so repeat upgrades leave an existing IdentityHub key unchanged.

The successful provisioning Job remains available for one hour for inspection:

```shell
kubectl logs --namespace edc-participant job/windx-participant-identityhub-provision
```

The ingress routes only `/.well-known/did.json`, `/api/credentials`, `/api/v1/dsp`, and `/api/public`. It leaves the Control Plane management port private. The default NGINX annotations disable proxy buffering for streaming data responses; adjust them for the ingress controller in use.

After the DID document responds at `https://<publicHost>/.well-known/did.json`, complete the existing SWMS onboarding: register the BPN and DID, then request `MembershipCredential`, `BpnCredential`, and `DataExchangeGovernanceCredential` for the participant.

## Migrating from Compose

For an existing participant, restore both PostgreSQL databases (`edc` and `ih`) and the Vault KV entries before installing. Keep the same `publicHost`, PostgreSQL password, IdentityHub superuser key, and management key. If moving to a different Vault, issue a stable workload token for the restored KV paths and put that token in `vault-token`. A fresh database or Vault with the same DID can generate a new signing key, so do not run provisioning against empty state and treat it as the old participant. Back up the database, Vault storage, and Kubernetes Secret together.

The Compose README records the identity state and hostname constraints in more detail: [`deployment/edc-participant-compose/README.md`](../edc-participant-compose/README.md).

## Compatibility notes

The Compose setup cites connector chart `0.12.1`; the local chart in this repository is `0.13.1-SNAPSHOT`. This package uses the local chart and carries its Wind-X image values explicitly. The local chart has small compatibility additions for Secret references, the Wind-X IATP BDRS variable names, and the Data Plane participant ID. Review and pin these chart and image versions together when updating the participant stack.
