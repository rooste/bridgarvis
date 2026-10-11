#!/usr/bin/env bash
# Publishes site/ to the Bridgarvis host:
#   1. renders the pages (header/footer + placeholders from infra/local.env)
#   2. uploads them to the deploy bucket
#   3. on the host via SSM: issues the Let's Encrypt cert if missing (Route53 DNS-01),
#      installs the nginx TLS config, syncs the files, reloads nginx
#
# local.env needs: BRIDGARVIS_SITE_HOST, SITE_OWNER_NAME, SITE_OWNER_LOCATION, SITE_CONTACT_EMAIL.
# Optional BRIDGARVIS_MAIL_UI_HOST: nginx routes port 443 by SNI. That name is passed through, still
# encrypted, to the Bridgarvis gateway (which checks the browser's client certificate itself); every
# other name is served by nginx on an internal port.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
set -a; source "$ROOT/infra/local.env"; set +a
: "${BRIDGARVIS_SITE_HOST:?}" "${SITE_OWNER_NAME:?}" "${SITE_OWNER_LOCATION:?}" "${SITE_CONTACT_EMAIL:?}"

MAIL_UI_HOST="${BRIDGARVIS_MAIL_UI_HOST:-}"
BUCKET="$(aws cloudformation describe-stacks --stack-name BridgarvisStack \
  --query "Stacks[0].Outputs[?OutputKey=='DeployBucketName'].OutputValue" --output text)"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

python3 - "$ROOT/site" "$OUT" <<'PY'
import datetime, os, pathlib, re, sys
src, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
values = {
    "__OWNER_NAME__": os.environ["SITE_OWNER_NAME"],
    "__OWNER_LOCATION__": os.environ["SITE_OWNER_LOCATION"],
    "__CONTACT_EMAIL__": os.environ["SITE_CONTACT_EMAIL"],
    "__UPDATED__": datetime.date.today().strftime("%B %-d, %Y"),
}
header, footer = (src / "_header.html").read_text(), (src / "_footer.html").read_text()
for f in src.iterdir():
    if f.name.startswith("_"):
        continue
    text = f.read_text().replace("<!--#header-->", header).replace("<!--#footer-->", footer)
    for k, v in values.items():
        text = text.replace(k, v)
    leftover = re.findall(r"__[A-Z_]+__", text)
    if leftover:
        sys.exit(f"{f.name}: unreplaced placeholders {leftover}")
    (out / f.name).write_text(text)
PY

aws s3 sync --delete --only-show-errors "$OUT/" "s3://$BUCKET/site/"
echo "Uploaded site to s3://$BUCKET/site/"

"$ROOT/scripts/host-run.sh" "$(cat <<EOF
set -euo pipefail
HOST='$BRIDGARVIS_SITE_HOST'
if [ ! -f /etc/letsencrypt/live/\$HOST/fullchain.pem ]; then
  certbot certonly --dns-route53 --non-interactive --agree-tos --register-unsafely-without-email -d "\$HOST"
fi
# Port 443 belongs to the SNI router (stream module); HTTPS sites listen internally on 8444
dnf -y -q install nginx-mod-stream >/dev/null
install -d /etc/nginx/stream.d
grep -q 'stream.d' /etc/nginx/nginx.conf || echo 'include /etc/nginx/stream.d/*.conf;' >> /etc/nginx/nginx.conf
MAIL_HOST='$MAIL_UI_HOST'
cat > /etc/nginx/stream.d/sni.conf <<NGINX
stream {
    map \\\$ssl_preread_server_name \\\$tls_backend {
\$( [ -n "\$MAIL_HOST" ] && echo "        \$MAIL_HOST 127.0.0.1:8443;" )
        default 127.0.0.1:8444;
    }
    server {
        listen 443;
        ssl_preread on;
        proxy_pass \\\$tls_backend;
        proxy_connect_timeout 5s;
    }
}
NGINX
rm -f /etc/nginx/conf.d/mail-redirect.conf
if [ -n "\$MAIL_HOST" ]; then
  cat > /etc/nginx/conf.d/mail-ui.conf <<NGINX
server {
    listen 80;
    server_name \$MAIL_HOST;
    server_tokens off;
    return 301 https://\$MAIL_HOST/ui;
}
NGINX
fi
cat > /etc/nginx/conf.d/site.conf <<NGINX
server {
    listen 80;
    server_name \$HOST;
    server_tokens off;
    return 301 https://\\\$host\\\$request_uri;
}
server {
    listen 127.0.0.1:8444 ssl;
    http2 on;
    server_name \$HOST;
    server_tokens off;
    ssl_certificate     /etc/letsencrypt/live/\$HOST/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/\$HOST/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    root /var/www/site;
    index index.html;
    location / { try_files \\\$uri \\\$uri.html \\\$uri/ =404; }
    add_header Strict-Transport-Security "max-age=31536000" always;
    add_header X-Content-Type-Options nosniff always;
    add_header Referrer-Policy no-referrer always;
    add_header Content-Security-Policy "default-src 'none'; style-src 'self'; img-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'" always;
}
NGINX
aws s3 sync --delete --only-show-errors "s3://$BUCKET/site/" /var/www/site/

nginx -t 2>&1 | tail -1
systemctl reload nginx
echo "site published"
EOF
)"
