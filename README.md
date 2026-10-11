# Bridgarvis

AWS-side gateway that brings SMS (and later a dedicated email mailbox) into the Juujarvis family assistant. It runs on EC2. Juujarvis, on the Mac, connects to it outbound over mutual TLS; nothing in AWS ever connects to the Mac.

**Status:** mock. Inbound messages come from `POST /api/mock/inbound` and sends are only recorded. SQS and the AWS SMS API come next.

## Security model

- TLS with `client-auth: need`. The trust store holds exactly one certificate, Juujarvis's, so any other client fails the TLS handshake before reaching an endpoint.
- Actuator health is served on `127.0.0.1:8081`, plain HTTP, so local health checks don't need the client cert. It isn't exposed on the public port.
- Logs carry message IDs only, never message bodies.

## Run locally

```bash
scripts/gen-certs.sh                 # creates certs/ (gitignored)
./gradlew bootRun                    # https://localhost:8443
```

```bash
curl --cacert certs/server.pem --cert certs/juujarvis.pem --key certs/juujarvis.key \
     https://localhost:8443/api/messages
```

## Configuration

| Env var | Default | Purpose |
| --- | --- | --- |
| `BRIDGARVIS_PORT` | `8443` | Public TLS port |
| `BRIDGARVIS_TLS_CERT` / `BRIDGARVIS_TLS_KEY` | `file:certs/server.*` | Server certificate (Let's Encrypt in production) |
| `BRIDGARVIS_TLS_RELOAD` | `false` | Reload the server cert when files change (set `true` with Let's Encrypt) |
| `BRIDGARVIS_CLIENT_CERT` | `file:certs/juujarvis.pem` | The pinned client certificate (PEM; may hold several during rotation) |
| `BRIDGARVIS_MANAGEMENT_PORT` | `8081` | Localhost-only health port |
| `BRIDGARVIS_SMS_NUMBER` | placeholder | Own SMS number |

## REST API (mTLS)

| Method | Path | |
| --- | --- | --- |
| GET | `/api/messages` | Pending (unacked) inbound messages |
| GET | `/api/messages/{id}` | One pending message. `404` once acked |
| POST | `/api/messages/{id}/ack` | Acknowledge, which deletes the message. `204`, or `404` for an unknown id |
| POST | `/api/messages/send` | `{"to","text"}` → `{"messageId","status"}` |
| GET | `/api/messages/outbox` | Mock outbox |
| POST | `/api/mock/inbound` | `{"from","text"}`: simulate an incoming SMS |

## WebSocket: `wss://host:8443/ws` (mTLS)

Notification-only. The server sends `{"type":"sms.available","id":"…"}`, with no message content.

Integration flow:
1. Juujarvis keeps the socket open and receives `sms.available`.
2. Juujarvis calls `GET /api/messages/{id}`, processes the message, then `POST /api/messages/{id}/ack`.
3. Replies go out through `POST /api/messages/send`.

On connect, every unacked message is announced again. On startup Juujarvis can also drain `GET /api/messages`, so a missed notification never loses a message.

## Tests

`./gradlew test` starts the app with real mutual TLS (test-only certs in `src/test/resources/certs`). It checks that clients with no certificate or the wrong one are rejected, along with the REST pull flow and the WebSocket notifications.

## Infrastructure and hosting

`infra/` is a CDK (TypeScript) app for `BridgarvisStack`: its own VPC, one Amazon Linux 2023 Graviton instance with no SSH, an Elastic IP, DNS records, the deploy bucket and the `/bridgarvis` CloudWatch log group. First-boot setup is in `infra/assets/bootstrap.sh`. It installs Java 21, nginx, certbot (Route53 DNS validation), automatic security updates, swap and the CloudWatch agent.

Account-specific values (AWS profile, domain names, the owner's name, phone number) live in `infra/local.env`, which is gitignored. See `infra/local.env.example`.

```bash
cd infra && set -a && source local.env && set +a
cdk diff && cdk deploy               # infrastructure changes

scripts/host-run.sh 'systemctl status nginx'   # run a command on the host (via SSM, no SSH)
scripts/deploy-site.sh                         # publish site/ (program website, privacy policy, terms)
```

`site/` holds the public program website needed for SMS toll-free registration. Personal details are placeholders, filled in from `local.env` at publish time.
