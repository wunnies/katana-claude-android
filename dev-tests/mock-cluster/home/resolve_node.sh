#!/bin/bash
set -eo pipefail
USER=z5019209
JOBNAME=tunnel-sshd
PORT=2222

source /etc/profile

read -r JOBID NODE < <(qstat -f | awk -v jn="$JOBNAME" -v usr="$USER" '
  /^Job Id:/        { jid=$3; node="" }
  /^    Job_Name/   { name=$3 }
  /^    Job_Owner/  { split($3, a, "@"); owner=a[1] }
  /^    exec_host/  { split($3, h, "/"); node=h[1] }
  owner==usr && name==jn && node!="" { print jid, node; exit }
')

if [[ -z "$JOBID" || -z "$NODE" ]]; then
  echo "❌ No running job named '$JOBNAME' found for $USER" >&2
  exit 1
fi

echo "Connecting to $NODE:$PORT (job $JOBID)" >&2

for i in {1..120}; do
  if nc -z "$NODE" "$PORT" 2>/dev/null; then
    exec nc "$NODE" "$PORT"
  fi
  sleep 5
done

echo "❌ $NODE:$PORT never came up after 10 minutes" >&2
