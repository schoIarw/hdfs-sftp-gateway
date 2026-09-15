#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${HFG_GATEWAY_ENV_FILE:-/etc/hfg/hfg-gateway.env}"

read_port() {
  local name="$1"
  local fallback="$2"
  local value=""
  if [[ -r "${ENV_FILE}" ]]; then
    value="$(awk -F= -v key="${name}" '$1 == key {sub(/^[^=]*=/, ""); gsub(/[[:space:]\"]/, ""); print; exit}' "${ENV_FILE}")"
  fi
  printf '%s' "${value:-${fallback}}"
}

FTP_PORT="$(read_port HFG_FTP_PORT 21)"
SFTP_PORT="$(read_port HFG_SFTP_PORT 22)"
systemctl is-active --quiet hfg-gateway
ss -H -ltn | awk '{print $4}' | grep -Eq ":(${FTP_PORT}|${SFTP_PORT})$"
curl --fail --silent --max-time 1 http://127.0.0.1:18080/actuator/health/readiness >/dev/null
