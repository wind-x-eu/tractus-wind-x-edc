#!/usr/bin/env bash
# Nimmt die Wind-X-Teilnehmer in den Catena-X-Wallet-Stub des Umbrella auf (Phase 2).
#
# WARUM DIESES SKRIPT?
#   Der Helm-Wert `identity-and-trust-bundle.ssi-dim-wallet-stub.wallet.seeding.bpnList` reicht NICHT:
#   Das Seeding des Stubs laeuft nur bei LEERER Datenbank -- es gibt keinen ApplicationRunner, der es
#   bei einem Neustart wiederholt. Ergaenzt man die Liste nachtraeglich und rollt den Pod, passiert nichts.
#   Der offizielle Weg sind stattdessen die beiden APIs, die auch das Catena-X-Portal beim Onboarding
#   einer Firma benutzt (gefunden in der OpenAPI des Stubs unter /docs/api-docs -- NICHT /v3/api-docs):
#
#     1. POST /api/dim/setup-dim?companyName=..&bpn=..     -> Keypair + DID-Dokument
#        ACHTUNG: asynchron! Der Stub antwortet sofort 201, wartet dann PORTAL_WAIT_TIME (60 s)
#        und legt erst danach an. Zu frueh in der DB nachsehen => sieht leer aus.
#
#     2. POST /api/v2.0.0/dcp/requestCredentials/catena-x-portal  -> MembershipCredential & Co.
#        Muss mit dem TOKEN DES HOLDERS aufgerufen werden. Mit dem Issuer-Token lehnt der Stub ab:
#        "Caller BPN does not match the holder wallet DID".
#
#   Schluessel und DIDs werden deterministisch aus der BPN abgeleitet ("Everytime same wallet
#   generated with bpn") -- ein erneuter Lauf ist daher unschaedlich.
#
# VORAUSSETZUNG: kubectl-Zugriff auf den Uni-K3s (Kontext try1); der Stub laeuft im ns umbrella.
# NUTZUNG:  ./windx-wallet-onboarding.sh [--verify-only]

set -euo pipefail

CTX="${CTX:---context try1}"
NS="${NS:-umbrella}"
PROVIDER_BPN="${PROVIDER_BPN:-BPNL00000003AVTH}"
CONSUMER_BPN="${CONSUMER_BPN:-BPNL00000003AWSS}"
ISSUER_BPN="${ISSUER_BPN:-BPNL00000003CRHK}"     # BASE_WALLET_BPN des Stubs
CRED_TYPES="${CRED_TYPES:-MembershipCredential BpnCredential DataExchangeGovernanceCredential}"
WAIT="${WAIT:-75}"                                # > PORTAL_WAIT_TIME (60 s)
VERIFY_ONLY=0
[ "${1:-}" = "--verify-only" ] && VERIFY_ONLY=1

WALLET_SVC="http://ssi-dim-wallet-service.${NS}.svc.cluster.local:8080"
DID_PREFIX="did:web:ssi-dim-wallet-stub.tx.test"

# Fuehrt ein Skript in einem Wegwerf-curl-Pod im Cluster aus (der Stub ist nur clusterintern erreichbar).
in_cluster() {
    local b64
    b64=$(base64 -w0)
    kubectl $CTX -n default run "windx-onboard-$RANDOM" --rm -i --restart=Never \
        --image=curlimages/curl:8.10.1 --command -- sh -c "echo $b64 | base64 -d | sh" 2>/dev/null \
        | grep -vE "pod .* deleted"
}

verify() {
    echo "== Wallet-Zustand in der DB =="
    kubectl $CTX -n "$NS" exec wallet-postgres-0 -- \
        env PGPASSWORD=postgrespassword psql -U postgres -d postgres -tAc \
        "SELECT holder_bpn||'  '||replace(key, holder_bpn||'###','') FROM holder_credential
         WHERE holder_bpn IN ('$PROVIDER_BPN','$CONSUMER_BPN') ORDER BY 1;" 2>/dev/null | sed 's/^/  /'
    echo "  -- erwartet: je 3 Credentials (Membership/Bpn/DataExchangeGovernance) pro BPN"
}

if [ "$VERIFY_ONLY" = "1" ]; then verify; exit 0; fi

echo "== 1) Wallets anlegen (setup-dim, asynchron) =="
{
    echo "W=$WALLET_SVC"
    for pair in "$PROVIDER_BPN:WindX-Provider" "$CONSUMER_BPN:WindX-Consumer"; do
        bpn="${pair%%:*}"; name="${pair##*:}"
        echo "printf '  %-18s setup-dim -> HTTP ' '$bpn'"
        echo "curl -s -o /dev/null -w '%{http_code}\\n' -X POST \"\$W/api/dim/setup-dim?companyName=$name&bpn=$bpn&didDocumentLocation=ssi-dim-wallet-stub.tx.test\""
    done
} | in_cluster

echo "   warte ${WAIT}s (PORTAL_WAIT_TIME) ..."
sleep "$WAIT"

echo "== 2) Credentials ausstellen (DCP, mit Holder-Token) =="
{
    echo "W=$WALLET_SVC"
    echo "ISS=$DID_PREFIX:$ISSUER_BPN"
    echo 'tok() { curl -s -X POST "$W/oauth/token" -H "Content-Type: application/x-www-form-urlencoded" -d "grant_type=client_credentials&client_id=$1&client_secret=$1" | sed -n '"'"'s/.*"access_token":"\([^"]*\)".*/\1/p'"'"'; }'
    for bpn in "$PROVIDER_BPN" "$CONSUMER_BPN"; do
        echo "echo '  --- $bpn'"
        echo "T=\$(tok $bpn)"
        for t in $CRED_TYPES; do
            echo "printf '    %-34s HTTP ' '$t'"
            echo "curl -s -o /dev/null -w '%{http_code}\\n' -X POST \"\$W/api/v2.0.0/dcp/requestCredentials/catena-x-portal\" -H \"Authorization: Bearer \$T\" -H 'Content-Type: application/json' -d '{\"requestedCredentials\":[{\"credentialType\":\"$t\",\"format\":\"vcdm11_jwt\"}],\"issuerDid\":\"'\"\$ISS\"'\",\"holderDid\":\"$DID_PREFIX:$bpn\",\"expirationDate\":\"2028-11-25T16:51:22.000Z\"}'"
        done
    done
} | in_cluster

echo
verify
