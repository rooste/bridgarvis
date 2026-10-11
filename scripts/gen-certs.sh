#!/usr/bin/env bash
# Generates the TLS material for Bridgarvis mutual TLS.
#
#   scripts/gen-certs.sh [out-dir]        (default: certs/)
#
# Produces:
#   server.pem / server.key          self-signed server cert for localhost (dev only;
#                                    production uses the instance's Let's Encrypt cert)
#   juujarvis.pem / juujarvis.key    the client identity. Bridgarvis trusts exactly this
#                                    certificate (pinned) and rejects every other client.
#
# Rotation: generate a new juujarvis cert, append it to Bridgarvis's trusted client PEM,
# switch Juujarvis to it, then remove the old one.
set -euo pipefail

OUT="${1:-certs}"
DAYS="${DAYS:-825}"
mkdir -p "$OUT"
umask 077

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Writes an EC P-256 key (PKCS#8) and a self-signed cert with the given extensions.
make_cert() {
    local name="$1" cn="$2" ext="$3"
    printf '[req]\ndistinguished_name=dn\n[dn]\n[ext]\n%b\n' "$ext" > "$TMP/$name.cnf"
    openssl ecparam -name prime256v1 -genkey -noout -out "$TMP/$name.sec1"
    openssl pkcs8 -topk8 -nocrypt -in "$TMP/$name.sec1" -out "$OUT/$name.key"
    openssl req -x509 -new -key "$OUT/$name.key" -sha256 -days "$DAYS" \
        -subj "/CN=$cn" -config "$TMP/$name.cnf" -extensions ext -out "$OUT/$name.pem"
}

make_cert server localhost \
    'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost,IP:127.0.0.1'

make_cert juujarvis juujarvis \
    'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=clientAuth'

chmod 644 "$OUT"/*.pem
echo "Certificates written to $OUT/"
openssl x509 -in "$OUT/juujarvis.pem" -noout -subject -enddate -fingerprint -sha256
