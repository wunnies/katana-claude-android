#!/bin/bash
#PBS -l select=1:ncpus=1:mem=96GB
#PBS -l walltime=08:00:00
#PBS -N tunnel-sshd

PORT=2222
HOST_KEY="$HOME/.ssh/sshd_host_ecdsa_key"
LOGDIR="$HOME/sshd_debug"
SSHD_CONF="$HOME/.ssh/sshd_tunnel_config"
mkdir -p "$LOGDIR"

echo "[INFO] Starting SSHD on $(hostname) at $(date)" | tee -a "$LOGDIR/run.log"

ps aux > "$LOGDIR/ps_before_sshd.txt"

if [ ! -f "$HOST_KEY" ]; then
  echo "[INFO] Generating new host key..." | tee -a "$LOGDIR/run.log"
  ssh-keygen -t ecdsa -f "$HOST_KEY" -N "" >> "$LOGDIR/run.log" 2>&1
fi

# Memory monitor: log usage every 5 min
(while true; do
  echo "$(date '+%Y-%m-%d %H:%M:%S') $(free -m | awk '/Mem:/{print $3"MB used / "$2"MB total"}')" >> "$LOGDIR/mem.log"
  sleep 300
done) &

echo "[INFO] Launching sshd on port $PORT" | tee -a "$LOGDIR/run.log"
/usr/sbin/sshd -D -p "$PORT" -f "$SSHD_CONF" -h "$HOST_KEY" -E "$LOGDIR/sshd.log" &
SSHD_PID=$!

# confirm the port is bound
for i in {1..30}; do
  if ss -tln | grep -q ":$PORT "; then
    echo "$(hostname):$PORT:$PBS_JOBID" > "$HOME/.tunnel_ready"
    echo "[INFO] sshd confirmed listening at $(date)" | tee -a "$LOGDIR/run.log"
    break
  fi
  sleep 1
done

ps aux > "$LOGDIR/ps_after_sshd.txt"
wait $SSHD_PID
# Clean up the marker when the job ends
rm -f "$HOME/.tunnel_ready"
