#!/bin/sh
# =============================================================================
# Deploy-Schritt 5 -- Participant Context im IdentityHub anlegen.
# Dabei entstehen: Ed25519-Schluesselpaar (privat in den Vault unter
# <did>#signing-key-1), STS-Konto (Secret unter <did>-sts-client-secret) und das
# DID-Dokument mit CredentialService + ProtocolEndpoint (beide https).
#
# IDEMPOTENT: existiert der Participant schon, wird NICHTS geaendert -- ein
# zweiter Lauf wuerde sonst einen neuen Schluessel erzeugen (= neue Identitaet).
# =============================================================================
set -eu
SUPERUSER_KEY=$(cat /secrets/superuser_key)      # aus dem Volume "secrets"
DID="did:web:$PUBLIC_HOST"
B64=$(printf '%s' "$DID" | base64 | tr -d '\n')                       # mit '=' (CredentialService-URL)
CTX=$(printf '%s' "$B64" | tr -d '=' | tr '+/' '-_')                   # base64url ohne '=' (API-Pfad)
IH=http://identityhub:8082/api/identity/v1alpha/participants
H="X-Api-Key: $SUPERUSER_KEY"

echo "ih-provision: warte auf die Identity-API ..."
i=0
until [ "$(curl -s -o /dev/null -w '%{http_code}' -H "$H" $IH)" = "200" ]; do
    i=$((i+1)); [ $i -gt 100 ] && { echo "FEHLER: Identity-API antwortet nicht mit 200"; exit 1; }
    sleep 3
done

if [ "$(curl -s -o /dev/null -w '%{http_code}' -H "$H" $IH/$CTX)" = "200" ]; then
    echo "ih-provision: Participant $DID existiert bereits -- unveraendert."
    exit 0
fi

code=$(curl -s -o /tmp/r.json -w '%{http_code}' -X POST $IH -H "$H" -H 'Content-Type: application/json' -d "{
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
    2*)  echo "ih-provision: Participant $DID angelegt (HTTP $code)." ;;
    409) echo "ih-provision: existiert bereits (HTTP 409)." ;;
    *)   echo "ih-provision: FEHLER HTTP $code"; cat /tmp/r.json; exit 1 ;;
esac
