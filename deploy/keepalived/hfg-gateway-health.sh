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

read_value() {
  local name="$1"
  local fallback="$2"
  local value=""
  if [[ -r "${ENV_FILE}" ]]; then
    value="$(awk -F= -v key="${name}" '$1 == key {sub(/^[^=]*=/, ""); gsub(/^[[:space:]\"]+|[[:space:]\"]+$/, ""); print; exit}' "${ENV_FILE}")"
  fi
  printf '%s' "${value:-${fallback}}"
}

gateway_running() {
  if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet hfg-gateway; then
    return 0
  fi
  if ! command -v docker >/dev/null 2>&1; then
    return 1
  fi
  if [[ "$(docker inspect --format '{{.State.Running}}' hfg-gateway 2>/dev/null || true)" == "true" ]]; then
    return 0
  fi
  [[ -n "$(docker ps --quiet --filter status=running \
    --filter label=com.docker.compose.service=hfg-gateway 2>/dev/null)" ]]
}

port_listening() {
  local port="$1"
  ss -H -ltn | awk '{print $4}' | grep -Eq ":${port}$"
}

FTP_PORT="$(read_port HFG_FTP_PORT 21)"
SFTP_PORT="$(read_port HFG_SFTP_PORT 22)"
MANAGEMENT_PORT="$(read_port HFG_MANAGEMENT_PORT 18080)"
MANAGEMENT_BIND="$(read_value HFG_MANAGEMENT_BIND 127.0.0.1)"
FTP_ENABLED="$(read_value HFG_FTP_ENABLED true)"
SFTP_ENABLED="$(read_value HFG_SFTP_ENABLED true)"

gateway_running
if [[ "${FTP_ENABLED,,}" == "true" ]]; then
  port_listening "${FTP_PORT}"
fi
if [[ "${SFTP_ENABLED,,}" == "true" ]]; then
  port_listening "${SFTP_PORT}"
fi
if [[ "${FTP_ENABLED,,}" != "true" && "${SFTP_ENABLED,,}" != "true" ]]; then
  exit 1
fi
case "${MANAGEMENT_BIND}" in
  0.0.0.0|::|'[::]') MANAGEMENT_BIND=127.0.0.1 ;;
esac
if [[ "${MANAGEMENT_BIND}" == *:* && "${MANAGEMENT_BIND}" != \[*\] ]]; then
  MANAGEMENT_BIND="[${MANAGEMENT_BIND}]"
fi
curl --fail --silent --max-time 1 \
  "http://${MANAGEMENT_BIND}:${MANAGEMENT_PORT}/actuator/health/readiness" >/dev/null
