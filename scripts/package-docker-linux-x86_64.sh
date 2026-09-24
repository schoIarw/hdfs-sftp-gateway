#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-0.1.13}"
ARCH="$(uname -m)"
MEDIA_NAME="hfg-${VERSION}-linux-x86_64-docker"
DIST_DIR="${ROOT_DIR}/dist"
STAGE_DIR="${DIST_DIR}/${MEDIA_NAME}"
IMAGE_TAR="${STAGE_DIR}/images/hfg-images.tar"

if [[ "${ARCH}" != "x86_64" ]]; then
  echo "This release must be assembled on x86_64; detected ${ARCH}." >&2
  exit 1
fi

case "${STAGE_DIR}" in
  "${DIST_DIR}"/hfg-*-linux-x86_64-docker) ;;
  *) echo "Unsafe staging path: ${STAGE_DIR}" >&2; exit 1 ;;
esac
test -d "${STAGE_DIR}/build"
docker image inspect "hfg-manager:${VERSION}" >/dev/null
docker image inspect "hfg-gateway:${VERSION}" >/dev/null
docker save --output "${IMAGE_TAR}" "hfg-manager:${VERSION}" "hfg-gateway:${VERSION}"
rm -rf "${STAGE_DIR}/build"

BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
GIT_COMMIT="$(git -C "${ROOT_DIR}" rev-parse HEAD 2>/dev/null || echo unknown)"
cat > "${STAGE_DIR}/RELEASE-INFO.txt" <<EOF
HFG version: ${VERSION}
Platform: Linux x86_64 (amd64)
Medium: Docker
Git commit: ${GIT_COMMIT}
Build time (UTC): ${BUILD_TIME}
Images: hfg-manager:${VERSION}, hfg-gateway:${VERSION}
EOF

find "${STAGE_DIR}" -type d -exec chmod 0755 {} +
find "${STAGE_DIR}" -type f ! -path '*/keepalived/*.sh' -exec chmod 0644 {} +
SOURCE_EPOCH="${SOURCE_DATE_EPOCH:-$(git -C "${ROOT_DIR}" log -1 --format=%ct 2>/dev/null || date +%s)}"
ARCHIVE="${DIST_DIR}/${MEDIA_NAME}.tar.gz"
tar --sort=name --mtime="@${SOURCE_EPOCH}" --owner=0 --group=0 --numeric-owner \
  -C "${DIST_DIR}" -czf "${ARCHIVE}" "${MEDIA_NAME}"
sha256sum "${ARCHIVE}" > "${ARCHIVE}.sha256"
echo "${ARCHIVE}"
