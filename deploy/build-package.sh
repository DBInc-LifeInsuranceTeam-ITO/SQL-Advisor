#!/usr/bin/env bash
set -euo pipefail

VERSION=${1:-}
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "사용법: ./deploy/build-package.sh 1.0.0 [출력 디렉터리]" >&2
  exit 1
fi

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEPLOY_ROOT="$REPO_ROOT/deploy"
OUTPUT_ROOT="${2:-$REPO_ROOT/dist}"
PACKAGE_NAME="SQLAdvisor-$VERSION"
PACKAGE_ROOT="$OUTPUT_ROOT/$PACKAGE_NAME"
ARCHIVE_PATH="$OUTPUT_ROOT/$PACKAGE_NAME.tar.gz"

rm -rf "$PACKAGE_ROOT"
rm -f "$ARCHIVE_PATH"
mkdir -p "$PACKAGE_ROOT/images" "$PACKAGE_ROOT/config" "$PACKAGE_ROOT/nginx"
cp -R "$DEPLOY_ROOT/distribution/." "$PACKAGE_ROOT/"
cp "$DEPLOY_ROOT/config/application-prod.yml" "$DEPLOY_ROOT/config/logback-spring.xml" "$PACKAGE_ROOT/config/"
cp "$DEPLOY_ROOT/nginx/prod.conf" "$PACKAGE_ROOT/nginx/"
printf '%s\n' "$VERSION" > "$PACKAGE_ROOT/VERSION"
sed -i "s/^SQLADVISOR_VERSION=.*/SQLADVISOR_VERSION=$VERSION/" "$PACKAGE_ROOT/.env.example"
sed -i "s/^# SQLAdvisor 1.0.0$/# SQLAdvisor $VERSION/" "$PACKAGE_ROOT/RELEASE_NOTES.md"

docker build -t "sqladvisor-api:$VERSION" "$REPO_ROOT/sqladvisor"
docker build -t "sqladvisor-web:$VERSION" "$REPO_ROOT/frontend"
docker build -t "sqladvisor-worker:$VERSION" "$REPO_ROOT/worker"
docker build -t "sqladvisor-postgres:$VERSION" -f "$DEPLOY_ROOT/postgres.distribution.Dockerfile" "$REPO_ROOT/sqladvisor/init-db/postgres"
docker pull redis:7-alpine

IMAGE_TAR="$PACKAGE_ROOT/images/sqladvisor-images-$VERSION.tar"
docker save -o "$IMAGE_TAR" "sqladvisor-api:$VERSION" "sqladvisor-web:$VERSION" "sqladvisor-worker:$VERSION" "sqladvisor-postgres:$VERSION" redis:7-alpine
(cd "$PACKAGE_ROOT" && sha256sum "images/sqladvisor-images-$VERSION.tar" > SHA256SUMS)
(cd "$OUTPUT_ROOT" && tar -czf "$ARCHIVE_PATH" "$PACKAGE_NAME")
echo "완료: $ARCHIVE_PATH"
