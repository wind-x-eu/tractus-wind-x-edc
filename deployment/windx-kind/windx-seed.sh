#!/bin/sh
# Wind-X Dataspace Walkthrough (DID-Identitaet, DSP 2025) -- Stufe 1-3.
#
# Seedet am Provider Asset + permissive Policy + ContractDefinition, ruft den Katalog ab
# und fuehrt eine Contract-Negotiation bis FINALIZED durch. Alles ueber DSP 2025
# (dataspace-protocol-http:2025-1), rein DID-basiert -- KEIN BPN, KEINE Catena-X-Credentials.
#
# Reproduziert den am 2026-07-15 verifizierten Stand (docs/windx/phase-cluster-kind-ergebnisse.md, Weg C).
#
# Ausfuehren (cluster-intern, da kein Ingress). Vom Windows-Host mit Zugriff auf kind-mvd:
#
#   export KUBECONFIG=C:/Users/luf/Desktop/wind-x/kubernetes/.kube
#   B64=$(base64 -w0 deployment/windx-kind/windx-seed.sh)
#   kubectl --context kind-mvd -n windx run seed --rm -i --restart=Never \
#       --image=curlimages/curl:8.10.1 --command -- sh -c "echo $B64 | base64 -d | sh"
#
# Voraussetzung: windx-stack.yaml deployt, Setup-Job gelaufen, windx-sts-secrets gesetzt,
# Provider/Consumer Running (siehe windx-stack.yaml Kopf).

set -e
PROV=http://windx-provider:18181/management/v3
CONS=http://windx-consumer:28181/management/v3
KEY='X-Api-Key: password'
CT='Content-Type: application/json'
PROVIDER_DID='did:web:windx-identityhub:provider'
DSP='http://windx-provider:18182/protocol/2025-1'
PROTO='dataspace-protocol-http:2025-1'

post() { # url file -> druckt HTTP-Code (200 neu, 409 existiert bereits = ok)
  curl -s -o /dev/null -w "%{http_code}" -X POST "$1" -H "$KEY" -H "$CT" -d @"$2"
}

echo "==== 1) Provider seeden ===="

cat > /tmp/asset.json <<'JSON'
{ "@context": {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"},
  "@id": "windx-asset-1",
  "properties": {"description": "Wind-X DID test asset"},
  "dataAddress": {"@type": "DataAddress", "type": "HttpData",
                  "baseUrl": "http://windx-identityhub:8181/api/check/health"} }
JSON
echo "  asset  -> HTTP $(post "$PROV/assets" /tmp/asset.json)"

cat > /tmp/policy.json <<'JSON'
{ "@context": {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"},
  "@id": "windx-policy-allow",
  "policy": {"@context": "http://www.w3.org/ns/odrl.jsonld", "@type": "Set",
             "permission": [], "prohibition": [], "obligation": []} }
JSON
echo "  policy -> HTTP $(post "$PROV/policydefinitions" /tmp/policy.json)   (permissiv: keine Constraints)"

cat > /tmp/cd.json <<'JSON'
{ "@context": {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"},
  "@id": "windx-cd-1",
  "accessPolicyId": "windx-policy-allow",
  "contractPolicyId": "windx-policy-allow",
  "assetsSelector": [] }
JSON
echo "  cd     -> HTTP $(post "$PROV/contractdefinitions" /tmp/cd.json)"

echo "==== 2) Katalog (Consumer -> Provider, DSP 2025) ===="
cat > /tmp/cat.json <<JSON
{ "@context": {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"},
  "@type": "CatalogRequest",
  "counterPartyAddress": "$DSP",
  "counterPartyId": "did:web:windx-identityhub:provider",
  "protocol": "$PROTO",
  "querySpec": {"offset": 0, "limit": 10} }
JSON
CAT=$(curl -s -X POST "$CONS/catalog/request" -H "$KEY" -H "$CT" -d @/tmp/cat.json)
OFFER=$(echo "$CAT" | sed -n 's/.*"hasPolicy":\[{"@id":"\([^"]*\)".*/\1/p')
echo "  dataset: $(echo "$CAT" | grep -o '"@id":"windx-asset-1"' | head -1)"
echo "  offerId: $OFFER"
[ -z "$OFFER" ] && { echo "  KEINE Offer-ID -> Abbruch. Katalog:"; echo "$CAT"; exit 1; }

echo "==== 3) Contract-Negotiation (DSP 2025) -> FINALIZED ===="
cat > /tmp/nego.json <<JSON
{ "@context": ["http://www.w3.org/ns/odrl.jsonld", {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"}],
  "@type": "ContractRequest",
  "counterPartyAddress": "$DSP",
  "protocol": "$PROTO",
  "policy": {"@id": "$OFFER", "@type": "Offer", "assigner": "$PROVIDER_DID",
             "target": "windx-asset-1", "permission": [], "prohibition": [], "obligation": []} }
JSON
NID=$(curl -s -X POST "$CONS/contractnegotiations" -H "$KEY" -H "$CT" -d @/tmp/nego.json | sed -n 's/.*"@id":"\([^"]*\)".*/\1/p')
echo "  negotiationId: $NID"
[ -z "$NID" ] && { echo "  Abbruch"; exit 1; }

i=0
while [ $i -lt 20 ]; do
  S=$(curl -s "$CONS/contractnegotiations/$NID" -H "$KEY")
  STATE=$(echo "$S" | sed -n 's/.*"state":"\([^"]*\)".*/\1/p')
  echo "  [$i] state=$STATE"
  case "$STATE" in
    FINALIZED) echo "  ==> FINALIZED. agreementId=$(echo "$S" | sed -n 's/.*"contractAgreementId":"\([^"]*\)".*/\1/p')"; exit 0 ;;
    TERMINATED|ERROR) echo "  ==> $STATE"; echo "$S"; exit 2 ;;
  esac
  i=$((i+1)); sleep 3
done
echo "  Timeout ohne FINALIZED"; exit 3
