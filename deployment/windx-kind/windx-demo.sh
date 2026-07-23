#!/bin/sh
# Wind-X Connector -- narrativer Demonstrator (in-cluster, DID-Identitaet, DSP 2025).
#
# Erzaehlt in 4 Akten, was der Connector kann: zwei Organisationen, die sich nie zuvor
# gesehen haben, bauen kryptografisches Vertrauen (DID) auf, handeln einen Nutzungsvertrag
# aus und tauschen Daten -- wobei der Vertrag das TOR ist (mit Vertrag HTTP 200, ohne 401).
#
# Fasst windx-seed.sh (Seed + Katalog + Negotiation) und transfer.sh (EDR + Datenabruf)
# zu einer durchgaengigen Vorfuehrung zusammen. Identische Requests wie diese beiden
# Skripte -- reproduziert den am 2026-07-15 verifizierten Stand (Weg C, DSP 2025).
#
# Zwei Darstellungs-Ebenen (fuer gemischtes Publikum):
#   * Story-Ebene  -- was passiert, in Klartext (immer sichtbar)
#   * Technik-Ebene -- echte Requests, IDs, HTTP-Codes (mit STORY_ONLY=1 ausblendbar)
#
# Ausfuehren -- am einfachsten ueber den Host-Launcher:
#   deployment/windx-kind/run-demo.sh
# oder direkt (cluster-intern, da kein Ingress):
#   B64=$(base64 -w0 deployment/windx-kind/windx-demo.sh)
#   kubectl --context kind-mvd -n windx run windx-demo --rm -i --restart=Never \
#       --image=curlimages/curl:8.10.1 --command -- sh -c "echo $B64 | base64 -d | sh"
#
# Voraussetzung: Stack deployt, Setup-Job gelaufen, windx-sts-secrets + EDR-Signing-Keys
# gesetzt (siehe deployment/windx-kind/README.md, Schritte 3-6). Ohne EDR-Keys endet Akt 4
# vor dem Datenabruf -- die ersten drei Akte laufen trotzdem.
#
# Steuerung per Env:  STORY_ONLY=1 (Technik ausblenden) · PAUSE=<sek> · NO_COLOR=1

# --- Konfiguration (identisch zu windx-seed.sh / transfer.sh) ---
PROV=http://windx-provider:18181/management/v3
CONS=http://windx-consumer:28181/management/v3
KEY='X-Api-Key: password'
CT='Content-Type: application/json'
PROVIDER_DID='did:web:windx-identityhub:provider'
DSP='http://windx-provider:18182/protocol/2025-1'
PROTO='dataspace-protocol-http:2025-1'

# --- Darstellung ---
: "${STORY_ONLY:=0}"   # 1 = technische Zeilen ausblenden
: "${PAUSE:=1}"        # Sekunden Pause zwischen den Akten
if [ -n "$NO_COLOR" ]; then
  B=; DIM=; G=; Y=; C=; M=; RS=
else
  e=$(printf '\033')
  B="${e}[1m"; DIM="${e}[2m"; G="${e}[1;32m"; Y="${e}[1;33m"; C="${e}[1;36m"; M="${e}[1;35m"; RS="${e}[0m"
fi

act()  { printf '\n%s========  %s  ========%s\n' "$C" "$1" "$RS"; }
say()  { printf '%s%s%s\n' "$B" "$1" "$RS"; }                       # Story-Ebene
sub()  { printf '%s%s%s\n' "$DIM" "$1" "$RS"; }                     # leiser Zusatz
tech() { [ "$STORY_ONLY" = 1 ] || printf '%s    | %s%s\n' "$DIM" "$1" "$RS"; }  # Technik-Ebene
ok()   { printf '%s  [OK] %s%s\n' "$G" "$1" "$RS"; }
warn() { printf '%s  [!]  %s%s\n' "$Y" "$1" "$RS"; }
pause(){ sleep "$PAUSE"; }

post() { curl -s -o /dev/null -w "%{http_code}" -X POST "$1" -H "$KEY" -H "$CT" -d @"$2"; }

# ============================================================================
printf '\n%s===========================================================%s\n' "$C" "$RS"
say   "  WIND-X CONNECTOR  --  Datensouveraenitaet in 4 Akten"
sub   "  Zwei Organisationen ohne Vorbeziehung tauschen Daten aus --"
sub   "  aber nur mit ausgehandeltem Vertrag. Identitaet: rein DID-basiert."
printf '%s===========================================================%s\n' "$C" "$RS"
pause

# ---------------------------------------------------------------------------
act "AKT 1 / 4   Der Provider stellt Daten bereit"
say "Der Datenanbieter (Provider) legt einen Datensatz online und definiert die Nutzungsregeln."
sub "Hier: ein Health-Endpunkt als Beispiel-Datenquelle, mit einer bewusst permissiven Policy."

cat > /tmp/asset.json <<'JSON'
{ "@context": {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"},
  "@id": "windx-asset-1",
  "properties": {"description": "Wind-X DID test asset"},
  "dataAddress": {"@type": "DataAddress", "type": "HttpData",
                  "baseUrl": "http://windx-identityhub:8181/api/check/health"} }
JSON
tech "POST /assets                 -> HTTP $(post "$PROV/assets" /tmp/asset.json)  (Datensatz windx-asset-1)"

cat > /tmp/policy.json <<'JSON'
{ "@context": {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"},
  "@id": "windx-policy-allow",
  "policy": {"@context": "http://www.w3.org/ns/odrl.jsonld", "@type": "Set",
             "permission": [], "prohibition": [], "obligation": []} }
JSON
tech "POST /policydefinitions      -> HTTP $(post "$PROV/policydefinitions" /tmp/policy.json)  (permissiv, keine Constraints)"

cat > /tmp/cd.json <<'JSON'
{ "@context": {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"},
  "@id": "windx-cd-1",
  "accessPolicyId": "windx-policy-allow",
  "contractPolicyId": "windx-policy-allow",
  "assetsSelector": [] }
JSON
tech "POST /contractdefinitions    -> HTTP $(post "$PROV/contractdefinitions" /tmp/cd.json)  (Angebot verknuepft)"
ok "Der Provider bietet 'windx-asset-1' an. (HTTP 409 = war schon vorhanden, auch gut.)"
pause

# ---------------------------------------------------------------------------
act "AKT 2 / 4   Identitaet & Katalog -- 'Was hast du anzubieten?'"
say "Der Datennutzer (Consumer) fragt den Katalog des Providers ab."
say "Beide weisen sich kryptografisch per DID aus -- ohne je vorher Schluessel getauscht zu haben."

cat > /tmp/cat.json <<JSON
{ "@context": {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"},
  "@type": "CatalogRequest",
  "counterPartyAddress": "$DSP",
  "counterPartyId": "$PROVIDER_DID",
  "protocol": "$PROTO",
  "querySpec": {"offset": 0, "limit": 10} }
JSON
CAT=$(curl -s -X POST "$CONS/catalog/request" -H "$KEY" -H "$CT" -d @/tmp/cat.json)
OFFER=$(echo "$CAT" | sed -n 's/.*"hasPolicy":\[{"@id":"\([^"]*\)".*/\1/p')
tech "POST /catalog/request  (DSP 2025) -> counterPartyId=$PROVIDER_DID"

if [ -z "$OFFER" ]; then
  warn "Keine Offer-ID erhalten -- Identitaetskette pruefen (windx-sts-secrets? README)."
  tech "Antwort: $CAT"
  exit 1
fi
ok "Vertrauen steht: der Katalog kam an. Datensatz 'windx-asset-1' ist sichtbar."
tech "offerId: $OFFER"

say "Der Provider bewirbt dabei die Wind-X-Transferarten -- unsere Extensions sind im Cluster live:"
for t in Mms-PULL MediatorHttpData-PULL MmsLight-PULL HttpData-PUSH; do
  echo "$CAT" | grep -q "$t" && ok "beworbene Transferart: $t"
done
pause

# ---------------------------------------------------------------------------
act "AKT 3 / 4   Der Vertrag wird ausgehandelt"
say "Der Consumer handelt einen Nutzungsvertrag aus und startet zugleich den Transfer."
say "Beobachte die Zustaende:  angefragt  ->  vereinbart  ->  besiegelt."

cat > /tmp/edr.json <<JSON
{ "@context": ["http://www.w3.org/ns/odrl.jsonld", {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"}],
  "@type": "ContractRequest", "counterPartyAddress": "$DSP", "protocol": "$PROTO",
  "policy": {"@id": "$OFFER", "@type": "Offer", "assigner": "$PROVIDER_DID", "target": "windx-asset-1",
             "permission": [], "prohibition": [], "obligation": []},
  "callbackAddresses": [] }
JSON
NID=$(curl -s -X POST "$CONS/edrs" -H "$KEY" -H "$CT" -d @/tmp/edr.json | sed -n 's/.*"@id":"\([^"]*\)".*/\1/p')
tech "POST /edrs  -> negotiationId=$NID"
[ -z "$NID" ] && { warn "Keine negotiationId -- Abbruch."; exit 1; }

cat > /tmp/q.json <<JSON
{ "@context": {"@vocab": "https://w3id.org/edc/v0.0.1/ns/"}, "@type": "QuerySpec",
  "filterExpression": [{"operandLeft": "contractNegotiationId", "operator": "=", "operandRight": "$NID"}] }
JSON
TPID=""; LAST=""; AGID=""; i=0
while [ $i -lt 25 ]; do
  S=$(curl -s "$CONS/contractnegotiations/$NID" -H "$KEY")
  STATE=$(echo "$S" | sed -n 's/.*"state":"\([^"]*\)".*/\1/p')
  if [ "$STATE" != "$LAST" ]; then
    case "$STATE" in
      REQUESTED) sub "   ... Vertrag angefragt (REQUESTED)" ;;
      AGREED)    sub "   ... Konditionen vereinbart (AGREED)" ;;
      VERIFIED)  sub "   ... Vertrag geprueft (VERIFIED)" ;;
      FINALIZED) AGID=$(echo "$S" | sed -n 's/.*"contractAgreementId":"\([^"]*\)".*/\1/p') ;;
      TERMINATED|ERROR) warn "Verhandlung fehlgeschlagen: $STATE"; tech "$S"; exit 2 ;;
    esac
    LAST="$STATE"
  fi
  R=$(curl -s -X POST "$CONS/edrs/request" -H "$KEY" -H "$CT" -d @/tmp/q.json)
  TPID=$(echo "$R" | sed -n 's/.*"transferProcessId":"\([^"]*\)".*/\1/p')
  [ "$STATE" = "FINALIZED" ] && [ -n "$TPID" ] && break
  i=$((i+1)); sleep 3
done
if [ "$LAST" != "FINALIZED" ]; then
  warn "Kein FINALIZED innerhalb des Zeitfensters -- Abbruch."; exit 3
fi
ok "Vertrag besiegelt (FINALIZED). Es existiert jetzt ein signiertes Agreement."
tech "agreementId: $AGID"
tech "transferProcessId: $TPID"
pause

# ---------------------------------------------------------------------------
act "AKT 4 / 4   Das Tor -- der Vertrag entscheidet ueber den Zugriff"
say "Der Vertrag materialisiert sich als Zugriffs-Token (EDR). Jetzt der entscheidende Moment:"
say "ein und dieselbe Datenadresse -- einmal MIT Token, einmal OHNE."

DA=$(curl -s "$CONS/edrs/$TPID/dataaddress?auto_refresh=true" -H "$KEY")
EP=$(echo "$DA" | sed -n 's/.*"endpoint":"\([^"]*\)".*/\1/p')
TOK=$(echo "$DA" | sed -n 's/.*"authorization":"\([^"]*\)".*/\1/p')
tech "GET /edrs/$TPID/dataaddress -> endpoint=$EP"
if [ -z "$EP" ]; then
  warn "Kein Endpoint/Token erhalten -- der Provider hat vermutlich keine echten EDR-Signing-Keys."
  sub  "  (Akt 1-3 sind der Kern der Demo; fuer Akt 4 EDR-Keys seeden -- README Schritt 4-6.)"
  tech "$DA"
  exit 0
fi

printf '\n'
say ">> MIT Vertrag (Token vorhanden):"
BODY=$(curl -s -w '\n%{http_code}' -H "Authorization: $TOK" "$EP")
CODE=$(printf '%s' "$BODY" | tail -n1)
DATA=$(printf '%s' "$BODY" | sed '$d')
if [ "$CODE" = "200" ]; then
  ok "HTTP 200 -- die Daten fliessen."
  printf '%s     %s%s\n' "$G" "$DATA" "$RS"
else
  warn "Unerwartet HTTP $CODE"; tech "$DATA"
fi

printf '\n'
say ">> OHNE Vertrag (Gegenprobe, kein Token):"
CODE2=$(curl -s -o /dev/null -w "%{http_code}" "$EP")
if [ "$CODE2" = "401" ]; then
  ok "HTTP 401 -- dieselbe Adresse, aber die Tuer bleibt zu."
else
  warn "Erwartet 401, erhalten HTTP $CODE2"
fi

# ---------------------------------------------------------------------------
printf '\n%s===========================================================%s\n' "$M" "$RS"
say   "  FAZIT"
say   "  Der Vertrag IST das Tor. Ohne Agreement kein Token, ohne Token"
say   "  keine Daten -- selbst fuer einen authentifizierten Partner."
sub   "  Das ist Datensouveraenitaet, durchgesetzt vom Connector."
printf '%s===========================================================%s\n' "$M" "$RS"
