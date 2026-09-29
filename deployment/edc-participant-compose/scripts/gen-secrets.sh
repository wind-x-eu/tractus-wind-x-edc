#!/bin/sh
# =============================================================================
# Dienst "secrets": erzeugt beim ERSTEN Start die vier Geheimnisse als Dateien im
# Volume "secrets" (/secrets). Vorhandene Dateien bleiben unveraendert -- sonst
# passten Datenbank und Vault-Sicherung nicht mehr zu den Schluesseln.
#
#   /secrets/vault_token        Root-Token des Vaults (Dev-Modus)
#   /secrets/mgmt_key           X-Api-Key der Management-API
#   /secrets/superuser_key      Superuser der Identity-API (base64("super-user").<zufall>)
#   /secrets/postgres_password  Passwort der Datenbank
#
# Auslesen, z.B. den Management-Key:
#   docker compose exec controlplane sh -c 'cat /secrets/mgmt_key'
# =============================================================================
set -eu
D=/secrets
rnd() { head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n'; }
mk() {
    if [ -s "$D/$1" ]; then
        echo "secrets: $1 vorhanden -- unveraendert"
    else
        printf '%s' "$2" > "$D/$1"; echo "secrets: $1 erzeugt"
    fi
    chmod 644 "$D/$1"      # lesbar fuer die Dienst-Benutzer (postgres, vault, docker)
}
mk vault_token       "$(rnd)"
mk mgmt_key          "$(rnd)"
mk superuser_key     "c3VwZXItdXNlcg==.$(rnd)"
mk postgres_password "$(rnd)"
