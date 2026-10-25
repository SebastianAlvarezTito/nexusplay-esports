#!/usr/bin/env bash
# =============================================================================
# NexusPlay eSports Platform - setup.sh
# Levanta TODO desde cero, sin intervención manual:
#   1) verifica prerequisitos   2) inicia Minikube (+ metrics-server)
#   3) instala kube-prometheus-stack con Helm   4) construye las imágenes dentro de Minikube
#   5) aplica los manifiestos y el dashboard    6) muestra las URLs
#
# Variables opcionales: MINIKUBE_CPUS (4)  MINIKUBE_MEMORY en MB (3072)  IMAGE_TAG (1.0.0)
# En Windows ejecútalo desde Git Bash o WSL.
# =============================================================================
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NS_APP="nexusplay"
NS_MON="monitoring"
RELEASE="kube-prometheus-stack"
IMAGE_TAG="${IMAGE_TAG:-1.0.0}"
MINIKUBE_CPUS="${MINIKUBE_CPUS:-4}"
MINIKUBE_MEMORY="${MINIKUBE_MEMORY:-3072}"

log()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✔\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '\n\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }
trap 'die "El setup falló en la línea $LINENO. Revisa el mensaje anterior y vuelve a ejecutar (es idempotente)."' ERR

print_install_hints() {
  cat <<'HINTS'

  Instala lo que falte y vuelve a ejecutar:

  macOS:    brew install --cask docker && brew install minikube kubectl helm
  Windows:  winget install Docker.DockerDesktop Kubernetes.minikube Kubernetes.kubectl Helm.Helm
  Linux:    ver README.md (sección "Instalación de herramientas")
HINTS
}

# ------------------------------------------------------------------ 1) prerequisitos
log "1/6 Verificando prerequisitos"
missing=0
for bin in docker minikube kubectl helm; do
  if command -v "$bin" >/dev/null 2>&1; then
    ok "$bin encontrado"
  else
    warn "Falta '$bin'"; missing=1
  fi
done
if (( missing )); then print_install_hints; exit 1; fi
docker info >/dev/null 2>&1 || die "Docker está instalado pero su daemon no responde. Abre Docker Desktop y reintenta."
ok "Docker daemon activo"

# ------------------------------------------------------------------ 2) cluster
log "2/6 Iniciando Minikube (cpus=$MINIKUBE_CPUS, memoria=${MINIKUBE_MEMORY}MB)"
if minikube status >/dev/null 2>&1; then
  ok "Minikube ya estaba corriendo"
else
  minikube start --driver=docker --cpus="$MINIKUBE_CPUS" --memory="$MINIKUBE_MEMORY"
fi
minikube addons enable metrics-server >/dev/null
ok "Addon metrics-server habilitado"
kubectl config use-context minikube >/dev/null

# ------------------------------------------------------------------ 3) monitoreo
log "3/6 Instalando kube-prometheus-stack (Prometheus, Grafana, Alertmanager, Operator)"
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts --force-update >/dev/null
helm repo update >/dev/null
helm upgrade --install "$RELEASE" prometheus-community/kube-prometheus-stack \
  --namespace "$NS_MON" --create-namespace \
  -f "$ROOT_DIR/k8s/monitoring/kube-prometheus-stack-values.yaml" \
  --wait --timeout 15m
ok "Stack de monitoreo listo"

# ------------------------------------------------------------------ 4) imágenes
log "4/6 Construyendo imágenes dentro del daemon de Minikube"
# Sin esto, Kubernetes no encontraría las imágenes locales (ErrImagePull / ImagePullBackOff).
eval "$(minikube docker-env --shell bash)"
docker build -t "tournament-service:${IMAGE_TAG}" "$ROOT_DIR/microservices/tournament-service"
docker build -t "matchmaking-service:${IMAGE_TAG}" "$ROOT_DIR/microservices/matchmaking-service"
eval "$(minikube docker-env --unset --shell bash)"
ok "Imágenes tournament-service:${IMAGE_TAG} y matchmaking-service:${IMAGE_TAG} construidas"

# ------------------------------------------------------------------ 5) manifiestos
log "5/6 Aplicando manifiestos de Kubernetes"
kubectl apply -f "$ROOT_DIR/k8s/00-namespace.yaml"
kubectl apply -f "$ROOT_DIR/k8s/tournament-service/"
kubectl apply -f "$ROOT_DIR/k8s/matchmaking-service/"
kubectl apply -f "$ROOT_DIR/k8s/monitoring/prometheus-rules.yaml"

# Dashboard de Grafana: el sidecar de Grafana carga los ConfigMaps con el label grafana_dashboard=1
kubectl -n "$NS_MON" create configmap nexusplay-dashboard \
  --from-file=nexusplay-dashboard.json="$ROOT_DIR/docs/grafana/nexusplay-dashboard.json" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS_MON" label configmap nexusplay-dashboard grafana_dashboard=1 --overwrite >/dev/null

# Si ya existían pods con la misma etiqueta de imagen, los reiniciamos para tomar la imagen recién construida
kubectl -n "$NS_APP" rollout restart deployment/tournament-service deployment/matchmaking-service >/dev/null
kubectl -n "$NS_APP" rollout status deployment/tournament-service --timeout=300s
kubectl -n "$NS_APP" rollout status deployment/matchmaking-service --timeout=300s
ok "Pods en Running"

# Verificación best-effort: comprobamos que ambos servicios tengan Endpoints (IPs) listos.
# Usamos kubectl en lugar de wget dentro del pod de Prometheus (que no tiene wget instalado).
log "Verificando endpoints de los servicios (best-effort)"
targets_ready=0
for _ in $(seq 1 24); do
  endpoints_count="$(kubectl -n "$NS_APP" get endpoints tournament-service matchmaking-service -o jsonpath='{range .items[*]}{.subsets[*].addresses[*].ip}{"\n"}{end}' 2>/dev/null | grep -c . || true)"
  [[ "${endpoints_count:-0}" -ge 2 ]] && targets_ready=1 && break
  sleep 5
done

if [[ "$targets_ready" -eq 1 ]]; then 
  ok "Ambos servicios tienen Endpoints listos (los targets estarán UP en Prometheus en unos segundos)"
else 
  warn "Aún no se ven ambos endpoints listos; revisa http://<url-prometheus>/targets en 1-2 minutos"
fi

# ------------------------------------------------------------------ 6) URLs
log "6/6 Accesos"
case "$(uname -s)" in
  Linux*)
    IP="$(minikube ip)"
    GRAFANA_URL="http://${IP}:30300"; PROM_URL="http://${IP}:30900"; ALERT_URL="http://${IP}:30903"
    TOURNAMENT_URL="http://${IP}:30081"; MATCHMAKING_URL="http://${IP}:30082"
    ;;
  *)
    # Docker Desktop (macOS/Windows): los NodePorts no son alcanzables desde el host -> port-forward
    "$ROOT_DIR/scripts/port-forward.sh" start >/dev/null
    GRAFANA_URL="http://localhost:3000"; PROM_URL="http://localhost:9090"; ALERT_URL="http://localhost:9093"
    TOURNAMENT_URL="http://localhost:18081"; MATCHMAKING_URL="http://localhost:18082"
    ;;
esac
GRAFANA_PASS="$(kubectl -n "$NS_MON" get secret "${RELEASE}-grafana" -o jsonpath='{.data.admin-password}' | base64 -d)"

cat <<OUT

  ╔══════════════════════════════════════════════════════════════════╗
  ║  NexusPlay eSports Platform - listo                              ║
  ╚══════════════════════════════════════════════════════════════════╝
  Grafana            : ${GRAFANA_URL}   (usuario: admin / clave: ${GRAFANA_PASS})
  Dashboard          : Grafana > Dashboards > "NexusPlay eSports - Observabilidad"
  Prometheus         : ${PROM_URL}   (targets: ${PROM_URL}/targets)
  Alertmanager       : ${ALERT_URL}
  tournament-service : ${TOURNAMENT_URL}   (/health  /metrics  /api/tournaments)
  matchmaking-service: ${MATCHMAKING_URL}   (/health  /metrics  /api/teams)

  Tráfico de prueba  : ./load-testing/stress.sh -h
  Apagar todo        : ./scripts/teardown.sh [--cluster]
OUT