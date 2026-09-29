#!/bin/sh
# =============================================================================
# vault-keeper -- macht den In-Memory-Vault (Dev-Modus) dauerhaft.
#
#   Start:  Sicherung vorhanden -> alle Secrets zurueckspielen
#           keine Sicherung     -> nur den Superuser-Key setzen (erster Start)
#   Danach: alle 30 s sichern; ist der Vault zwischenzeitlich leer (Neustart),
#           wird zurueckgespielt statt gesichert.
#
# Sicherung: /backup/vault.tsv, je Zeile "<key><TAB><{"content":...}>".
# Die Keys legt der IdentityHub prozentkodiert an (did%3Aweb%3A...%23signing-key-1);
# in der HTTP-API muss deshalb '%' selbst als %25 kodiert werden.
# =============================================================================
set -u
# Geheimnisse aus dem Volume "secrets" (erzeugt vom Dienst "secrets")
VAULT_TOKEN=$(cat /secrets/vault_token)
SUPERUSER_KEY=$(cat /secrets/superuser_key)
V=http://vault:8200
B=/backup/vault.tsv
SU='sup3r$3cr3t'

api() { curl -s -H "X-Vault-Token: $VAULT_TOKEN" "$@"; }
enc() { printf '%s' "$1" | sed -e 's/%/%25/g' -e 's/\$/%24/g' -e 's/#/%23/g' -e 's/:/%3A/g'; }
keys() { api "$V/v1/secret/metadata?list=true" | sed -n 's/.*"keys":\[\([^]]*\)\].*/\1/p' | tr ',' '\n' | sed 's/^"//;s/"$//' | grep -v '^$'; }
has_su() { [ "$(api -o /dev/null -w '%{http_code}' "$V/v1/secret/data/$(enc "$SU")")" = "200" ]; }
put() { api -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
          "$V/v1/secret/data/$(enc "$1")" -d "{\"data\":$2}"; }

restore() {
    n=0
    while IFS="$(printf '\t')" read -r k j; do
        [ -n "$k" ] || continue
        c=$(put "$k" "$j"); n=$((n+1))
        echo "  restore $k -> $c"
    done < "$B"
    echo "vault-keeper: $n Secrets zurueckgespielt"
}

backup() {
    tmp=$B.tmp; : > "$tmp"
    for k in $(keys); do
        j=$(api "$V/v1/secret/data/$(enc "$k")" | sed -n 's/.*"data":{"data":\(.*\),"metadata":.*/\1/p')
        [ -n "$j" ] && printf '%s\t%s\n' "$k" "$j" >> "$tmp"
    done
    new=$(wc -l < "$tmp"); old=0; [ -f "$B" ] && old=$(wc -l < "$B")
    # nie eine vollere Sicherung durch eine leerere ersetzen
    if [ "$new" -ge "$old" ] && [ "$new" -gt 0 ]; then mv "$tmp" "$B"; else rm -f "$tmp"; fi
}

echo "vault-keeper: warte auf Vault ..."
until [ "$(curl -s -o /dev/null -w '%{http_code}' $V/v1/sys/health)" = "200" ]; do sleep 2; done

if [ -s "$B" ]; then
    restore
else
    c=$(put "$SU" "{\"content\":\"$SUPERUSER_KEY\"}")
    echo "vault-keeper: erster Start, Superuser-Key gesetzt -> $c"
fi
has_su || { echo "FEHLER: Superuser-Key fehlt nach Initialisierung"; exit 1; }
touch /tmp/ready
echo "vault-keeper: bereit"

while sleep 30; do
    until [ "$(curl -s -o /dev/null -w '%{http_code}' $V/v1/sys/health)" = "200" ]; do sleep 2; done
    if has_su; then backup; else echo "vault-keeper: Vault leer (Neustart?) -> Rueckspielen"; restore; fi
done
