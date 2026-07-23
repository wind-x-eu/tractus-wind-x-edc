#!/usr/bin/env bash
# Erzeugt das Signier-Schluesselpaar, mit dem die Data-Plane ihre Zugriffstoken (EDR) signiert,
# und legt es als Vault-Overlay ab.
#
# WARUM: Das offizielle Chart laesst dataplane.token.signer.privatekey_alias auf "CHANGEME" stehen.
# Ohne echten Schluessel schlaegt JEDER Transfer fehl -- die Negotiation wird noch FINALIZED, aber
# der Provider quittiert die Transferanfrage mit
#     "Could not generate access token: JWSSigner cannot be generated for private key 'CHANGEME...'"
# und der Transferprozess geht auf TERMINATED. Es gibt dann schlicht keine EDR.
#
# WARUM ALS OVERLAY: Der Vault laeuft im dev-mode (in-memory). Nur was in vault.server.postStart
# steht, wird nach einem Neustart neu geseedet. Schluesselmaterial gehoert aber nicht ins Git --
# deshalb schreibt dieses Skript eine separate values-Datei, die per .gitignore ausgeschlossen ist.
#
# Aufruf:  ./windx-cx-dataplane-keys.sh
# Danach:  helm upgrade ... -f values-windx-<rolle>.yaml -f values-windx-<rolle>-keys.yaml
#
# Idempotent: vorhandene Overlays werden nur mit --force neu erzeugt (sonst wechseln die Schluessel
# und bereits ausgestellte Token werden ungueltig).
set -euo pipefail
cd "$(dirname "$0")"

FORCE="${1:-}"

gen_role() {
    local role="$1" bpn="$2"
    local out="values-windx-${role}-keys.yaml"

    if [ -f "$out" ] && [ "$FORCE" != "--force" ]; then
        echo "  $out existiert bereits -- unveraendert (--force zum Neuerzeugen)"
        return
    fi

    local tmp priv pub
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' RETURN

    # EC P-256 -> ES256, das ist der Default-Algorithmus der EDC-Data-Plane.
    openssl ecparam -name prime256v1 -genkey -noout -out "$tmp/ec.pem" 2>/dev/null
    openssl pkcs8 -topk8 -nocrypt -in "$tmp/ec.pem" -out "$tmp/priv.pem" 2>/dev/null
    openssl ec -in "$tmp/ec.pem" -pubout -out "$tmp/pub.pem" 2>/dev/null

    # Base64 in EINER Zeile: der Vault-Container hat kein openssl, aber busybox-base64.
    # So bleibt das PEM (mehrzeilig) in einem einzeiligen Shell-Kommando transportierbar.
    priv="$(base64 -w0 < "$tmp/priv.pem")"
    pub="$(base64 -w0 < "$tmp/pub.pem")"

    cat > "$out" <<YAML
# ERZEUGT von windx-cx-dataplane-keys.sh -- NICHT ins Git, NICHT von Hand editieren.
# Enthaelt das ES256-Signierschluesselpaar der Data-Plane (${role}, ${bpn}).
#
# Ueberschreibt vault.server.postStart vollstaendig, seedet daher AUCH das Wallet-Secret --
# sonst ginge die DCP-Authentifizierung beim naechsten Vault-Neustart verloren.
vault:
  server:
    postStart:
      - sh
      - -c
      - |-
        {
        sleep 5
        /bin/vault kv put secret/edc-wallet-secret content=${bpn}
        /bin/vault kv put secret/windx-dp-signer-private content="\$(echo ${priv} | base64 -d)"
        /bin/vault kv put secret/windx-dp-signer-public content="\$(echo ${pub} | base64 -d)"
        }
YAML
    echo "  $out geschrieben (EC P-256, Aliase windx-dp-signer-private/-public)"
}

echo "==== Data-Plane-Signierschluessel erzeugen ===="
gen_role provider BPNL00000003AVTH
gen_role consumer BPNL00000003AWSS

cat <<'EOF'

Naechster Schritt (beide Connectoren):
  helm upgrade windx-provider ../../charts/tractusx-connector -n windx \
      -f values-windx-provider.yaml -f values-windx-provider-keys.yaml
  helm upgrade windx-consumer ../../charts/tractusx-connector -n windx \
      -f values-windx-consumer.yaml -f values-windx-consumer-keys.yaml
EOF
