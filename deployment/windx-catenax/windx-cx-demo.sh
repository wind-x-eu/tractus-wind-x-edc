#!/usr/bin/env bash
# Demo des MMS-Use-Case (Phase 5): Turbinen-Telemetrie aus dem iecsim, gezogen ueber den
# Tractus-X-EDC -- Katalog -> Vertrag (Membership) -> Mms-PULL -> Live-JSON, 401-Gegenprobe.
#
# Fuenf Akte mit Pausen (Enter). Die eigentlichen API-Aufrufe laufen in einem Demo-Pod im
# Cluster (windx-cx-demo.py), weil die Management-APIs nur clusterintern erreichbar sind.
#
#   ./windx-cx-demo.sh          Vorfuehrung mit Pausen
#   ./windx-cx-demo.sh --auto   ohne Pausen (Selbsttest/CI)
#
# Voraussetzungen: windx-cx-seed.sh ist gelaufen (Asset + Policy), beide Connectoren laufen.
set -u
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1   # Git-Bash: /tmp-Argumente nicht zu C:/... mangeln

KCTX="${KCTX:-try1}"
NS="${NS:-windx}"
POD="windx-demo"
AUTO=0
[ "${1:-}" = "--auto" ] && AUTO=1
[ -z "${KUBECONFIG:-}" ] && [ -f "C:/Users/luf/Desktop/wind-x/kubernetes/.kube" ] && \
    export KUBECONFIG="C:/Users/luf/Desktop/wind-x/kubernetes/.kube"

K() { kubectl --context "$KCTX" "$@"; }

C_TITLE='\033[1;36m'; C_DIM='\033[2m'; C_OK='\033[1;32m'; C_OFF='\033[0m'
akt()   { printf "\n${C_TITLE}==== AKT %s: %s ====${C_OFF}\n" "$1" "$2"; }
erz()   { printf "${C_DIM}%s${C_OFF}\n" "$1"; }
pause() { [ "$AUTO" = "1" ] && return; printf "\n${C_DIM}[Enter fuer den naechsten Schritt]${C_OFF}"; read -r _; }

# ---------------------------------------------------------------- Vorbereitung
printf "${C_TITLE}Wind-X Demonstrator -- IEC-61850-Telemetrie ueber den Tractus-X-EDC${C_OFF}\n"
erz "Cluster: $KCTX / Namespace: $NS"

if ! K -n "$NS" get pod "$POD" >/dev/null 2>&1; then
    erz "Starte Demo-Pod ..."
    K -n "$NS" run "$POD" --image=python:3.12-alpine --restart=Never --command -- sleep 14400 >/dev/null
fi
K -n "$NS" wait --for=condition=Ready "pod/$POD" --timeout=180s >/dev/null || { echo "Demo-Pod nicht bereit"; exit 1; }
K -n "$NS" cp windx-cx-demo.py "$POD:/tmp/demo.py" >/dev/null
run() { K -n "$NS" exec "$POD" -- python3 /tmp/demo.py "$1"; }

# iecsimweb lokal verfuegbar machen (Akt 1) -- nicht fatal, wenn Port belegt
PF_PID=""
if ! { exec 3<>/dev/tcp/127.0.0.1/8080; } 2>/dev/null; then
    K -n aas port-forward svc/iecsimweb 8080:80 >/dev/null 2>&1 &
    PF_PID=$!
else
    exec 3>&- 3<&-
fi
trap '[ -n "$PF_PID" ] && kill "$PF_PID" 2>/dev/null' EXIT

# ---------------------------------------------------------------- Akt 1
akt 1 "DIE ANLAGE"
erz "Der iecsim simuliert eine Windenergieanlage nach IEC 61850: MMS-Protokoll auf TCP 102,"
erz "kein HTTP, keine partnerbezogene Autorisierung -- so sieht Anlagen-IT wirklich aus."
printf "  Live-Ansicht im Browser oeffnen:  ${C_OK}http://localhost:8080${C_OFF}\n"
erz "  (Windgeschwindigkeit, Leistung, Status -- bitte offen lassen fuer Akt 4)"
pause

# ---------------------------------------------------------------- Akt 2
akt 2 "DAS ANGEBOT"
erz "Der Consumer fragt den Katalog des Providers ab. Schon dieser Aufruf laeuft ueber den"
erz "vollen DCP-Handschlag: DID-Aufloesung, Credential-Praesentation, Token vom Wallet."
run katalog || exit 1
erz "-> Die Anlage erscheint als Vertragsangebot mit Policy -- nicht als offener Port."
pause

# ---------------------------------------------------------------- Akt 3
akt 3 "DER VERTRAG"
erz "Der Consumer verhandelt das Angebot. Der Provider prueft dabei das MembershipCredential"
erz "aus dem Wallet des Consumers -- das ist die 'echte Authentifizierung' des Auftrags."
run vertrag || exit 1
erz "-> FINALIZED: rechtsfoermige Grundlage fuer den Datenzugriff."
pause

# ---------------------------------------------------------------- Akt 4
akt 4 "DIE DATEN"
erz "Transfer 'Mms-PULL': die Provider-Data-Plane verbindet sich zum iecsim (Port 102),"
erz "pollt die Sensoren und uebersetzt IEC 61850 -> JSON. Der Consumer sieht nur HTTP."
run daten || exit 1
printf "\n"
erz "Beweis im iecsim-Log (jeder MMS-Read wird mit Client-IP quittiert):"
DPIP=$(K -n "$NS" get pod -o wide 2>/dev/null | awk '/provider-tractusx-connector-dataplane/ {print $6}')
# gezielt nach der Data-Plane-IP filtern -- die iecsimweb-UI pollt permanent und wuerde das Log fluten
K -n aas logs deploy/iecsim --since=2m 2>/dev/null | grep -a "client=${DPIP:-<none>}:" | tail -3 | sed 's/^/    /'
printf "  Data-Plane-Pod-IP: ${C_OK}%s${C_OFF} -- die Reads kommen vom EDC, nicht von der Web-UI.\n" "${DPIP:-?}"
erz "-> Werte mit der Browser-Ansicht (Akt 1) vergleichen: dieselbe Anlage, dieselben Zahlen."
pause

# ---------------------------------------------------------------- Akt 5
akt 5 "DAS SCHLOSS"
erz "Gegenprobe: derselbe Endpunkt ohne Token. Und danach ein zweiter Abruf mit Token --"
erz "der neue Werte liefert, denn das ist eine lebende Anlage, kein aufgezeichnetes Demo."
run schloss || exit 1

# ---------------------------------------------------------------- Fazit
printf "\n${C_OK}==== DEMO ABGESCHLOSSEN ====${C_OFF}\n"
erz "Gezeigt: Sichtbarkeit nur im Dataspace-Katalog | Zugriff nur mit Membership-Credential |"
erz "Protokolluebersetzung IEC 61850 -> JSON | Token-Schutz (401) | Live-Daten."
erz "Aufraeumen (optional): kubectl --context $KCTX -n $NS delete pod $POD"
