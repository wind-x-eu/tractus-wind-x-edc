#!/bin/sh
set -eu

S=/secrets
VT=$(cat "$S/vault_token")
SU=$(cat "$S/superuser_key")
PW=$(cat "$S/postgres_password")
P=/tmp/secrets.properties
umask 077

{
    echo "edc.vault.hashicorp.token=$VT"
    case "$ROLE" in
        identityhub)
            echo "edc.ih.api.superuser.key=$SU"
            for s in participantcontext keypair didresource stsclient credentialrequest \
                     credentials jti participantcontextconfig; do
                echo "edc.datasource.$s.password=$PW"
            done
            cat /config/identityhub.properties
            ;;
        *) echo "start-java: unknown ROLE '$ROLE'" >&2; exit 1 ;;
    esac
} > "$P"

cd /app
exec java -Djava.util.logging.config.file=/app/logging.properties \
     -Dedc.fs.config="$P" -Djava.security.egd=file:/dev/urandom \
     -jar identityhub.jar
