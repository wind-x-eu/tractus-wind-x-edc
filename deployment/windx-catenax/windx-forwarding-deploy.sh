#!/usr/bin/env bash
# Rollt den MMS-Forwarding-Service (Nachbildung) aus: Code als ConfigMap + Deployment/Service/Ingress.
# Kein Image-Build noetig -- python:3.12-alpine mountet den Code aus der ConfigMap.
#
#   ./windx-forwarding-deploy.sh          ausrollen bzw. Code aktualisieren
#
# Push-Ziel (clusterintern):  http://windx-forwarding:4000/log?signal=<sensor>
# Lesepfad (extern):          http://windx-forwarding.tx.test/log?signal=<sensor>  (Host-Header auf 10.103.45.212)
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1

KCTX="${KCTX:-try1}"
NS="${NS:-windx}"
[ -z "${KUBECONFIG:-}" ] && [ -f "C:/Users/luf/Desktop/wind-x/kubernetes/.kube" ] && \
    export KUBECONFIG="C:/Users/luf/Desktop/wind-x/kubernetes/.kube"
K() { kubectl --context "$KCTX" "$@"; }

echo "==== 1) Code als ConfigMap ===="
# windx_mms_client.py muss mit in die ConfigMap: der Dienst importiert ihn fuer /api/sensors
# (MMS GetNameList). Beide Dateien landen in /app, deshalb greift der Import ohne PYTHONPATH.
K -n "$NS" create configmap windx-forwarding \
    --from-file=forwarding.py=windx-forwarding.py \
    --from-file=windx_mms_client.py=windx_mms_client.py \
    --dry-run=client -o yaml | K -n "$NS" apply -f - | sed 's/^/  /'

echo "==== 2) Deployment + Service + Ingress ===="
K apply -f windx-forwarding.yaml | sed 's/^/  /'

echo "==== 3) Neustart erzwingen (ConfigMap-Aenderungen wirken sonst erst verzoegert) ===="
K -n "$NS" rollout restart deploy/windx-forwarding >/dev/null
K -n "$NS" rollout status deploy/windx-forwarding --timeout=180s | sed 's/^/  /'

echo
echo "Bereit. Kurztest:"
echo "  curl -s -H 'Host: windx-forwarding.tx.test' http://10.103.45.212/health"
