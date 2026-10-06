#!/bin/sh
set -eu

VAULT_TOKEN=$(cat /secrets/vault_token)
SUPERUSER_KEY=$(cat /secrets/superuser_key)
VAULT_URL=${VAULT_URL%/}
DATA_PATH=${VAULT_KV_V2_DATA_PATH#/}
HEALTH_PATH=${VAULT_HEALTH_PATH:-/v1/sys/health}
ALIAS='sup3r%243cr3t'

echo "vault-bootstrap: waiting for Vault ..."
while :; do
    code=$(curl -sS -o /dev/null -w '%{http_code}' "$VAULT_URL$HEALTH_PATH" || true)
    if [ "$code" = "200" ] || [ "$code" = "429" ]; then
        break
    fi
    sleep 3
done

code=$(curl -sS -o /tmp/vault-response -w '%{http_code}' -X POST \
    -H "X-Vault-Token: $VAULT_TOKEN" \
    -H 'Content-Type: application/json' \
    "$VAULT_URL/v1/$DATA_PATH/$ALIAS" \
    -d "{\"data\":{\"content\":\"$SUPERUSER_KEY\"}}")
case "$code" in
    2*) echo "vault-bootstrap: IdentityHub superuser alias is ready (HTTP $code)." ;;
    *) echo "vault-bootstrap: ERROR HTTP $code"; cat /tmp/vault-response; exit 1 ;;
esac
