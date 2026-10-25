#!/usr/bin/env bash
# =============================================================================
# Port-forwards en segundo plano (necesarios con Docker Desktop en macOS/Windows,
# donde los NodePorts de Minikube no son alcanzables desde el host).
#   ./scripts/port-forward.sh start | stop | status
#   Grafana 3000 · Prometheus 9090 · Alertmanager 9093 · tournament 18081 · matchmaking 18082
# =============================================================================
set -uo pipefail

RELEASE="kube-prometheus-stack"
PID_FILE="${TMPDIR:-/tmp}/nexusplay-port-forward.pids"

# namespace|servicio|puerto-local:puerto-servicio
FORWARDS=(
  "monitoring|${RELEASE}-grafana|3000:80"
  "monitoring|${RELEASE}-prometheus|9090:9090"
  "monitoring|${RELEASE}-alertmanager|9093:9093"
  "nexusplay|tournament-service|18081:8081"
  "nexusplay|matchmaking-service|18082:8082"
)

stop_all() {
  if [[ -f "$PID_FILE" ]]; then
    while read -r pid; do kill "$pid" 2>/dev/null || true; done < "$PID_FILE"
    rm -f "$PID_FILE"
  fi
}

case "${1:-}" in
  start)
    stop_all
    for f in "${FORWARDS[@]}"; do
      IFS='|' read -r ns svc ports <<< "$f"
      nohup kubectl -n "$ns" port-forward "svc/$svc" "$ports" >/dev/null 2>&1 &
      echo $! >> "$PID_FILE"
      echo "svc/$svc -> localhost:${ports%%:*}"
    done
    ;;
  stop)   stop_all; echo "port-forwards detenidos" ;;
  status) [[ -f "$PID_FILE" ]] && echo "PIDs activos: $(tr '\n' ' ' < "$PID_FILE")" || echo "sin port-forwards" ;;
  *)      echo "Uso: $0 start|stop|status"; exit 1 ;;
esac
