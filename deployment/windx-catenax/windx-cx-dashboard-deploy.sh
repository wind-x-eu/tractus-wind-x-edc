#!/usr/bin/env bash
# Rollt den Wind-X-Demonstrator aus: Code als ConfigMap + Deployment/Service.
# Kein Image-Build noetig -- python:3.12-alpine mountet den Code aus der ConfigMap.
#
#   ./windx-cx-dashboard-deploy.sh            ausrollen bzw. Code aktualisieren
#   ./windx-cx-dashboard-deploy.sh --port      danach gleich port-forward starten
#
# Zugriff: kubectl -n windx port-forward svc/windx-dashboard 8090:80 -> http://localhost:8090
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1

KCTX="${KCTX:-try1}"
NS="${NS:-windx}"
[ -z "${KUBECONFIG:-}" ] && [ -f "C:/Users/luf/Desktop/wind-x/kubernetes/.kube" ] && \
    export KUBECONFIG="C:/Users/luf/Desktop/wind-x/kubernetes/.kube"
K() { kubectl --context "$KCTX" "$@"; }

echo "==== 1) Code als ConfigMap ===="
# --dry-run|apply statt create: so ist der Aufruf wiederholbar (Code-Update ohne Loeschen).
K -n "$NS" create configmap windx-dashboard \
    --from-file=dashboard.py=windx-cx-dashboard.py \
    --from-file=dashboard.html=windx-cx-dashboard.html \
    --dry-run=client -o yaml | K -n "$NS" apply -f - | sed 's/^/  /'

echo "==== 2) Deployment + Service ===="
K apply -f windx-cx-dashboard.yaml | sed 's/^/  /'

echo "==== 3) Neustart erzwingen (ConfigMap-Aenderungen wirken sonst erst verzoegert) ===="
K -n "$NS" rollout restart deploy/windx-dashboard >/dev/null
K -n "$NS" rollout status deploy/windx-dashboard --timeout=180s | sed 's/^/  /'

echo
echo "Bereit. Zugriff:"
echo "  kubectl --context $KCTX -n $NS port-forward svc/windx-dashboard 8090:80"
echo "  -> http://localhost:8090"

if [ "${1:-}" = "--port" ]; then
    echo
    echo "Starte port-forward (Abbruch mit Strg+C) ..."
    K -n "$NS" port-forward svc/windx-dashboard 8090:80
fi
