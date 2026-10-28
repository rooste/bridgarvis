# Bridgarvis

AWS-side gateway that brings email, Alexa and (later) SMS into the Juujarvis family assistant. It runs on EC2. Juujarvis, on the Mac, connects to it outbound over mutual TLS; nothing in AWS ever connects to the Mac.

**Status:** live for email (SES receiving and sending, DynamoDB/S3 storage, mail UI) and Alexa. SMS is still a mock: inbound comes from `POST /api/mock/inbound` and sends are only recorded. Full design and current setup: `../docs/bridgarvis.md` (workspace docs).

## Security model

- TLS with `client-auth: need`. The trust store holds only pinned certificates (Juujarvis's, plus one per browser device for the mail UI), so any other client fails the TLS handshake before reaching an endpoint. `ClientRoleFilter` then limits each certificate by its CN: `juujarvis` → `/api`, `/ws`; `browser-*` → `/ui`.
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

## REST API (mTLS, client certificate CN `juujarvis`)

| Method | Path | |
| --- | --- | --- |
| GET | `/api/messages` | Pending (unacked) inbound messages |
| GET | `/api/messages/{id}` | One pending message. `404` once acked |
| GET | `/api/messages/{id}/attachments/{index}` | Attachment bytes |
| POST | `/api/messages/{id}/ack` | Handled: the message is no longer pending (kept until its retention ends). `204`, or `404` for an unknown id |
| POST | `/api/email/send` | `{to[], cc[], subject, text, inReplyTo?, references?}` → `{id, messageId, status}` via SES |
| POST | `/api/alexa/{id}/reply` | `{speech, reprompt?, endSession, room?}`: answer a waiting Alexa request |
| POST | `/api/alexa/announce` | `{deviceId, text}`: speak on an Echo |
| POST | `/api/messages/send` | SMS mock: `{"to","text"}` → `{"messageId","status"}` |
| GET | `/api/messages/outbox` | SMS mock outbox |
| POST | `/api/mock/inbound` | SMS mock: `{"from","text"}` simulates an incoming SMS |

Browser client certificates (CN `browser-*`) may only use the mail UI (`/ui`). The Alexa connector (localhost, behind nginx) serves only `POST /alexa`, which verifies Amazon's signature.

## WebSocket: `wss://host:8443/ws` (mTLS)

Notification-only. The server sends `{"type":"message.available","id":"…","channel":"email|alexa|sms"}`, with no message content.

Integration flow:
1. Juujarvis keeps the socket open and receives `message.available`.
2. Juujarvis calls `GET /api/messages/{id}`, handles the message, then `POST /api/messages/{id}/ack`.
3. Replies go out through `/api/email/send` or `/api/alexa/{id}/reply`.

On connect, every pending message is announced again. Juujarvis also drains `GET /api/messages` on every (re)connect, so a missed notification never loses a message.

## Tests

`./gradlew test` starts the app with real mutual TLS (test-only certs in `src/test/resources/certs`). It checks that clients with no certificate or the wrong one are rejected, along with the REST pull flow and the WebSocket notifications.

## Infrastructure and hosting

`infra/` is a CDK (TypeScript) app for `BridgarvisStack`: its own VPC, one Amazon Linux 2023 Graviton instance with no SSH, an Elastic IP, DNS records, the deploy bucket, the `/bridgarvis` CloudWatch log group, SES receiving/sending, SNS/SQS, the DynamoDB tables, the mail bucket and the Alexa smart home Lambda. First-boot setup is in `infra/assets/bootstrap.sh`. It installs Java 21, nginx, certbot (Route53 DNS validation), automatic security updates, swap and the CloudWatch agent.

Account-specific values (AWS profile, domain names, the owner's name, phone number) live in `infra/local.env`, which is gitignored. See `infra/local.env.example`.

```bash
cd infra && set -a && source local.env && set +a
cdk diff && cdk deploy               # infrastructure changes

scripts/deploy.sh                              # deploy the app (S3 + SSM, health check, rollback)
scripts/host-run.sh 'systemctl status nginx'   # run a command on the host (via SSM, no SSH)
scripts/deploy-site.sh                         # publish site/ (program website, privacy policy, terms)
```

`site/` holds the public program website needed for SMS toll-free registration. Personal details are placeholders, filled in from `local.env` at publish time.
