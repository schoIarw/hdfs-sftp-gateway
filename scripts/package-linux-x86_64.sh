#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-0.1.1}"
ARCH="$(uname -m)"
MEDIA_NAME="hfg-${VERSION}-linux-x86_64"
DIST_DIR="${ROOT_DIR}/dist"
STAGE_DIR="${DIST_DIR}/${MEDIA_NAME}"

if [[ "${ARCH}" != "x86_64" ]]; then
  echo "This release must be assembled on x86_64; detected ${ARCH}." >&2
  exit 1
fi

MANAGER_JAR="${ROOT_DIR}/hfg-manager-api/target/hfg-manager-api-${VERSION}.jar"
GATEWAY_JAR="${ROOT_DIR}/hfg-gateway-app/target/hfg-gateway-app-${VERSION}.jar"
KEYTOOL_JAR="${ROOT_DIR}/hfg-common-contract/target/hfg-common-contract-${VERSION}.jar"
SBOM="${ROOT_DIR}/target/bom.json"
for required in "${MANAGER_JAR}" "${GATEWAY_JAR}" "${KEYTOOL_JAR}" "${SBOM}"; do
  if [[ ! -f "${required}" ]]; then
    echo "Missing build output: ${required}" >&2
    echo "Run 'make package' before assembling the release." >&2
    exit 1
  fi
done

case "${STAGE_DIR}" in
  "${DIST_DIR}"/hfg-*-linux-x86_64) ;;
  *) echo "Unsafe staging path: ${STAGE_DIR}" >&2; exit 1 ;;
esac

rm -rf "${STAGE_DIR}"
install -d "${STAGE_DIR}/bin" "${STAGE_DIR}/config" "${STAGE_DIR}/systemd/centos7" \
  "${STAGE_DIR}/keepalived" "${STAGE_DIR}/prometheus" "${STAGE_DIR}/docker" "${STAGE_DIR}/docs"

install -m 0644 "${MANAGER_JAR}" "${STAGE_DIR}/bin/hfg-manager.jar"
install -m 0644 "${GATEWAY_JAR}" "${STAGE_DIR}/bin/hfg-gateway.jar"
install -m 0644 "${KEYTOOL_JAR}" "${STAGE_DIR}/bin/hfg-keytool.jar"
install -m 0644 "${SBOM}" "${STAGE_DIR}/SBOM.cyclonedx.json"
install -m 0644 "${ROOT_DIR}/deploy/env/hfg-manager.env.example" "${STAGE_DIR}/config/"
install -m 0644 "${ROOT_DIR}/deploy/env/hfg-gateway.env.example" "${STAGE_DIR}/config/"
install -m 0644 "${ROOT_DIR}/deploy/systemd/hfg-manager.service" "${STAGE_DIR}/systemd/"
install -m 0644 "${ROOT_DIR}/deploy/systemd/hfg-gateway.service" "${STAGE_DIR}/systemd/"
install -m 0644 "${ROOT_DIR}/deploy/systemd/centos7/hfg-manager.service" "${STAGE_DIR}/systemd/centos7/"
install -m 0644 "${ROOT_DIR}/deploy/systemd/centos7/hfg-gateway.service" "${STAGE_DIR}/systemd/centos7/"
install -m 0755 "${ROOT_DIR}/deploy/keepalived/hfg-gateway-health.sh" "${STAGE_DIR}/keepalived/"
install -m 0755 "${ROOT_DIR}/deploy/keepalived/hfg-role-change.sh" "${STAGE_DIR}/keepalived/"
install -m 0644 "${ROOT_DIR}/deploy/keepalived/keepalived.conf.template" "${STAGE_DIR}/keepalived/"
install -m 0644 "${ROOT_DIR}/deploy/prometheus/prometheus.yaml" "${STAGE_DIR}/prometheus/"
install -m 0644 "${ROOT_DIR}/deploy/prometheus/hfg-rules.yaml" "${STAGE_DIR}/prometheus/"
install -m 0644 "${ROOT_DIR}/deploy/docker/runtime/Dockerfile.manager" "${STAGE_DIR}/docker/"
install -m 0644 "${ROOT_DIR}/deploy/docker/runtime/Dockerfile.gateway" "${STAGE_DIR}/docker/"
install -m 0644 "${ROOT_DIR}/deploy/docker/package/compose.postgresql.yaml" "${STAGE_DIR}/docker/"
install -m 0644 "${ROOT_DIR}/deploy/docker/package/compose.mysql.yaml" "${STAGE_DIR}/docker/"
install -m 0644 "${ROOT_DIR}/deploy/docker/package/compose.gateway.yaml" "${STAGE_DIR}/docker/"
install -m 0644 "${ROOT_DIR}/docs/package-deployment.md" "${STAGE_DIR}/docs/"
install -m 0644 "${ROOT_DIR}/docs/deployment.md" "${STAGE_DIR}/docs/"
install -m 0644 "${ROOT_DIR}/docs/configuration.md" "${STAGE_DIR}/docs/"
install -m 0644 "${ROOT_DIR}/docs/testing.md" "${STAGE_DIR}/docs/"

BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
GIT_COMMIT="$(git -C "${ROOT_DIR}" rev-parse HEAD 2>/dev/null || echo unknown)"
JAVA_BUILD="$(java -version 2>&1 | head -n 1)"
cat > "${STAGE_DIR}/RELEASE-INFO.txt" <<EOF
HFG version: ${VERSION}
Platform: Linux x86_64 (amd64)
Git commit: ${GIT_COMMIT}
Build time (UTC): ${BUILD_TIME}
Build Java: ${JAVA_BUILD}
Runtime Java: 17
EOF

cat > "${STAGE_DIR}/README.txt" <<EOF
HFG ${VERSION} Linux x86_64 deployment medium

Start here: docs/package-deployment.md
Manager executable: bin/hfg-manager.jar
Gateway executable: bin/hfg-gateway.jar
Snapshot key generator: bin/hfg-keytool.jar

The Docker image archive is distributed as a separate release asset:
hfg-${VERSION}-linux-x86_64-docker-images.tar.gz
EOF

find "${STAGE_DIR}" -type d -exec chmod 0755 {} +
find "${STAGE_DIR}" -type f ! -path '*/keepalived/*.sh' -exec chmod 0644 {} +

SOURCE_EPOCH="${SOURCE_DATE_EPOCH:-$(git -C "${ROOT_DIR}" log -1 --format=%ct 2>/dev/null || date +%s)}"
ARCHIVE="${DIST_DIR}/${MEDIA_NAME}.tar.gz"
mkdir -p "${DIST_DIR}"
tar --sort=name --mtime="@${SOURCE_EPOCH}" --owner=0 --group=0 --numeric-owner \
  -C "${DIST_DIR}" -czf "${ARCHIVE}" "${MEDIA_NAME}"
sha256sum "${ARCHIVE}" > "${ARCHIVE}.sha256"
echo "${ARCHIVE}"
