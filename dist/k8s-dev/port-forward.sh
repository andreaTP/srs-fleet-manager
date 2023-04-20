#! /bin/bash
set -euxo pipefail

(kubectl port-forward service/registry-app 8082:8080) & PID_PF1=$!
(kubectl port-forward service/keycloak 8083:8080) & PID_PF2=$!

trap "kill -9 $PID_PF1 && kill -9 $PID_PF2" SIGTERM SIGINT

wait $PID_PF1 $PID_PF2
