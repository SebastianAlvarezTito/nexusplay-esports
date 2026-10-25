#!/usr/bin/env bash
# =============================================================================
# NexusPlay eSports Platform - teardown.sh
# Elimina los recursos creados por setup.sh.
#   ./scripts/teardown.sh             -> borra la app y el stack de monitoreo (el cluster sigue)
#   ./scripts/teardown.sh --cluster   -> además elimina el cluster Minikube completo
# =============================================================================
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NS_APP="nexusplay"
NS_MON="monitoring"
RELEASE="kube-prometheus-stack"
DELETE_CLUSTER=false

usage() { sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; }

for arg in "$@"; do
  case "$arg" in
    -c|--cluster) DELETE_CLUSTER=true ;;
    -h|--help)    usage; exit 0 ;;
    *) echo "Parámetro desconocido: $arg"; usage; exit 1 ;;
  esac
done

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

log "Deteniendo port-forwards"
"$ROOT_DIR/scripts/port-forward.sh" stop >/dev/null 2>&1 || true

if $DELETE_CLUSTER; then
  log "Eliminando el cluster Minikube completo"
  minikube delete
  echo "Listo: cluster eliminado."
  exit 0
fi

if ! kubectl cluster-info >/dev/null 2>&1; then
  echo "No hay cluster accesible; nada que limpiar."; exit 0
fi

log "Eliminando namespace $NS_APP (deployments, services, ServiceMonitors, PrometheusRule)"
kubectl delete namespace "$NS_APP" --ignore-not-found --wait=true

log "Eliminando dashboard y release de Helm"
kubectl -n "$NS_MON" delete configmap nexusplay-dashboard --ignore-not-found
helm uninstall "$RELEASE" -n "$NS_MON" 2>/dev/null || true
kubectl delete namespace "$NS_MON" --ignore-not-found --wait=true

echo
echo "Recursos eliminados. Las imágenes y el cluster siguen disponibles."
echo "Para borrar también el cluster: ./scripts/teardown.sh --cluster"
