#!/usr/bin/env bash
# Beendet den Onboarding-Test: Tunnel und Stack stoppen, Test-Identitaet
# (Volumes) loeschen. Den Participant beim SWMS-Operator bitte zusaetzlich
# im Admin-UI entfernen -- sein DID-Dokument ist ab jetzt nicht mehr erreichbar.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose -p edctunnel --env-file tunnel-test/.env.tunnel \
  -f docker-compose.yml -f tunnel-test/docker-compose.tunnel.yml down -v --remove-orphans
echo "Test beendet. Participant im SWMS-Operator-UI entfernen."
