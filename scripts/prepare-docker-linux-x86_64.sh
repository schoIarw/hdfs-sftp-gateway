#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-0.1.9}"
ARCH="$(uname -m)"
MEDIA_NAME="hfg-${VERSION}-linux-x86_64-docker"
DIST_DIR="${ROOT_DIR}/dist"
STAGE_DIR="${DIST_DIR}/${MEDIA_NAME}"

if [[ "${ARCH}" != "x86_64" ]]; then
  echo "This release must be assembled on x86_64; detected ${ARCH}." >&2
  exit 1
fi

MANAGER_JAR="${ROOT_DIR}/hfg-manager-api/target/hfg-manager-api-${VERSION}.jar"
GATEWAY_JAR="${ROOT_DIR}/hfg-gateway-app/target/hfg-gateway-app-${VERSION}.jar"
BOOTSTRAP_JAR="${ROOT_DIR}/hfg-common-contract/target/hfg-common-contract-${VERSION}-bootstrap.jar"
SBOM="${ROOT_DIR}/target/bom.json"
for required in "${MANAGER_JAR}" "${GATEWAY_JAR}" "${BOOTSTRAP_JAR}" "${SBOM}"; do
  if [[ ! -f "${required}" ]]; then
    echo "Missing build output: ${required}" >&2
    exit 1
  fi
done

case "${STAGE_DIR}" in
  "${DIST_DIR}"/hfg-*-linux-x86_64-docker) ;;
  *) echo "Unsafe staging path: ${STAGE_DIR}" >&2; exit 1 ;;
esac

rm -rf "${STAGE_DIR}"
install -d "${STAGE_DIR}/build/bin" "${STAGE_DIR}/build/runtime" "${STAGE_DIR}/config" \
  "${STAGE_DIR}/docker" "${STAGE_DIR}/images" "${STAGE_DIR}/keepalived" \
  "${STAGE_DIR}/prometheus" "${STAGE_DIR}/tools" "${STAGE_DIR}/docs"
install -m 0644 "${MANAGER_JAR}" "${STAGE_DIR}/build/bin/hfg-manager.jar"
install -m 0644 "${GATEWAY_JAR}" "${STAGE_DIR}/build/bin/hfg-gateway.jar"
install -m 0644 "${ROOT_DIR}/deploy/docker/runtime/Dockerfile.manager" "${STAGE_DIR}/build/runtime/"
install -m 0644 "${ROOT_DIR}/deploy/docker/runtime/Dockerfile.gateway" "${STAGE_DIR}/build/runtime/"
install -m 0644 "${BOOTSTRAP_JAR}" "${STAGE_DIR}/tools/hfg-bootstrap.jar"
install -m 0644 "${SBOM}" "${STAGE_DIR}/SBOM.cyclonedx.json"
install -m 0644 "${ROOT_DIR}/deploy/env/hfg-manager.env.example" "${STAGE_DIR}/config/"
install -m 0644 "${ROOT_DIR}/deploy/env/hfg-gateway.env.example" "${STAGE_DIR}/config/"
install -m 0644 "${ROOT_DIR}/deploy/docker/package/compose.postgresql.yaml" "${STAGE_DIR}/docker/"
install -m 0644 "${ROOT_DIR}/deploy/docker/package/compose.mysql.yaml" "${STAGE_DIR}/docker/"
install -m 0644 "${ROOT_DIR}/deploy/docker/package/compose.gateway.yaml" "${STAGE_DIR}/docker/"
install -m 0755 "${ROOT_DIR}/deploy/keepalived/hfg-gateway-health.sh" "${STAGE_DIR}/keepalived/"
install -m 0755 "${ROOT_DIR}/deploy/keepalived/hfg-role-change.sh" "${STAGE_DIR}/keepalived/"
install -m 0644 "${ROOT_DIR}/deploy/keepalived/keepalived.conf.template" "${STAGE_DIR}/keepalived/"
install -m 0644 "${ROOT_DIR}/deploy/prometheus/prometheus.yaml" "${STAGE_DIR}/prometheus/"
install -m 0644 "${ROOT_DIR}/deploy/prometheus/hfg-rules.yaml" "${STAGE_DIR}/prometheus/"
install -m 0644 "${ROOT_DIR}/docs/package-deployment.md" "${STAGE_DIR}/docs/"
install -m 0644 "${ROOT_DIR}/docs/deployment.md" "${STAGE_DIR}/docs/"
install -m 0644 "${ROOT_DIR}/docs/configuration.md" "${STAGE_DIR}/docs/"
install -m 0644 "${ROOT_DIR}/docs/testing.md" "${STAGE_DIR}/docs/"
install -m 0644 "${ROOT_DIR}/docs/release-notes-v${VERSION}.md" "${STAGE_DIR}/docs/"

cat > "${STAGE_DIR}/README.txt" <<EOF
HFG ${VERSION} Linux x86_64 Docker deployment medium

Start here: docs/package-deployment.md, section "Docker 部署"
Images: images/hfg-images.tar (docker load -i images/hfg-images.tar)
        If the tar is missing, run ./build-images.sh on a host with Docker.
Compose files: docker/

This archive contains no Native Manager/Gateway executable JARs.
Use hfg-${VERSION}-linux-x86_64-native.tar.gz for Native deployment.
EOF

cat > "${STAGE_DIR}/build-images.sh" <<EOF
#!/usr/bin/env bash
# Builds the HFG images from this medium and stores them in images/hfg-images.tar.
set -euo pipefail
cd "\$(dirname "\${BASH_SOURCE[0]}")"
VERSION="\${1:-${VERSION}}"
docker build -f build/runtime/Dockerfile.manager -t "hfg-manager:\${VERSION}" build
docker build -f build/runtime/Dockerfile.gateway -t "hfg-gateway:\${VERSION}" build
docker save -o images/hfg-images.tar "hfg-manager:\${VERSION}" "hfg-gateway:\${VERSION}"
echo "images/hfg-images.tar created for \${VERSION}"
EOF
chmod 0755 "${STAGE_DIR}/build-images.sh"

SOURCE_EPOCH="${SOURCE_DATE_EPOCH:-$(git -C "${ROOT_DIR}" log -1 --format=%ct 2>/dev/null || date +%s)}"
ARCHIVE="${DIST_DIR}/${MEDIA_NAME}.tar.gz"
mkdir -p "${DIST_DIR}"
# GNU tar before 1.28 (CentOS 7) has no --sort/--mtime support.
if tar --help 2>/dev/null | grep -q -- '--sort'; then
  tar --sort=name --mtime="@${SOURCE_EPOCH}" --owner=0 --group=0 --numeric-owner \
    -C "${DIST_DIR}" -czf "${ARCHIVE}" "${MEDIA_NAME}"
else
  tar -C "${DIST_DIR}" -czf "${ARCHIVE}" "${MEDIA_NAME}"
fi
sha256sum "${ARCHIVE}" > "${ARCHIVE}.sha256"
echo "${ARCHIVE}"
