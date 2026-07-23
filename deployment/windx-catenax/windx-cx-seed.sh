#!/bin/sh
# Phase 4: Grundfluss der Catena-X-Variante -- Asset + ECHTE Membership-Policy + ContractDef seeden,
# danach Katalog und Contract-Negotiation vom Consumer aus.
#
# Unterschied zu Weg C (windx-seed.sh im KinD): dort musste die Policy PERMISSIV sein (leere Constraints),
# weil der plain-EDC-IdentityHub keine BPN-tragenden Credentials ausstellt. Hier ist cx-dcp aktiv und der
# Wallet-Stub stellt ein MembershipCredential aus -> echte Zugriffskontrolle ueber Credentials.
#
# Laeuft IM Cluster (curl-Pod), da die Connectoren nur clusterintern erreichbar sind.

PROV_MGMT="http://windx-provider-tractusx-connector-controlplane:8081/management"
CONS_MGMT="http://windx-consumer-tractusx-connector-controlplane:8081/management"
PROV_DSP="http://windx-provider-tractusx-connector-controlplane:8084/api/v1/dsp"
PROV_DID="did:web:ssi-dim-wallet-stub.tx.test:BPNL00000003AVTH"
KEY="X-Api-Key: password"
JSON="Content-Type: application/json"
CX="https://w3id.org/catenax/2025/9/policy/"

post() { # $1=url $2=body -> gibt HTTP-Code aus, Body in /tmp/o.json
    curl -s -o /tmp/o.json -w "%{http_code}" -X POST "$1" -H "$KEY" -H "$JSON" -d "$2"
}

echo "==== 1) Asset (Backend: Health-Endpunkt der Provider-Control-Plane) ===="
code=$(post "$PROV_MGMT/v3/assets" '{
  "@context":{"@vocab":"https://w3id.org/edc/v0.0.1/ns/"},
  "@id":"windx-cx-asset-1",
  "properties":{"name":"Wind-X Catena-X Demo Asset"},
  "dataAddress":{"type":"HttpData","baseUrl":"http://windx-provider-tractusx-connector-controlplane:8080/api/check/health","proxyMethod":"true","proxyQueryParams":"true"}
}')
echo "  POST /v3/assets -> HTTP $code"; [ "$code" = "409" ] && echo "  (existiert bereits -- ok)"

echo "==== 2) ECHTE Membership-Policy (Constraint: Membership eq active) ===="
code=$(post "$PROV_MGMT/v3/policydefinitions" '{
  "@context":["https://w3id.org/dspace/2025/1/odrl-profile.jsonld",{"@vocab":"https://w3id.org/edc/v0.0.1/ns/"}],
  "@id":"windx-membership-policy",
  "policy":{
    "@type":"Set",
    "permission":[{
      "action":"'"$CX"'access",
      "constraint":{
        "@type":"LogicalConstraint",
        "and":[{
          "leftOperand":"'"$CX"'Membership",
          "operator":"eq",
          "rightOperand":"active"
        }]
      }
    }]
  }
}')
echo "  POST /v3/policydefinitions -> HTTP $code"
[ "$code" != "200" ] && [ "$code" != "409" ] && { echo "  FEHLER:"; head -c 400 /tmp/o.json; echo; }

echo "==== 3) ContractDefinition ===="
code=$(post "$PROV_MGMT/v3/contractdefinitions" '{
  "@context":{"@vocab":"https://w3id.org/edc/v0.0.1/ns/"},
  "@id":"windx-cx-cd-1",
  "accessPolicyId":"windx-membership-policy",
  "contractPolicyId":"windx-membership-policy",
  "assetsSelector":{"@type":"Criterion","operandLeft":"https://w3id.org/edc/v0.0.1/ns/id","operator":"=","operandRight":"windx-cx-asset-1"}
}')
echo "  POST /v3/contractdefinitions -> HTTP $code"; [ "$code" = "409" ] && echo "  (existiert bereits -- ok)"

echo "==== 3b) MMS-Asset (IEC-61850-Daten aus dem iecsim, ns aas) ===="
# Datenpunkte empirisch gegen den iecsim verifiziert (2026-07-20; iecsim quittiert jeden Read
# mit LD/LN/DO/FC im Log): LD=WPPSLD2 traegt die Turbinen-Telemetrie.
# Sensor-Format = roher MMS-ItemId "LN$FC$DO$DA"; das Logical Device geht separat als Domain mit.
# MIT Suffix ($mag$f fuer Messwerte, $stVal fuer Zustaende) liefert iecsim den reinen Skalar
# ("2.213111"); OHNE Suffix die volle Struktur "Wert, Quality-Bits, Zeitstempel". Fuer den
# Demonstrator die sauberen Skalare:
#   WGEN1$MX$W$mag$f       Generator-Wirkleistung   WNAC1$MX$WdSpd$mag$f  Windgeschwindigkeit
#   WROT1$MX$RotSpd$mag$f  Rotordrehzahl            WGBX1$MX$OilTmp$mag$f Getriebeoeltemperatur
#   WTUR1$ST$Running$stVal Betriebszustand
code=$(post "$PROV_MGMT/v3/assets" '{
  "@context":{"@vocab":"https://w3id.org/edc/v0.0.1/ns/"},
  "@id":"windx-mms-asset-1",
  "properties":{"name":"Wind-X Turbinen-Telemetrie (IEC 61850/MMS, iecsim)"},
  "dataAddress":{
    "type":"Mms",
    "mms:serverHost":"iecsim.aas.svc.cluster.local",
    "mms:serverPort":"102",
    "mms:logicalDevice":"WPPSLD2",
    "mms:sensors":"WGEN1$MX$W$mag$f,WNAC1$MX$WdSpd$mag$f,WROT1$MX$RotSpd$mag$f,WGBX1$MX$OilTmp$mag$f,WTUR1$ST$Running$stVal",
    "mms:updateInterval":"1000"
  }
}')
echo "  POST /v3/assets (windx-mms-asset-1) -> HTTP $code"; [ "$code" = "409" ] && echo "  (existiert bereits -- ok)"

echo "==== 3c) ContractDefinition fuer das MMS-Asset (gleiche Membership-Policy) ===="
code=$(post "$PROV_MGMT/v3/contractdefinitions" '{
  "@context":{"@vocab":"https://w3id.org/edc/v0.0.1/ns/"},
  "@id":"windx-cx-cd-mms",
  "accessPolicyId":"windx-membership-policy",
  "contractPolicyId":"windx-membership-policy",
  "assetsSelector":{"@type":"Criterion","operandLeft":"https://w3id.org/edc/v0.0.1/ns/id","operator":"=","operandRight":"windx-mms-asset-1"}
}')
echo "  POST /v3/contractdefinitions -> HTTP $code"; [ "$code" = "409" ] && echo "  (existiert bereits -- ok)"

echo "==== 3d) MMS-Live-Asset (kontinuierlicher Mms-PUSH, edc:isNonFinite) ===="
# Eigenes Asset fuer den Push-Strom: isNonFinite=true schaltet die MMSDataSource in den
# unendlichen Streaming-Modus -- dasselbe Flag am PULL-Asset (3b) wuerde dessen finiten
# Ein-Aggregations-Pfad unendlich machen, deshalb ZWEI Assets. "isNonFinite" expandiert
# ueber @vocab zu edc:isNonFinite (gleiche Property liest der FinitenessEvaluator).
code=$(post "$PROV_MGMT/v3/assets" '{
  "@context":{"@vocab":"https://w3id.org/edc/v0.0.1/ns/"},
  "@id":"windx-mms-live-1",
  "properties":{"name":"Wind-X Turbinen-Telemetrie LIVE (IEC 61850/MMS, kontinuierlicher Push)"},
  "dataAddress":{
    "type":"Mms",
    "isNonFinite":"true",
    "mms:serverHost":"iecsim.aas.svc.cluster.local",
    "mms:serverPort":"102",
    "mms:logicalDevice":"WPPSLD2",
    "mms:sensors":"WGEN1$MX$W$mag$f,WNAC1$MX$WdSpd$mag$f,WROT1$MX$RotSpd$mag$f,WGBX1$MX$OilTmp$mag$f,WTUR1$ST$Running$stVal",
    "mms:updateInterval":"1000"
  }
}')
echo "  POST /v3/assets (windx-mms-live-1) -> HTTP $code"; [ "$code" = "409" ] && echo "  (existiert bereits -- ok)"

code=$(post "$PROV_MGMT/v3/contractdefinitions" '{
  "@context":{"@vocab":"https://w3id.org/edc/v0.0.1/ns/"},
  "@id":"windx-cx-cd-mms-live",
  "accessPolicyId":"windx-membership-policy",
  "contractPolicyId":"windx-membership-policy",
  "assetsSelector":{"@type":"Criterion","operandLeft":"https://w3id.org/edc/v0.0.1/ns/id","operator":"=","operandRight":"windx-mms-live-1"}
}')
echo "  POST /v3/contractdefinitions (windx-cx-cd-mms-live) -> HTTP $code"; [ "$code" = "409" ] && echo "  (existiert bereits -- ok)"

echo "==== 4) Katalog (Consumer -> Provider, echter DCP-Fluss ueber den Wallet-Stub) ===="
code=$(post "$CONS_MGMT/v3/catalog/request" '{
  "@context":{"@vocab":"https://w3id.org/edc/v0.0.1/ns/"},
  "counterPartyAddress":"'"$PROV_DSP"'",
  "counterPartyId":"'"$PROV_DID"'",
  "protocol":"dataspace-protocol-http"
}')
echo "  POST /v3/catalog/request -> HTTP $code"
if [ "$code" = "200" ]; then
    echo "  dataset:  $(grep -o '"@id":"windx-cx-asset-1"' /tmp/o.json | head -1)"
    OFFER=$(sed -n 's/.*"odrl:hasPolicy":{"@id":"\([^"]*\)".*/\1/p' /tmp/o.json | head -1)
    [ -z "$OFFER" ] && OFFER=$(sed -n 's/.*"hasPolicy":\[\{0,1\}{"@id":"\([^"]*\)".*/\1/p' /tmp/o.json | head -1)
    echo "  offerId:  $OFFER"
    echo "$OFFER" > /tmp/offer.txt
else
    echo "  FEHLER:"; head -c 500 /tmp/o.json; echo
fi
