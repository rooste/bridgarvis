#!/usr/bin/env bash
# Builds Bridgarvis and deploys it to the host (S3 + SSM, no SSH).
#
#   scripts/deploy.sh
#
# On the host: ensures the gateway TLS certificate (Let's Encrypt, Route53 DNS-01) and the pinned
# Juujarvis client certificate are in place, installs the release, restarts the service and checks
# health on 127.0.0.1:8081. If the health check fails, the previous release is restored.
#
# local.env needs: BRIDGARVIS_HOST, BRIDGARVIS_JUUJARVIS_ADDRESS. Trusted client certificates come from
# certs/juujarvis.pem and certs/browser-*.pem. AWS resource names come from the stack outputs.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
set -a; source "$ROOT/infra/local.env"; set +a
: "${BRIDGARVIS_HOST:?}" "${BRIDGARVIS_JUUJARVIS_ADDRESS:?}"
MAIL_UI_HOST="${BRIDGARVIS_MAIL_UI_HOST:-}"
NOTIFY_ADDRESSES="${BRIDGARVIS_NOTIFY_ADDRESSES:-}"
[ -f "$ROOT/certs/juujarvis.pem" ] || { echo "Missing certs/juujarvis.pem (scripts/gen-certs.sh)" >&2; exit 1; }
# Trusted clients: Juujarvis plus every browser certificate (scripts/gen-client-cert.sh)
CLIENTS="$(mktemp)"
trap 'rm -f "$CLIENTS"' EXIT
cat "$ROOT/certs/juujarvis.pem" "$ROOT"/certs/browser-*.pem > "$CLIENTS" 2>/dev/null || cat "$ROOT/certs/juujarvis.pem" > "$CLIENTS"
echo "Trusted client certificates: $(grep -c 'BEGIN CERTIFICATE' "$CLIENTS")"

output() {
  aws cloudformation describe-stacks --stack-name BridgarvisStack \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text
}
BUCKET="$(output DeployBucketName)"
TABLE="$(output MessagesTable)"
MAIL_BUCKET="$(output MailBucketName)"
MAIL_QUEUE="$(output MailParseQueueUrl)"
EVENTS_QUEUE="$(output MailEventsQueueUrl)"
CONFIG_SET="$(output MailConfigurationSet)"
: "${BRIDGARVIS_MAIL_FROM_ADDRESS:?}"

(cd "$ROOT" && ./gradlew -q bootJar)
JAR="$(ls "$ROOT"/build/libs/bridgarvis-*.jar | grep -v plain | head -1)"
REL="$(date -u +%Y%m%d-%H%M%S)-$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo local)"

aws s3 cp --only-show-errors "$JAR" "s3://$BUCKET/releases/$REL/bridgarvis.jar"
aws s3 cp --only-show-errors "$CLIENTS" "s3://$BUCKET/config/clients.pem"
echo "Uploaded release $REL"

"$ROOT/scripts/host-run.sh" "$(cat <<EOF
set -euo pipefail
HOST='$BRIDGARVIS_HOST'
BUCKET='$BUCKET'
REL='$REL'

# Gateway certificate; the deploy hook copies it where the service user can read it
cat > /etc/letsencrypt/renewal-hooks/deploy/bridgarvis-tls.sh <<'HOOK'
#!/bin/bash
set -e
HOST_FILE=/etc/bridgarvis/tls/host
[ -f "\$HOST_FILE" ] || exit 0
LIVE=/etc/letsencrypt/live/\$(cat "\$HOST_FILE")
install -m 640 -o root -g bridgarvis "\$LIVE/fullchain.pem" /etc/bridgarvis/tls/fullchain.pem
install -m 640 -o root -g bridgarvis "\$LIVE/privkey.pem" /etc/bridgarvis/tls/privkey.pem
HOOK
chmod 755 /etc/letsencrypt/renewal-hooks/deploy/bridgarvis-tls.sh
echo "\$HOST" > /etc/bridgarvis/tls/host
# One certificate for the gateway name and (optionally) the mail UI name, which nginx passes through to us
MAIL_HOST='$MAIL_UI_HOST'
NAMES="-d \$HOST"; [ -n "\$MAIL_HOST" ] && NAMES="\$NAMES -d \$MAIL_HOST"
LIVE="/etc/letsencrypt/live/\$HOST/cert.pem"
if [ ! -f "\$LIVE" ] || { [ -n "\$MAIL_HOST" ] && ! openssl x509 -in "\$LIVE" -noout -ext subjectAltName | grep -q "DNS:\$MAIL_HOST"; }; then
  certbot certonly --dns-route53 --non-interactive --agree-tos --register-unsafely-without-email \
    --cert-name "\$HOST" --expand \$NAMES 2>&1 | tail -2
fi
/etc/letsencrypt/renewal-hooks/deploy/bridgarvis-tls.sh

# Pinned client certificates (public parts only)
aws s3 cp --only-show-errors "s3://\$BUCKET/config/clients.pem" /etc/bridgarvis/tls/clients.pem
chown root:bridgarvis /etc/bridgarvis/tls/clients.pem
chmod 640 /etc/bridgarvis/tls/clients.pem

# AWS settings for the service (resource names from the stack; credentials come from the instance role)
cat > /etc/bridgarvis/aws.env <<ENV
AWS_REGION=$AWS_REGION
BRIDGARVIS_CLIENT_CERT=file:/etc/bridgarvis/tls/clients.pem
BRIDGARVIS_STORE=dynamo
BRIDGARVIS_MESSAGES_TABLE=$TABLE
BRIDGARVIS_MAIL_INGEST=true
BRIDGARVIS_MAIL_BUCKET=$MAIL_BUCKET
BRIDGARVIS_MAIL_QUEUE_URL=$MAIL_QUEUE
BRIDGARVIS_JUUJARVIS_ADDRESS=$BRIDGARVIS_JUUJARVIS_ADDRESS
BRIDGARVIS_NOTIFY_ADDRESSES=$NOTIFY_ADDRESSES
BRIDGARVIS_SCHOOL_ADDRESSES=${BRIDGARVIS_SCHOOL_ADDRESSES:-}
BRIDGARVIS_SCHOOL_SENDER_DOMAINS=${BRIDGARVIS_SCHOOL_SENDER_DOMAINS:-}
BRIDGARVIS_MAIL_FROM_ADDRESS=$BRIDGARVIS_MAIL_FROM_ADDRESS
BRIDGARVIS_MAIL_CONFIGURATION_SET=$CONFIG_SET
BRIDGARVIS_MAIL_EVENTS_QUEUE_URL=$EVENTS_QUEUE
ENV
chown root:bridgarvis /etc/bridgarvis/aws.env
chmod 640 /etc/bridgarvis/aws.env
install -d /etc/systemd/system/bridgarvis.service.d
printf '[Service]\nEnvironmentFile=/etc/bridgarvis/aws.env\n' > /etc/systemd/system/bridgarvis.service.d/aws.conf
systemctl daemon-reload

# Install the release
install -d -o bridgarvis -g bridgarvis "/opt/bridgarvis/releases/\$REL"
aws s3 cp --only-show-errors "s3://\$BUCKET/releases/\$REL/bridgarvis.jar" "/opt/bridgarvis/releases/\$REL/bridgarvis.jar"
PREV="\$(readlink /opt/bridgarvis/current || true)"
ln -sfn "/opt/bridgarvis/releases/\$REL" /opt/bridgarvis/current
systemctl enable --quiet bridgarvis
systemctl restart bridgarvis

healthy() {
  for i in \$(seq 1 60); do
    curl -sf http://127.0.0.1:8081/actuator/health | grep -q '"UP"' && return 0
    sleep 2
  done
  return 1
}
if healthy; then
  echo "release \$REL healthy"
  ls -1dt /opt/bridgarvis/releases/* | tail -n +6 | xargs -r rm -rf
else
  echo "release \$REL FAILED health check" >&2
  journalctl -u bridgarvis -n 30 --no-pager >&2 || true
  if [ -n "\$PREV" ]; then
    ln -sfn "\$PREV" /opt/bridgarvis/current
    systemctl restart bridgarvis
    healthy && echo "rolled back to \$PREV" >&2
  fi
  exit 1
fi
EOF
)"
