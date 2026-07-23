#!/usr/bin/env bash
# Host-Launcher fuer den Wind-X Demonstrator (Git Bash / WSL auf dem Windows-Host).
#
# Startet windx-demo.sh in einem curl-Pod im Namespace 'windx'. Der Demo-Ablauf MUSS
# cluster-intern laufen (der Stack hat kein Ingress -- DIDs/Services sind nur cluster-intern
# aufloesbar). Der Launcher macht Preflight-Checks und reicht die Steuer-Env durch.
#
# Aufruf:
#   deployment/windx-kind/run-demo.sh              # volle Demo (Story + Technik)
#   STORY_ONLY=1 deployment/windx-kind/run-demo.sh # nur die Story-Ebene (fuer Nicht-Techniker)
#   PAUSE=4 deployment/windx-kind/run-demo.sh       # laengere Pausen zum Mitmoderieren
set -euo pipefail

CTX="${CTX:-kind-mvd}"
NS="${NS:-windx}"
: "${KUBECONFIG:=C:/Users/luf/Desktop/wind-x/kubernetes/.kube}"
export KUBECONFIG
DIR="$(cd "$(dirname "$0")" && pwd)"
K="kubectl --context $CTX -n $NS"

# --- Preflight ---------------------------------------------------------------
if ! $K get deploy windx-provider windx-consumer windx-identityhub >/dev/null 2>&1; then
  echo "FEHLER: windx-Stack nicht gefunden (Kontext $CTX, Namespace $NS)."
  echo "        Erst 'kubectl apply -f $DIR/windx-stack.yaml' (siehe README)."
  exit 1
fi
$K get secret windx-sts-secrets        >/dev/null 2>&1 || \
  echo "WARN: Secret 'windx-sts-secrets' fehlt -> Negotiation scheitert (README Schritt 3)."
$K get secret windx-provider-vault-seed >/dev/null 2>&1 || \
  echo "WARN: Secret 'windx-provider-vault-seed' fehlt -> Akt 4 (Datentransfer) scheitert (README Schritt 4-6)."

# --- Start -------------------------------------------------------------------
$K delete pod windx-demo --ignore-not-found >/dev/null 2>&1 || true
B64="$(base64 -w0 "$DIR/windx-demo.sh")"

# Steuer-Env nur setzen, wenn vom Aufrufer vorgegeben (sonst greifen die Defaults im Skript).
ENVFLAGS=()
[ -n "${STORY_ONLY:-}" ] && ENVFLAGS+=(--env="STORY_ONLY=$STORY_ONLY")
[ -n "${PAUSE:-}" ]      && ENVFLAGS+=(--env="PAUSE=$PAUSE")
[ -n "${NO_COLOR:-}" ]   && ENVFLAGS+=(--env="NO_COLOR=$NO_COLOR")

$K run windx-demo --rm -i --restart=Never \
  --image=curlimages/curl:8.10.1 \
  "${ENVFLAGS[@]}" \
  --command -- sh -c "echo $B64 | base64 -d | sh"
