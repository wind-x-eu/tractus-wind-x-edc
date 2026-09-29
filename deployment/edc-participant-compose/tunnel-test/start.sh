#!/usr/bin/env bash
# =============================================================================
# Onboarding-Test vom eigenen Rechner -- Schritte 1 bis 3:
#
#   1  Cloudflare Quick Tunnel starten, oeffentlichen Namen ermitteln
#   2  Stack mit diesem Namen als PUBLIC_HOST starten -> Identitaet
#      did:web:<zufall>.trycloudflare.com wird angelegt
#   3  DID-Dokument ueber den oeffentlichen Namen pruefen und die Werte fuer das
#      SWMS-Operator-UI ausgeben
#
#   ./start.sh <BPN>          z.B.  ./start.sh BPNL00000000LT01
#
# JEDER Lauf erzeugt einen neuen Tunnelnamen und damit eine NEUE Identitaet:
# die Test-Volumes werden dabei geloescht. Beim SWMS-Operator muss der Eintrag
# danach neu angelegt werden. Ende des Tests: ./stop.sh
#
# Voraussetzungen: Docker (Desktop), Zugriff auf ghcr.io/wind-x-eu (docker login),
# bash (Linux/macOS/Git Bash), curl, openssl.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"

BPN="${1:-}"
[ -n "$BPN" ] || { echo "Aufruf: $0 <BPN>   (z.B. BPNL00000000LT01)" >&2; exit 2; }

E=.env.tunnel
C=(docker compose -p edctunnel --env-file "tunnel-test/$E" -f docker-compose.yml -f tunnel-test/docker-compose.tunnel.yml)
cc() { (cd .. && "${C[@]}" "$@"); }
say() { printf '\n==== %s ====\n' "$*"; }

# ---------------------------------------------------------------- Vorbereitung
if [ ! -f "$E" ]; then
    sed -e "s|^BPN=.*|BPN=$BPN|" \
        -e "s|^SITE_ADDRESS=.*|SITE_ADDRESS=:80|" \
        -e "s|^HTTP_PORT=.*|HTTP_PORT=8089|" \
        -e "s|^HTTPS_PORT=.*|HTTPS_PORT=8444|" \
        -e "s|^MGMT_BIND=.*|MGMT_BIND=127.0.0.1:18182|" \
        ../.env.example > "$E"
    echo "Neue Testkonfiguration: tunnel-test/$E (Geheimnisse erzeugt der Dienst \"secrets\")"
else
    sed -i "s|^BPN=.*|BPN=$BPN|" "$E"
fi
sed -i -e "s|^OPERATOR_DID=.*|OPERATOR_DID=did:web:auth-windx.cluster.swms-cloud.com|" \
       -e "s|^BDRS_URL=.*|BDRS_URL=https://auth-windx.cluster.swms-cloud.com/api/directory|" "$E"
OP_UI="https://auth-windx.cluster.swms-cloud.com (SWMS)"
echo "Operator: $(grep '^OPERATOR_DID=' "$E" | cut -d= -f2)"

say "0) Alten Testlauf entfernen (neuer Tunnelname = neue Identitaet)"
cc down -v --remove-orphans >/dev/null 2>&1 || true
echo "  erledigt"

# ---------------------------------------------------------------- Schritt 1
say "1) Tunnel starten"
sed -i "s|^PUBLIC_HOST=.*|PUBLIC_HOST=pending.invalid|" "$E"
cc up -d tunnel >/dev/null
HOST=""
for i in $(seq 1 30); do
    HOST=$(cc logs tunnel 2>/dev/null | grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' | head -1 | sed 's|https://||' || true)
    [ -n "$HOST" ] && break
    sleep 2
done
[ -n "$HOST" ] || { echo "FEHLER: kein Tunnelname -- 'docker compose -p edctunnel logs tunnel' pruefen" >&2; exit 1; }
echo "  oeffentlicher Name: https://$HOST"

# ---------------------------------------------------------------- Schritt 2
say "2) Stack mit PUBLIC_HOST=$HOST starten"
sed -i "s|^PUBLIC_HOST=.*|PUBLIC_HOST=$HOST|" "$E"
cc up -d >/dev/null
echo "  warte auf Identitaet (ih-provision) ..."
for i in $(seq 1 60); do
    st=$(cc ps -a --format '{{.Service}} {{.State}} {{.ExitCode}}' 2>/dev/null | awk '$1=="ih-provision"{print $2" "$3}')
    case "$st" in "exited 0") break ;; "exited "*) cc logs ih-provision; exit 1 ;; esac
    sleep 3
done
cc logs ih-provision 2>/dev/null | grep 'ih-provision:' | tail -1 | sed 's/^/  /' || true
echo "  warte auf Connector ..."
for i in $(seq 1 60); do
    cc logs controlplane 2>/dev/null | grep -q 'Runtime controlplane ready' && break; sleep 3
done
cc logs controlplane 2>/dev/null | grep -q 'Runtime controlplane ready' && echo "  Control-Plane ready" || echo "  WARNUNG: Control-Plane noch nicht ready"

# ---------------------------------------------------------------- Schritt 3
say "3) DID-Dokument ueber den oeffentlichen Namen pruefen"
DID="did:web:$HOST"
ok=0
for i in $(seq 1 30); do
    body=$(curl -s -m 10 "https://$HOST/.well-known/did.json" || true)
    if printf '%s' "$body" | grep -q "\"id\":\"$DID\""; then ok=1; break; fi
    sleep 3        # der neue Tunnelname braucht einige Sekunden bis er weltweit aufloest
done
if [ "$ok" = 1 ]; then
    echo "  https://$HOST/.well-known/did.json -> 200, id = $DID"
    printf '%s' "$body" | grep -o '"serviceEndpoint":"[^"]*"' | sed 's/^/  /'
else
    echo "  FEHLER: DID-Dokument nicht oeffentlich abrufbar. Letzte Antwort:" >&2
    printf '%s\n' "${body:0:300}" >&2; exit 1
fi

B64=$(printf '%s' "$DID" | base64 | tr -d '\n')
. "./$E"
cat <<EOF

==== Werte fuer das Operator-UI $OP_UI (Participants -> New) ====
  Bpn                  : $BPN
  Did                  : $DID
  CredentialServiceUrl : https://$HOST/api/credentials/v1/participants/$B64

  Danach ausstellen: MembershipCredential, BpnCredential, DataExchangeGovernanceCredential
  Kontrolle:  (cd .. && ${C[*]} logs identityhub | grep HolderCredentialRequest)

==== Zugriff ====
  oeffentlich     : https://$HOST   (nur die vier Datenraum-Pfade)
  Management-API  : http://${MGMT_BIND}/management   X-Api-Key: $(cc exec -T controlplane sh -c 'cat /secrets/mgmt_key' 2>/dev/null)
  Ende des Tests  : ./stop.sh   (und Participant bei SWMS entfernen)
EOF
