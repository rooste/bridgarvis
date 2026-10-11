#!/bin/bash
# First-boot setup for the Bridgarvis host (Amazon Linux 2023, arm64). Runs once as root via user data.
set -euxo pipefail

dnf -y upgrade --refresh
dnf -y install java-21-amazon-corretto-headless amazon-cloudwatch-agent python3.12 dnf-automatic nginx

# Automatic security updates (the OS end-of-life problem should not come back as a project)
sed -i 's/^upgrade_type.*/upgrade_type = security/; s/^apply_updates.*/apply_updates = yes/' /etc/dnf/automatic.conf
systemctl enable --now dnf-automatic.timer

# 1 GB swap: the t4g.small has 2 GB RAM and the JVM heap is capped at 256 MB
if [ ! -f /swapfile ]; then
  dd if=/dev/zero of=/swapfile bs=1M count=1024
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap defaults 0 0' >> /etc/fstab
fi

# certbot with Route53 DNS validation (not packaged for AL2023; installed in a venv)
python3.12 -m venv /opt/certbot
/opt/certbot/bin/pip install --upgrade pip certbot certbot-dns-route53
ln -sf /opt/certbot/bin/certbot /usr/local/bin/certbot

# Twice-daily renewal; nginx reloads only when a certificate actually changed
mkdir -p /etc/letsencrypt/renewal-hooks/deploy
cat > /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh <<'HOOK'
#!/bin/bash
systemctl reload nginx
HOOK
chmod 755 /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
cat > /etc/systemd/system/certbot-renew.service <<'UNIT'
[Unit]
Description=Renew Let's Encrypt certificates
[Service]
Type=oneshot
ExecStart=/usr/local/bin/certbot renew --quiet
UNIT
cat > /etc/systemd/system/certbot-renew.timer <<'UNIT'
[Unit]
Description=Renew Let's Encrypt certificates twice a day
[Timer]
OnCalendar=*-*-* 03,15:17:00
RandomizedDelaySec=1h
Persistent=true
[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now certbot-renew.timer

# nginx: until certificates exist, port 80 only answers with a placeholder. TLS site configs are
# installed by scripts/deploy-site.sh after certbot has issued the certificate.
mkdir -p /var/www/site
rm -f /etc/nginx/conf.d/default.conf
cat > /etc/nginx/conf.d/00-default.conf <<'NGINX'
server {
    listen 80 default_server;
    server_name _;
    server_tokens off;
    location / { return 404; }
}
NGINX
systemctl enable --now nginx

# Service user and layout. Releases are unpacked by the deploy script; 'current' is a symlink.
useradd --system --home-dir /opt/bridgarvis --shell /sbin/nologin bridgarvis || true
install -d -o bridgarvis -g bridgarvis /opt/bridgarvis /opt/bridgarvis/releases /var/log/bridgarvis
install -d -m 750 -o root -g bridgarvis /etc/bridgarvis /etc/bridgarvis/tls

cat > /etc/bridgarvis/bridgarvis.env <<'ENV'
LOGGING_FILE_NAME=/var/log/bridgarvis/bridgarvis.log
BRIDGARVIS_TLS_CERT=file:/etc/bridgarvis/tls/fullchain.pem
BRIDGARVIS_TLS_KEY=file:/etc/bridgarvis/tls/privkey.pem
BRIDGARVIS_TLS_RELOAD=true
BRIDGARVIS_CLIENT_CERT=file:/etc/bridgarvis/tls/juujarvis.pem
ENV
chmod 640 /etc/bridgarvis/bridgarvis.env
chown root:bridgarvis /etc/bridgarvis/bridgarvis.env

cat > /etc/systemd/system/bridgarvis.service <<'UNIT'
[Unit]
Description=Bridgarvis gateway
After=network-online.target
Wants=network-online.target

[Service]
User=bridgarvis
Group=bridgarvis
WorkingDirectory=/opt/bridgarvis
EnvironmentFile=/etc/bridgarvis/bridgarvis.env
ExecStart=/usr/bin/java -Xmx256m -jar /opt/bridgarvis/current/bridgarvis.jar
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=/var/log/bridgarvis

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
# Not enabled yet: the first deploy installs the jar and certificates, then enables the service.

# Ship the application log to CloudWatch
cat > /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json <<'CW'
{
  "logs": {
    "logs_collected": {
      "files": {
        "collect_list": [
          { "file_path": "/var/log/bridgarvis/bridgarvis.log", "log_group_name": "__LOG_GROUP__", "log_stream_name": "{instance_id}/app" },
          { "file_path": "/var/log/cloud-init-output.log", "log_group_name": "__LOG_GROUP__", "log_stream_name": "{instance_id}/bootstrap" }
        ]
      }
    }
  }
}
CW
/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -s \
  -c file:/opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json

echo "bridgarvis bootstrap complete"
