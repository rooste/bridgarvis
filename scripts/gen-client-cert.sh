#!/usr/bin/env bash
# Generates an additional client certificate, e.g. for a browser:
#
#   scripts/gen-client-cert.sh browser-laptop [out-dir]
#
# Produces <name>.pem/.key and <name>.p12 (+ .p12.pass) for import into macOS Keychain or iOS.
# The name becomes the certificate CN; Bridgarvis grants CN "juujarvis" the API and CNs starting with
# "browser-" the web UI. scripts/deploy.sh adds every certs/*.pem client cert to the trusted set.
set -euo pipefail

NAME="${1:?usage: gen-client-cert.sh <name> [out-dir]}"
OUT="${2:-certs}"
DAYS="${DAYS:-825}"
[[ "$NAME" =~ ^[a-z0-9-]+$ ]] || { echo "name: lowercase letters, digits and dashes only" >&2; exit 1; }
mkdir -p "$OUT"
umask 077

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
printf '[req]\ndistinguished_name=dn\n[dn]\n[ext]\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=clientAuth\n' > "$TMP/c.cnf"
openssl ecparam -name prime256v1 -genkey -noout -out "$TMP/k.sec1"
openssl pkcs8 -topk8 -nocrypt -in "$TMP/k.sec1" -out "$OUT/$NAME.key"
openssl req -x509 -new -key "$OUT/$NAME.key" -sha256 -days "$DAYS" -subj "/CN=$NAME" \
    -config "$TMP/c.cnf" -extensions ext -out "$OUT/$NAME.pem"
chmod 644 "$OUT/$NAME.pem"

PASS="$(openssl rand -hex 8)"
printf '%s' "$PASS" > "$OUT/$NAME.p12.pass"
# 3DES/SHA1 packaging: accepted by both macOS Keychain and iOS profile installation
openssl pkcs12 -export -name "$NAME" -inkey "$OUT/$NAME.key" -in "$OUT/$NAME.pem" \
    -certpbe PBE-SHA1-3DES -keypbe PBE-SHA1-3DES -macalg sha1 \
    -out "$OUT/$NAME.p12" -passout "pass:$PASS"
echo "Created $OUT/$NAME.p12 (import password in $OUT/$NAME.p12.pass)"
