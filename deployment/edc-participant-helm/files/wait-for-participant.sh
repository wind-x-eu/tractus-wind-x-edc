#!/bin/sh
set -eu
key=$(cat /secrets/superuser_key)
ctx=$(printf 'did:web:%s' "$PUBLIC_HOST" | base64 | tr -d '\n=' | tr '+/' '-_')
until [ "$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' -H "X-Api-Key: $key" "$IDENTITYHUB_API_URL/$ctx" || true)" = 200 ]; do
    echo 'waiting for IdentityHub participant provisioning'
    sleep 3
done
