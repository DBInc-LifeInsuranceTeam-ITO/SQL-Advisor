#!/usr/bin/env sh
set -eu
. "$(dirname "$0")/common.sh"
check_prerequisites

found=false
for archive in "$PACKAGE_ROOT"/images/*.tar; do
  [ -f "$archive" ] || continue
  found=true
  echo "Docker 이미지 불러오는 중: $(basename "$archive")"
  docker load -i "$archive"
done
[ "$found" = true ] || { echo "images 디렉터리에 Docker 이미지 TAR 파일이 없습니다." >&2; exit 1; }

compose up -d
compose ps
echo "SQLAdvisor 설치가 완료되었습니다."

