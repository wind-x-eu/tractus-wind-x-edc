#!/bin/sh
# Wind-X Dataspace Walkthrough -- Stufe 4: echter Datentransfer (Consumer-Pull, HttpData).
#
# Setzt voraus, dass windx-seed.sh gelaufen ist (Asset/Policy/ContractDefinition vorhanden)
# UND der Provider echte signer/verifier-EC-Keys im Vault hat (siehe README, "EDR-Keys").
#
# Ablauf: Katalog -> POST /edrs (Negotiation + Transfer) -> auf transferProcessId warten ->
#         dataaddress (Endpoint + Token) holen -> Daten mit Token pullen -> Gegenprobe ohne Token.
#
# Ausfuehren (cluster-intern):
#   B64=$(base64 -w0 deployment/windx-kind/transfer.sh)
#   kubectl --context kind-mvd -n windx run xfer --rm -i --restart=Never \
#       --image=curlimages/curl:8.10.1 --command -- sh -c "echo $B64 | base64 -d | sh"
#
# Erwartetes Ergebnis: "MIT Token" -> HTTP 200 + Backend-Body; "OHNE Token" -> HTTP 401.

CONS=http://windx-consumer:28181/management/v3
KEY='X-Api-Key: password'
CT='Content-Type: application/json'
DSP='http://windx-provider:18182/protocol/2025-1'
PROTO='dataspace-protocol-http:2025-1'
PROVIDER_DID='did:web:windx-identityhub:provider'

echo "==== A) Katalog -> Offer-ID ===="
cat > /tmp/cat.json <<JSON
{ "@context": {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"}, "@type": "CatalogRequest",
  "counterPartyAddress": "$DSP", "counterPartyId": "$PROVIDER_DID", "protocol": "$PROTO",
  "querySpec": {"offset": 0, "limit": 10} }
JSON
OFFER=$(curl -s -X POST "$CONS/catalog/request" -H "$KEY" -H "$CT" -d @/tmp/cat.json | sed -n 's/.*"hasPolicy":\[{"@id":"\([^"]*\)".*/\1/p')
echo "  offer: $OFFER"
[ -z "$OFFER" ] && { echo "  Abbruch: keine Offer-ID (windx-seed.sh gelaufen?)"; exit 1; }

echo "==== B) POST /edrs (Negotiation + Transfer + EDR) ===="
cat > /tmp/edr.json <<JSON
{ "@context": ["http://www.w3.org/ns/odrl.jsonld", {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"}],
  "@type": "ContractRequest", "counterPartyAddress": "$DSP", "protocol": "$PROTO",
  "policy": {"@id": "$OFFER", "@type": "Offer", "assigner": "$PROVIDER_DID", "target": "windx-asset-1",
             "permission": [], "prohibition": [], "obligation": []},
  "callbackAddresses": [] }
JSON
NID=$(curl -s -X POST "$CONS/edrs" -H "$KEY" -H "$CT" -d @/tmp/edr.json | sed -n 's/.*"@id":"\([^"]*\)".*/\1/p')
echo "  negotiationId: $NID"
[ -z "$NID" ] && { echo "  Abbruch"; exit 1; }

echo "==== C) Auf transferProcessId warten ===="
cat > /tmp/q.json <<JSON
{ "@context": {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"}, "@type": "QuerySpec",
  "filterExpression": [{"operandLeft": "contractNegotiationId", "operator": "=", "operandRight": "$NID"}] }
JSON
TPID=""
i=0
while [ $i -lt 25 ]; do
  R=$(curl -s -X POST "$CONS/edrs/request" -H "$KEY" -H "$CT" -d @/tmp/q.json)
  TPID=$(echo "$R" | sed -n 's/.*"transferProcessId":"\([^"]*\)".*/\1/p')
  NST=$(curl -s "$CONS/contractnegotiations/$NID" -H "$KEY" | sed -n 's/.*"state":"\([^"]*\)".*/\1/p')
  echo "  [$i] negState=$NST  transferProcessId=$TPID"
  [ -n "$TPID" ] && break
  i=$((i+1)); sleep 3
done
[ -z "$TPID" ] && { echo "  Abbruch: keine transferProcessId"; echo "$R"; exit 2; }

echo "==== D) dataaddress (Endpoint + Token) ===="
DA=$(curl -s "$CONS/edrs/$TPID/dataaddress?auto_refresh=true" -H "$KEY")
EP=$(echo "$DA" | sed -n 's/.*"endpoint":"\([^"]*\)".*/\1/p')
TOK=$(echo "$DA" | sed -n 's/.*"authorization":"\([^"]*\)".*/\1/p')
echo "  endpoint: $EP"
[ -z "$EP" ] && { echo "  Abbruch: kein Endpoint"; echo "$DA"; exit 3; }

echo "==== E) DATENABRUF ===="
echo "--- MIT Token (erwartet HTTP 200 + Body):"
curl -s -w "\n  [HTTP %{http_code}]\n" -H "Authorization: $TOK" "$EP"
echo "--- Gegenprobe OHNE Token (erwartet 401):"
curl -s -o /dev/null -w "  [HTTP %{http_code}]\n" "$EP"
