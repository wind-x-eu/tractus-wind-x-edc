#!/bin/sh
# =============================================================================
# Startet IdentityHub, Control-Plane oder Data-Plane (ROLE) mit den Geheimnissen
# aus /secrets. Sie landen in einer EDC-Konfigurationsdatei (edc.fs.config) statt
# in Umgebungsvariablen -- so muss niemand sie in .env eintragen.
# Der java-Aufruf entspricht exakt dem ENTRYPOINT des jeweiligen Images; die
# Argumente aus "command" (z.B. --log-level) werden durchgereicht.
# =============================================================================
set -eu
S=/secrets
VT=$(cat "$S/vault_token")
PW=$(cat "$S/postgres_password")
P=/tmp/secrets.properties
umask 077
{
    echo "edc.vault.hashicorp.token=$VT"
    case "$ROLE" in
        identityhub)
            SU=$(cat "$S/superuser_key")
            echo "edc.ih.api.superuser.key=$SU"
            for s in participantcontext keypair didresource stsclient credentialrequest \
                     credentials jti participantcontextconfig; do
                echo "edc.datasource.$s.password=$PW"
            done
            cat /config/identityhub.properties ;;
        controlplane)
            MK=$(cat "$S/mgmt_key")
            echo "web.http.management.auth.key=$MK"
            echo "web.http.catalog.auth.key=$MK"
            echo "edc.datasource.default.password=$PW" ;;
        dataplane)
            MK=$(cat "$S/mgmt_key")
            echo "tx.edc.dpf.consumer.proxy.auth.apikey=$MK"
            echo "edc.datasource.default.password=$PW" ;;
        *) echo "start-java: unbekannte ROLE '$ROLE'" >&2; exit 1 ;;
    esac
} > "$P"

cd /app
case "$ROLE" in
    identityhub)
        exec java -Djava.util.logging.config.file=/app/logging.properties \
             -Dedc.fs.config="$P" -Djava.security.egd=file:/dev/urandom \
             -jar identityhub.jar "$@" ;;
    *)
        exec java -javaagent:/app/opentelemetry-javaagent.jar \
             -Dotel.javaagent.configuration-file=/app/opentelemetry.properties \
             -Dedc.fs.config="$P" -Djava.security.egd=file:/dev/urandom \
             -jar edc-runtime.jar "$@" ;;
esac
