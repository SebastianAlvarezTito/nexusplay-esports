#!/usr/bin/env bash
# =============================================================================
# NexusPlay eSports Platform - stress.sh
# Generador de tráfico parametrizable (solo curl + bash) para las casuísticas.
#
# Uso: ./load-testing/stress.sh [opciones]
#   -s ESCENARIO  lobby | browse | register | mixed        (default: mixed)
#                   lobby    -> GET sala de espera (matchmaking -> tournament): ideal Casuística 2
#                   browse   -> lecturas variadas en ambos servicios
#                   register -> POST masivo de equipos: ideal Casuística 3
#                   mixed    -> 80% lecturas / 20% inscripciones
#   -d SEGUNDOS   duración total                           (default: 60)
#   -c N          workers concurrentes                     (default: 5)
#   -r RPS        peticiones por segundo totales (0 = sin límite)  (default: 10)
#   -i ID         torneo objetivo                          (default: valorant-lima-open)
#   -T URL        URL de tournament-service   (auto-detecta port-forward o minikube ip)
#   -M URL        URL de matchmaking-service  (auto-detecta port-forward o minikube ip)
#   -h            esta ayuda
#
# Ejemplos:
#   ./load-testing/stress.sh -s lobby -d 120 -c 5 -r 5
#   ./load-testing/stress.sh -s register -d 45 -r 8 -i valorant-lima-open
# También se pueden fijar con TOURNAMENT_URL / MATCHMAKING_URL.
# =============================================================================
set -uo pipefail

SCENARIO="mixed"; DURATION=60; CONCURRENCY=5; RPS=10; TID="valorant-lima-open"
TS_URL="${TOURNAMENT_URL:-}"; MM_URL="${MATCHMAKING_URL:-}"

usage() { sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; }

while getopts "s:d:c:r:i:T:M:h" opt; do
  case "$opt" in
    s) SCENARIO="$OPTARG" ;;  d) DURATION="$OPTARG" ;;  c) CONCURRENCY="$OPTARG" ;;
    r) RPS="$OPTARG" ;;       i) TID="$OPTARG" ;;       T) TS_URL="$OPTARG" ;;
    M) MM_URL="$OPTARG" ;;    h) usage; exit 0 ;;       *) usage; exit 1 ;;
  esac
done

case "$SCENARIO" in lobby|browse|register|mixed) ;; *) echo "Escenario inválido: $SCENARIO"; usage; exit 1 ;; esac

# Resolución de URLs: 1) parámetro/env  2) port-forward local  3) minikube ip + NodePort
resolve_url() { # $1=valor actual  $2=puerto port-forward  $3=nodePort
  if [[ -n "$1" ]]; then echo "$1"; return; fi
  if curl -s -o /dev/null --max-time 2 "http://localhost:$2/health"; then echo "http://localhost:$2"; return; fi
  if command -v minikube >/dev/null 2>&1; then
    local ip; ip="$(minikube ip 2>/dev/null || true)"
    [[ -n "$ip" ]] && { echo "http://${ip}:$3"; return; }
  fi
  echo ""
}
TS_URL="$(resolve_url "$TS_URL" 18081 30081)"
MM_URL="$(resolve_url "$MM_URL" 18082 30082)"
[[ -z "$TS_URL" || -z "$MM_URL" ]] && { echo "No pude resolver las URLs. Usa -T y -M (o ./scripts/port-forward.sh start)."; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
END=$(( $(date +%s) + DURATION ))
INTERVAL="$(awk -v c="$CONCURRENCY" -v r="$RPS" 'BEGIN{ if (r<=0) print 0; else printf "%.3f", c/r }')"

echo "== NexusPlay stress =="
echo "escenario=$SCENARIO duración=${DURATION}s workers=$CONCURRENCY rps≈$RPS torneo=$TID"
echo "tournament-service : $TS_URL"
echo "matchmaking-service: $MM_URL"

code() { curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$@" || true; }

do_register() {
  local mmr=$(( 900 + RANDOM % 1900 ))
  code -X POST "$MM_URL/api/teams" -H 'Content-Type: application/json' \
       -d "{\"name\":\"Team-$RANDOM-$RANDOM\",\"tournamentId\":\"$TID\",\"mmr\":$mmr}"
}

do_browse() { # $1 = índice
  case $(( $1 % 4 )) in
    0) code "$TS_URL/api/tournaments" ;;
    1) code "$TS_URL/api/tournaments/$TID" ;;
    2) code "$MM_URL/api/teams?tournamentId=$TID" ;;
    3) code "$MM_URL/api/tournaments/$TID/lobby" ;;
  esac
}

do_request() { # $1=escenario  $2=n
  case "$1" in
    lobby)    code "$MM_URL/api/tournaments/$TID/lobby" ;;
    browse)   do_browse "$2" ;;
    register) do_register ;;
    mixed)    if (( $2 % 5 == 0 )); then do_register; else do_browse "$2"; fi ;;
  esac
}

worker() {
  local id="$1" n=0
  while (( $(date +%s) < END )); do
    n=$(( n + 1 ))
    do_request "$SCENARIO" "$(( id * 100000 + n ))" >> "$TMP/worker_$id.log"
    echo >> "$TMP/worker_$id.log"
    [[ "$INTERVAL" != "0" ]] && sleep "$INTERVAL"
  done
}

for i in $(seq 1 "$CONCURRENCY"); do worker "$i" & done
trap 'kill $(jobs -p) 2>/dev/null' INT
wait

echo
echo "== Resultado (código HTTP -> cantidad) =="
cat "$TMP"/worker_*.log | grep -v '^$' | sort | uniq -c | sort -rn \
  | awk '{printf "  HTTP %s -> %s\n", $2, $1}'
total="$(cat "$TMP"/worker_*.log | grep -vc '^$')"
echo "  total peticiones: $total"
