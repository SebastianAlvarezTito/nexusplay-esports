# NexusPlay eSports Platform

Sistema de microservicios Java para la gestión de torneos de eSports, desplegado en Kubernetes (Minikube)
y monitoreado con Prometheus y Grafana (`kube-prometheus-stack`).

```
 jugador ──► matchmaking-service (B, :8082) ── WebClient + timeout 2s ──► tournament-service (A, :8081)
                 equipos · emparejamientos                                  torneos · cupos · estados
                        │  /metrics                                                │  /metrics
                        └──────────────► Prometheus (ServiceMonitor) ◄─────────────┘
                                              │ PrometheusRule ──► Alertmanager
                                              └──────────────────► Grafana (dashboard)
```

El **Servicio B depende del Servicio A para decidir**: antes de inscribir un equipo consulta el torneo
(¿está `OPEN`?, ¿quedan cupos?, ¿cumple el MMR mínimo?) y reserva el cupo en A. Si A no responde dentro
del timeout, B lo registra (log + métrica) y responde `503` controlado.

## Estructura

```
microservices/
  tournament-service/     Servicio A · Java 17 · Spring Boot 3.3 · Maven · Dockerfile 2 etapas
  matchmaking-service/    Servicio B · idem + Spring WebClient
k8s/
  00-namespace.yaml       namespace "nexusplay"
  tournament-service/     deployment · service (NodePort, puerto "http") · servicemonitor
  matchmaking-service/    deployment · service (NodePort, puerto "http") · servicemonitor
  monitoring/             values del chart + PrometheusRule (alertas)
load-testing/stress.sh    generador de tráfico parametrizable
scripts/                  setup.sh · teardown.sh · port-forward.sh
docs/
  informe.md              informe técnico (Partes A-D + 3 casuísticas)
  grafana/                dashboard exportado en JSON
  evidencias/             capturas de pantalla
```

## Instalación de herramientas

Herramientas: **Docker Desktop**, **Minikube**, **kubectl**, **Helm**.

**macOS (Homebrew)**
```bash
brew install --cask docker
brew install minikube kubectl helm
open -a Docker        # esperar a que Docker Desktop termine de iniciar
```

**Windows (PowerShell como administrador)** — luego ejecutar los scripts desde *Git Bash* o *WSL*
```powershell
winget install Docker.DockerDesktop
winget install Kubernetes.minikube
winget install Kubernetes.kubectl
winget install Helm.Helm
```

**Linux (Ubuntu/Debian, x86_64)**
```bash
# Docker Engine
curl -fsSL https://get.docker.com | sudo sh && sudo usermod -aG docker "$USER" && newgrp docker
# Minikube
curl -LO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
sudo install minikube-linux-amd64 /usr/local/bin/minikube
# kubectl
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl
# Helm
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
```

**Verificación**
```bash
docker --version && docker info >/dev/null && echo "docker OK"
minikube version
kubectl version --client
helm version
```
Recursos recomendados para Docker Desktop: **≥ 4 CPU y ≥ 7 GB de RAM** (el script pide 4 CPU / 6 GB a Minikube).

## Puesta en marcha (un solo comando)

```bash
./scripts/setup.sh
```
Verifica prerequisitos → inicia Minikube (+ `metrics-server`) → instala `kube-prometheus-stack` →
construye las imágenes **dentro del daemon de Minikube** → aplica manifiestos y dashboard → muestra las URLs.
Es idempotente: si falla a mitad, vuelve a ejecutarlo.

### URLs

| Componente | Linux (`minikube ip`) | macOS / Windows (port-forward automático) |
|---|---|---|
| Grafana (`admin` / clave en `k8s/monitoring/kube-prometheus-stack-values.yaml`) | `http://<ip>:30300` | `http://localhost:3000` |
| Prometheus (`/targets`, `/alerts`) | `http://<ip>:30900` | `http://localhost:9090` |
| Alertmanager | `http://<ip>:30903` | `http://localhost:9093` |
| tournament-service | `http://<ip>:30081` | `http://localhost:18081` |
| matchmaking-service | `http://<ip>:30082` | `http://localhost:18082` |

> La clave de Grafana es `Nx-Play-2026!` por defecto: **cámbiala por la tuya** en el `values.yaml` antes de instalar.

## Prueba rápida

```bash
TS=http://localhost:18081; MM=http://localhost:18082      # ajusta según tu SO
curl $TS/api/tournaments                                   # torneos semilla
curl -X POST $MM/api/teams -H 'Content-Type: application/json' \
     -d '{"name":"Lima Wolves","tournamentId":"valorant-lima-open","mmr":1500}'
curl $MM/api/tournaments/valorant-lima-open/lobby          # B consulta a A
curl $TS/metrics | head                                    # métricas Prometheus
```

## Endpoints

**tournament-service (A)**: `GET /health` · `GET /metrics` · `GET /api/tournaments[?game=&status=]` · `GET /api/tournaments/{id}` ·
`POST /api/tournaments` · `POST /api/tournaments/{id}/slots` · `DELETE /api/tournaments/{id}/slots/{teamId}` ·
`POST /api/tournaments/{id}/capacity` · `POST /api/tournaments/{id}/start`

**matchmaking-service (B)**: `GET /health` · `GET /metrics` · `POST /api/teams` · `GET /api/teams[?tournamentId=]` ·
`GET /api/teams/{id}` · `DELETE /api/teams/{id}` · `POST /api/matchmaking/{tournamentId}/pair[?allowPartial=true]` ·
`GET /api/matches` · `POST /api/matches/{id}/result` · `GET /api/tournaments/{id}/lobby`

Torneos semilla: `valorant-lima-open` (16 cupos, MMR ≥ 800), `dota2-andes-cup` (8), `cs2-pacific-series` (32, MMR ≥ 1000), `lol-inca-league` (16).

## Tráfico de prueba y casuísticas

```bash
./load-testing/stress.sh -h
```
Las tres casuísticas (servicio caído, servicio lento, torneo agotado) están documentadas paso a paso en
[`docs/informe.md`](docs/informe.md#parte-c--casuísticas).

## Limpieza

```bash
./scripts/teardown.sh             # borra app + monitoreo (el cluster sigue)
./scripts/teardown.sh --cluster   # además elimina el cluster Minikube
```

## Solución de problemas

| Síntoma | Causa probable |
|---|---|
| Target ausente en `/targets` | El selector del ServiceMonitor no coincide con los labels del Service, o el `values.yaml` no abrió los selectores |
| `/metrics` devuelve 404 | Falta `management.endpoints.web.path-mapping.prometheus=metrics` (ya configurado en `application.properties`) |
| Pods en `ErrImagePull` / `ImagePullBackOff` | La imagen no se construyó con `eval $(minikube docker-env)` |
| Alertas ausentes en Prometheus | El `PrometheusRule` no lleva `release: kube-prometheus-stack` o `ruleSelectorNilUsesHelmValues` sigue en `true` |
| En macOS/Windows la URL por `minikube ip` no abre | Usa los port-forwards (`./scripts/port-forward.sh start`) |
