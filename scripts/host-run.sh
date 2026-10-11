#!/usr/bin/env bash
# Runs a shell script on the Bridgarvis host through SSM Run Command (no SSH) and prints its output.
#
#   scripts/host-run.sh 'systemctl status nginx'
#   scripts/host-run.sh < some-script.sh
#
# Needs infra/local.env (AWS profile/region). The instance is found by its CloudFormation output.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
set -a; source "$ROOT/infra/local.env"; set +a

if [ $# -gt 0 ]; then SCRIPT="$1"; else SCRIPT="$(cat)"; fi

INSTANCE="$(aws cloudformation describe-stacks --stack-name BridgarvisStack \
  --query "Stacks[0].Outputs[?OutputKey=='InstanceId'].OutputValue" --output text)"

PARAMS="$(python3 -c 'import json,sys; print(json.dumps({"commands": [sys.argv[1]], "executionTimeout": ["900"]}))' "$SCRIPT")"
CMD_ID="$(aws ssm send-command --instance-ids "$INSTANCE" --document-name AWS-RunShellScript \
  --parameters "$PARAMS" --query Command.CommandId --output text)"

while true; do
  STATUS="$(aws ssm get-command-invocation --command-id "$CMD_ID" --instance-id "$INSTANCE" \
    --query Status --output text 2>/dev/null || echo Pending)"
  case "$STATUS" in Pending|InProgress|Delayed) sleep 2 ;; *) break ;; esac
done

aws ssm get-command-invocation --command-id "$CMD_ID" --instance-id "$INSTANCE" \
  --query '[StandardOutputContent, StandardErrorContent]' --output text
[ "$STATUS" = Success ] || { echo "SSM command $STATUS" >&2; exit 1; }
