#!/usr/bin/env bash
# Test de fumée d'un déploiement Kubernetes : interroge le moniteur et vérifie que les cinq
# philosophes mangent, que le nombre de repas augmente et qu'aucune violation n'est détectée.
set -euo pipefail

NAMESPACE="${NAMESPACE:-dining}"
PORT="${PORT:-18090}"

kubectl -n "$NAMESPACE" port-forward svc/monitor "$PORT:8090" >/dev/null 2>&1 &
FORWARD_PID=$!
trap 'kill $FORWARD_PID 2>/dev/null || true' EXIT

snapshot() {
  curl -fsS "http://localhost:$PORT/api/snapshot"
}

echo "Attente du moniteur..."
for attempt in $(seq 1 30); do
  if snapshot >/dev/null 2>&1; then break; fi
  sleep 2
done

echo "Attente de l'activité des 5 philosophes..."
for attempt in $(seq 1 60); do
  SNAPSHOT="$(snapshot || echo '{}')"
  PHILOSOPHERS="$(echo "$SNAPSHOT" | jq '[.philosophers[]? | select(.meals > 0)] | length')"
  if [ "$PHILOSOPHERS" = "5" ]; then break; fi
  sleep 3
done

BEFORE="$(snapshot | jq '.totalMeals')"
sleep 10
SNAPSHOT="$(snapshot)"
AFTER="$(echo "$SNAPSHOT" | jq '.totalMeals')"
VIOLATIONS="$(echo "$SNAPSHOT" | jq '.violationCount')"
ACTIVE="$(echo "$SNAPSHOT" | jq '[.philosophers[] | select(.meals > 0)] | length')"

echo "Philosophes actifs : $ACTIVE / 5 ; repas : $BEFORE puis $AFTER ; violations : $VIOLATIONS"
[ "$ACTIVE" = "5" ] || { echo "ECHEC : tous les philosophes ne mangent pas"; exit 1; }
[ "$AFTER" -gt "$BEFORE" ] || { echo "ECHEC : le nombre de repas n'augmente plus"; exit 1; }
[ "$VIOLATIONS" = "0" ] || { echo "ECHEC : exclusion mutuelle violée"; exit 1; }
echo "OK"
