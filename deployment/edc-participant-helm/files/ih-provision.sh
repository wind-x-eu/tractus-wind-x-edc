#!/bin/sh
set -eu

SUPERUSER_KEY=$(cat /secrets/superuser_key)
DID="did:web:$PUBLIC_HOST"
B64=$(printf '%s' "$DID" | base64 | tr -d '\n')
CTX=$(printf '%s' "$B64" | tr -d '=' | tr '+/' '-_')
IH=${IDENTITYHUB_API_URL:-http://identityhub:8082/api/identity/v1alpha/participants}
H="X-Api-Key: $SUPERUSER_KEY"

echo "ih-provision: waiting for the IdentityHub API ..."
i=0
until [ "$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' -H "$H" "$IH" || true)" = "200" ]; do
    i=$((i+1))
    [ "$i" -gt 100 ] && { echo "ERROR: IdentityHub API did not become ready"; exit 1; }
    sleep 3
done

code=$(curl -sS --max-time 30 -o /tmp/response.json -w '%{http_code}' -H "$H" "$IH/$CTX")
case "$code" in
    200) echo "ih-provision: Participant $DID already exists; leaving its key unchanged."; exit 0 ;;
    404) ;; # Create only when the API confirms the participant is absent.
    *) echo "ih-provision: ERROR checking participant (HTTP $code)"; exit 1 ;;
esac

code=$(curl -sS --max-time 60 -o /tmp/response.json -w '%{http_code}' -X POST "$IH" \
    -H "$H" -H 'Content-Type: application/json' -d "{
  \"active\": true,
  \"did\": \"$DID\",
  \"participantContextId\": \"$DID\",
  \"key\": {
    \"keyGeneratorParams\": {\"algorithm\": \"EdDSA\", \"curve\": \"Ed25519\"},
    \"keyId\": \"$DID#signing-key-1\",
    \"privateKeyAlias\": \"$DID#signing-key-1\"
  },
  \"serviceEndpoints\": [
    {\"type\": \"CredentialService\", \"id\": \"credentialservice-1\",
     \"serviceEndpoint\": \"https://$PUBLIC_HOST/api/credentials/v1/participants/$B64\"},
    {\"type\": \"ProtocolEndpoint\", \"id\": \"dsp-url\",
     \"serviceEndpoint\": \"https://$PUBLIC_HOST/api/v1/dsp\"}
  ]}")
case "$code" in
    2*) echo "ih-provision: Participant $DID created (HTTP $code)." ;;
    409) echo "ih-provision: Participant already exists (HTTP 409)." ;;
    *) echo "ih-provision: ERROR HTTP $code"; cat /tmp/response.json; exit 1 ;;
esac
