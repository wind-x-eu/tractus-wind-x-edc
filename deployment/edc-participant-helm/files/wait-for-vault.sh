#!/bin/sh
set -eu
token=$(cat /secrets/vault_token)
url="${VAULT_URL%/}/v1/${VAULT_KV_V2_DATA_PATH#/}/sup3r%243cr3t"
until [ "$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' -H "X-Vault-Token: $token" "$url" || true)" = 200 ]; do
    echo 'waiting for Vault restore/bootstrap'
    sleep 3
done
