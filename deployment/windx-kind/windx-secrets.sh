#!/usr/bin/env bash
# Erzeugt alle statischen Geheimnisse des windx-Stacks als k8s-Secrets (WP4).
#
# WARUM NICHT IN windx-stack.yaml?
#   Ein Secret, dessen Wert im committeten YAML steht, ist kein Geheimnis -- base64 ist keine
#   Verschluesselung, der Wert liegt im Klartext in Git. Deshalb werden die Werte hier erzeugt,
#   nicht versioniert. Gleiches Muster wie windx-sts-secrets / windx-provider-vault-seed /
#   windx-edr-keys, die schon immer per Kommando entstehen.
#
# WAS WIRD ERZEUGT?
#   windx-superuser-apikey   apikey                    (generiert)  IdentityHub-Superuser + Setup-Job
#   windx-ih-secrets         sts-privatekey            (generiert)  IdentityHub STS-Signaturschluessel (P-256 PEM)
#                            aes-key                   (generiert)  IdentityHub AES-Schluessel
#                            api-auth-key              (Dev: password)
#                            sts-accounts-api-auth-key (Dev: password)
#   windx-connector-api-key  apikey                    (Dev: password)  Management-API Provider/Consumer
#   windx-db                 password                  (Dev-Passwort)   PostgreSQL
#
# Krypto-Material wird generiert (daran haengt kein Skript). Die API-Keys bleiben per Default
# 'password', weil windx-seed.sh, transfer.sh, windx-demo.sh und windx-dashboard.py sie hart
# kodieren -- sonst laeuft die Demo nicht mehr. Ueberschreibbar per Env, z. B.:
#   API_KEY=geheim DB_PASSWORD=geheim ./windx-secrets.sh
#
# IDEMPOTENT: vorhandene Secrets werden NICHT angefasst (Werte bleiben stabil -- wichtig, sobald
# der IdentityHub persistent ist: ein wechselnder AES-Key macht verschluesselte DB-Inhalte unlesbar).
# Neu erzeugen erzwingen:  ./windx-secrets.sh --force
#
# Nutzung:
#   ./windx-secrets.sh              # anlegen, was fehlt
#   ./windx-secrets.sh --force      # alle neu erzeugen (danach Setup-Job + Re-Seed noetig!)

set -euo pipefail

CTX="${CTX:---context kind-mvd}"
NS="${NS:-windx}"
API_KEY="${API_KEY:-password}"
DB_PASSWORD="${DB_PASSWORD:-windx-edc-pw}"
FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

kc() { kubectl $CTX -n "$NS" "$@"; }

exists() { kc get secret "$1" >/dev/null 2>&1; }

# Legt ein Secret an; ueberspringt es, wenn es schon existiert (ausser --force).
ensure() {
    name="$1"; shift
    if exists "$name"; then
        if [ "$FORCE" = "1" ]; then
            echo "  ~ $name existiert -> wird neu erzeugt (--force)"
            kc delete secret "$name" >/dev/null
        else
            echo "  = $name existiert bereits -> unveraendert"
            return 0
        fi
    fi
    kc create secret generic "$name" "$@" >/dev/null
    echo "  + $name angelegt"
}

echo "windx-Secrets in Namespace '$NS' ($CTX):"

# --- IdentityHub-Superuser-Key -------------------------------------------------
# Format, das der Auth-Filter erwartet: base64(principalId).base64(random)
SU_TOKEN="$(printf 'super-user' | base64 -w0).$(openssl rand -hex 16 | base64 -w0)"
ensure windx-superuser-apikey --from-literal=apikey="$SU_TOKEN"

# --- IdentityHub: STS-Signaturschluessel + AES-Key + API-Keys -------------------
# P-256/SEC1-PEM, gleiche Kurve wie der bisherige fest verdrahtete Schluessel.
openssl ecparam -name prime256v1 -genkey -noout -out "$TMP/sts.pem" 2>/dev/null
ensure windx-ih-secrets \
    --from-file=sts-privatekey="$TMP/sts.pem" \
    --from-literal=aes-key="$(openssl rand -base64 32)" \
    --from-literal=api-auth-key="$API_KEY" \
    --from-literal=sts-accounts-api-auth-key="$API_KEY"

# --- Management-API-Key der Connectoren ----------------------------------------
ensure windx-connector-api-key --from-literal=apikey="$API_KEY"

# --- PostgreSQL ----------------------------------------------------------------
ensure windx-db --from-literal=password="$DB_PASSWORD"

echo
echo "Fertig. Superuser-Key bei Bedarf auslesen:"
echo "  kubectl $CTX -n $NS get secret windx-superuser-apikey -o jsonpath='{.data.apikey}' | base64 -d"
