# Informe técnico — NexusPlay eSports Platform

**Curso:** Kubernetes con Prometheus & Grafana — Valle Grande · **Modalidad:** individual
**Dominio:** plataforma de torneos de eSports · **Servicio A:** `tournament-service` · **Servicio B:** `matchmaking-service`


---

## Parte A — Conceptos

### 1. ¿Qué es Micrometer y por qué se usa en lugar de la librería directa de Prometheus?

Micrometer es una fachada de métricas (como SLF4J lo es para logs): el código declara *qué* medir
(`Counter`, `Gauge`, `Timer`) y un *registry* decide *en qué formato y hacia dónde* se publica. En
`tournament-service` solo importo `io.micrometer.core.instrument.*`; la dependencia
`micrometer-registry-prometheus` es la que traduce esas métricas al formato de texto de Prometheus en `/metrics`.

Ventajas frente a usar `simpleclient` de Prometheus directamente: (1) el código de negocio no queda atado
a Prometheus — cambiar a otro backend es cambiar una dependencia; (2) Spring Boot Actuator ya instrumenta
HTTP, JVM, Tomcat y pools automáticamente con Micrometer; (3) aplica convenciones de nombres
(`tournament.slots.reserved` → `tournament_slots_reserved_total`) y tags uniformes (`management.metrics.tags.application`).

### 2. Counter, Gauge y Timer (ejemplos de mi código)

| Tipo | Qué mide | Ejemplo en mi código | Cómo se consulta |
|---|---|---|---|
| **Counter** | Valor que solo sube (eventos acumulados). Se analiza con `rate()`/`increase()` | `tournament.slots.reserved` en `TournamentMetrics.slotReserved()` — cada cupo reservado; `matchmaking.tournament.client.errors{reason="timeout"}` en `TournamentClient` | `rate(matchmaking_tournament_client_errors_total[1m])` |
| **Gauge** | Valor instantáneo que sube y baja | `tournament.slots.available{tournament=...}` (cupos libres) y `tournament.artificial.delay.ms` (delay inyectado en la Casuística 2) | `tournament_slots_available` |
| **Timer** | Cuántas veces ocurrió algo y cuánto tardó (`_count`, `_sum`, `_max` y `_bucket` si hay histograma) | `tournament.reservation.duration` (reserva de cupo) y `matchmaking.tournament.client.duration` (latencia B→A) | `histogram_quantile(0.95, sum by (le)(rate(matchmaking_tournament_client_duration_seconds_bucket[2m])))` |

### 3. ¿Qué es un ServiceMonitor y cómo sabe el Prometheus Operator qué monitorear?

Un `ServiceMonitor` es un CRD que declara *"scrapea los Services que tengan estos labels, por este puerto y esta ruta"*.
En `k8s/tournament-service/servicemonitor.yaml`: `selector.matchLabels: app=tournament-service` (debe coincidir
**exactamente** con los labels del `Service`), `endpoints.port: http` (nombre del puerto del Service, no el número) y `path: /metrics`.

El Prometheus Operator vigila los ServiceMonitors del cluster. Cada recurso `Prometheus` tiene
`serviceMonitorSelector` y `serviceMonitorNamespaceSelector`: solo los ServiceMonitors que cumplan esos selectores se
traducen a configuración de scrape. El operador resuelve el Service → sus `Endpoints` → los pods, y agrega cada pod como target.
Si el pod no está *Ready*, no figura en los Endpoints y su target desaparece.

### 4. Liveness probe vs readiness probe

- **Liveness**: *"¿el proceso sigue vivo?"*. Si falla `failureThreshold` veces seguidas, el kubelet **mata y reinicia** el contenedor (de ahí el `CrashLoopBackOff` cuando se repite).
- **Readiness**: *"¿puede atender tráfico ahora?"*. Si falla, el pod **no se reinicia**: solo se retira de los Endpoints del Service y deja de recibir peticiones hasta que vuelva a pasar.

En mi sistema ambos apuntan a `GET /health`, un endpoint propio que responde al instante y **no** consulta a otros
servicios (el de `matchmaking-service` no llama a `tournament-service`; si lo hiciera, una caída de A reiniciaría a B en cascada).
El delay artificial de la Casuística 2 tampoco afecta a `/health`, por eso A sigue "sano" aunque esté lento: ahí se ve por qué
los probes por sí solos no reemplazan a la observabilidad.

### 5. ¿Por qué apuntar Docker al daemon de Minikube antes de construir?

El cliente `docker` por defecto habla con el daemon del *host*. El nodo de Minikube es otra máquina (un contenedor con
el driver Docker) con su **propio daemon y su propio almacén de imágenes**. Una imagen construida en el host no existe
dentro del nodo; como mis Deployments usan imágenes locales (`tournament-service:1.0.0`, `imagePullPolicy: IfNotPresent`),
el kubelet intentaría bajarla de un registry remoto y los pods quedarían en `ErrImagePull` / `ImagePullBackOff`.
`eval $(minikube docker-env)` cambia las variables `DOCKER_HOST`, etc., para que `docker build` construya dentro del nodo
(`scripts/setup.sh`, paso 4).

### 6. ¿Qué pasa si no configuro el selector de ServiceMonitors en el values.yaml?

Por defecto el chart fija `serviceMonitorSelector: {matchLabels: {release: <nombre-del-release>}}` (con
`serviceMonitorSelectorNilUsesHelmValues: true`). Prometheus **ignora** cualquier ServiceMonitor sin ese label, aunque esté
perfectamente escrito: el recurso se crea sin error, pero el target nunca aparece en `/targets`. Por eso mi `values.yaml` pone
`serviceMonitorSelectorNilUsesHelmValues: false` (selector vacío = todos) y `serviceMonitorNamespaceSelector: {}`
(todos los namespaces), lo mismo para reglas (`ruleSelectorNilUsesHelmValues: false`). Como refuerzo, mis ServiceMonitors y el
PrometheusRule también llevan `release: kube-prometheus-stack`.

---

## Parte B — PromQL

Métricas reales de mis servicios; el label `job` equivale al nombre del Service.

**1. Tasa de requests por minuto de `matchmaking-service` (ventana de 5 minutos)**
```promql
sum(rate(http_server_requests_seconds_count{job="matchmaking-service", uri!~"/metrics|/health"}[5m])) * 60
```

**2. Latencia p95 del endpoint más crítico (inscripción de equipos: `POST /api/teams`)**
```promql
histogram_quantile(0.95,
  sum by (le) (rate(http_server_requests_seconds_bucket{job="matchmaking-service", method="POST", uri="/api/teams"}[5m])))
```

**3. Estado UP/DOWN de ambos servicios a la vez**
```promql
sum by (job) (up{namespace="nexusplay"})
```
Para un panel que muestre `0` incluso cuando la serie desaparece (pod fuera de los Endpoints):
`sum(up{job="tournament-service"}) or vector(0)`.

**4. Pregunta de negocio: ¿qué porcentaje de intentos de inscripción se pierde porque el torneo está lleno?**
```promql
sum(rate(matchmaking_registrations_rejected_total{reason="full"}[5m]))
/
( sum(rate(matchmaking_registrations_rejected_total[5m])) + sum(rate(matchmaking_teams_registered_total[5m])) )
```
Otras preguntas de negocio: cupos libres totales → `sum(tournament_slots_available)`;
torneos abiertos → `tournament_open_count`.

---

## Parte C — Casuísticas

> Variables de apoyo:
> ```bash
> export TOURNAMENT_URL=http://localhost:18081     # macOS/Windows (port-forward)   |  http://$(minikube ip):30081 en Linux
> export MATCHMAKING_URL=http://localhost:18082    #                                |  http://$(minikube ip):30082 en Linux
> ```
> Estado base antes de cada casuística: `kubectl -n nexusplay get pods` con ambos pods `1/1 Running` y los dos targets UP en Prometheus.

### 🔴 Casuística 1 — El servicio caído (CrashLoopBackOff)

**Contexto narrativo**
Es viernes por la noche, a una hora del inicio del *Valorant Lima Open*. Un compañero del equipo de plataforma "estandariza"
la configuración y despliega `tournament-service` con la variable `SERVER_PORT=9999`: la aplicación arranca bien, pero escucha
en un puerto distinto al que Kubernetes revisa (8081). Nadie lo nota hasta que los jugadores empiezan a quejarse.

**Síntoma (usuario final)**
Inscribir un equipo o abrir la sala de espera devuelve `503` ("El servicio de torneos no está disponible"). Nadie puede entrar al torneo.

**Activación**
```bash
kubectl -n nexusplay set env deployment/tournament-service SERVER_PORT=9999
kubectl -n nexusplay get pods -w        # ~2-3 minutos hasta ver CrashLoopBackOff (Ctrl+C para salir)
# opcional, para ver el efecto en el usuario:
./load-testing/stress.sh -s lobby -d 120 -c 3 -r 3
```

**Diagnóstico (Grafana / Prometheus)**
- Panel **Estado tournament-service (A)** pasa de UP (verde) a **DOWN** (rojo).
- Panel **Reinicios de contenedores** sube en escalera para el pod de `tournament-service`.
- Panel **Errores B → A por motivo** muestra `connection` (el Service no tiene Endpoints listos).
- En Prometheus → *Alerts*: `TournamentServiceDown` pasa a **FIRING** (≈1 min) y luego `NexusPlayPodCrashLooping`.
- `kubectl -n nexusplay describe pod -l app=tournament-service` → eventos `Liveness probe failed: ... connection refused` y `Back-off restarting failed container`.
- `matchmaking-service` sigue en UP: su `/health` no depende de A (fallo contenido, no en cascada).

**Resolución**
```bash
kubectl -n nexusplay set env deployment/tournament-service SERVER_PORT=8081
# alternativa: kubectl -n nexusplay rollout undo deployment/tournament-service
kubectl -n nexusplay rollout status deployment/tournament-service
```

**Lección técnica**
Los probes son el contrato entre la aplicación y Kubernetes. El *liveness* fallido hizo que el kubelet reiniciara el contenedor
una y otra vez (backoff exponencial → `CrashLoopBackOff`), mientras que el *readiness* fallido lo sacó de los Endpoints del
Service, así que `up` dejó de existir. Por eso las alertas usan `... or vector(0)`: un servicio caído no siempre reporta `up == 0`,
a veces simplemente **desaparece** del scrape.
*Experimento extra:* `kubectl -n nexusplay patch deployment tournament-service --type=json -p='[{"op":"replace","path":"/spec/template/spec/containers/0/readinessProbe/httpGet/path","value":"/nope"}]'`
deja el pod `Running 0/1` **sin reinicios** (solo readiness falla). Se revierte con `kubectl -n nexusplay rollout undo deployment/tournament-service`.

### 🟡 Casuística 2 — El servicio lento (timeouts en cascada)

**Contexto narrativo**
Durante la inscripción masiva, `tournament-service` empieza a responder lento (un recurso compartido saturado, simulado con un
delay artificial). `matchmaking-service` espera como máximo 2 s por respuesta (`TOURNAMENT_CLIENT_TIMEOUT_MS=2000`). Cuando A tarda
4 s, B se rinde antes de recibir la respuesta.

**Síntoma (usuario final)**
Las pantallas de sala de espera e inscripción tardan ~2 s y terminan en error `503` con `reason: timeout`. Los pods de ambos
servicios están `Running` y *Ready*: "todo parece sano" pero nada funciona.

**Activación**
```bash
kubectl -n nexusplay set env deployment/tournament-service ARTIFICIAL_DELAY_MS=4000
kubectl -n nexusplay rollout status deployment/tournament-service
./load-testing/stress.sh -s lobby -d 150 -c 5 -r 5
```

**Diagnóstico (Grafana / Prometheus)**
- Panel **Delay artificial en A (ms)** (Gauge `tournament_artificial_delay_ms`) muestra **4000** en rojo; el panel vecino marca el timeout de B (2000).
- Panel **Errores B → A por motivo**: sube `reason="timeout"`.
- Panel **Latencia p95 B → A** se pega a la línea del timeout (2 s); **Latencia p95 HTTP por servicio** muestra a A en ~4 s.
- El panel de estado de ambos servicios sigue en **UP**: los probes no ven el problema (`/health` no tiene delay).
- Alertas `MatchmakingTournamentTimeouts` y `TournamentServiceSlowForMatchmaking` en **FIRING**.
- Para aislar al culpable: la latencia de A (como servidor) ya es alta, la de B es alta solo *porque espera a A*, y B no tiene CPU saturada.

**Resolución**
```bash
kubectl -n nexusplay set env deployment/tournament-service ARTIFICIAL_DELAY_MS=0
kubectl -n nexusplay rollout status deployment/tournament-service
```

**Lección técnica**
La latencia se propaga entre microservicios: B se ve lento y erróneo aunque el problema esté en A. Sin métricas por servicio
(histogramas HTTP + el Timer del cliente B→A) es imposible saber cuál es el culpable. Además, un **timeout bien configurado** en
el cliente convierte un cuelgue indefinido en un error rápido y controlado (`503 + Retry-After`), y los health checks "baratos"
no sustituyen a la observabilidad de latencia.

### 🟠 Casuística 3 — Torneo agotado: demanda de inscripción perdida

**Contexto narrativo**
El *Valorant Lima Open* (16 cupos) se anuncia en redes y un streamer popular invita a su comunidad a inscribirse. En minutos llegan
cientos de intentos de inscripción para 16 cupos. Técnicamente nada "se rompe": los servicios están sanos y responden rápido.
Pero cada equipo rechazado es un inscrito (y sus ingresos por *prize pool*/patrocinio) que se pierde.

**Síntoma (usuario final)**
Los primeros 16 equipos se inscriben. Todos los demás reciben `409 Conflict` ("no tiene cupos disponibles") y se van a la competencia.

**Activación**
```bash
./load-testing/stress.sh -s register -d 60 -r 8 -i valorant-lima-open
```

**Diagnóstico (Grafana / Prometheus)**
- Panel **Cupos disponibles por torneo**: `valorant-lima-open` cae a **0** (rojo).
- Panel **Inscripciones aceptadas vs rechazadas**: aceptadas = 16 y luego 0; rechazadas por `full` suben a ~8/s.
- Panel **Torneos abiertos** baja en 1; la latencia y el estado UP siguen normales (no hay fallo técnico, es un fallo de negocio).
- Alerta `HighTeamRegistrationRejections` en **FIRING** (y `NoOpenTournaments` si solo quedaba ese torneo abierto).
- Consulta de negocio: `sum(increase(matchmaking_registrations_rejected_total{reason="full"}[5m]))` = equipos perdidos.

**Resolución**
Abrir cupos extra mientras dura la demanda (en otra terminal, a mitad del stress):
```bash
curl -s -X POST "$TOURNAMENT_URL/api/tournaments/valorant-lima-open/capacity" \
     -H 'Content-Type: application/json' -d '{"extraSlots": 500}'
```
Las inscripciones vuelven a aceptarse y las rechazadas caen a 0.

**Lección técnica**
No todo incidente es una caída: **las métricas de negocio** (cupos disponibles, rechazos por motivo) detectan pérdidas que ninguna
métrica de infraestructura ve. Un Gauge por torneo + un Counter con el *motivo* del rechazo permiten alertar sobre *demanda
insatisfecha* y actuar antes de que se traduzca en ingresos perdidos.

---

## Parte D — Evidencias del sistema

| # | Evidencia | Archivo |
|---|---|---|
| 1 | `/targets` de Prometheus con `tournament-service` y `matchmaking-service` en **UP** | `![targets](evidencias/01-targets-up.png)` |
| 2 | Dashboard de Grafana con los paneles funcionando | `![dashboard](evidencias/02-dashboard.png)` |
| 3 | `kubectl -n nexusplay get pods` con todos los pods en **Running** (y `kubectl get pods -n monitoring`) | `![pods](evidencias/03-pods-running.png)` |
| 4 | Una alerta en **FIRING** (Prometheus → Alerts o Alertmanager) | `![alerta](evidencias/04-alerta-firing.png)` |

Evidencias por casuística (captura de Grafana con la anomalía):
`evidencias/c1-servicio-caido.png` · `evidencias/c2-servicio-lento.png` · `evidencias/c3-torneo-agotado.png`

Dashboard exportado: [`docs/grafana/nexusplay-dashboard.json`](grafana/nexusplay-dashboard.json)