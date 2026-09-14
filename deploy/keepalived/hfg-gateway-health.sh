#!/usr/bin/env bash
set -euo pipefail
systemctl is-active --quiet hfg-gateway
ss -H -ltn | awk '{print $4}' | grep -Eq ':(21|22)$'
curl --fail --silent --max-time 1 http://127.0.0.1:18080/actuator/health/readiness >/dev/null
