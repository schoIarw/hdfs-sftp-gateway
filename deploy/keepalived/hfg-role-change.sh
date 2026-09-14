#!/usr/bin/env bash
set -euo pipefail
ROLE=${1:?role required}
install -d -o hfg -g hfg /var/lib/hfg
printf '%s\n' "$ROLE" > /var/lib/hfg/role
logger -t hfg-keepalived "role changed to $ROLE"
