#!/usr/bin/env sh
set -eu

PACKAGE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
COMPOSE_FILE="$PACKAGE_ROOT/docker-compose.yml"
ENV_FILE="$PACKAGE_ROOT/.env"

check_prerequisites() {
  command -v docker >/dev/null 2>&1 || { echo "Docker를 찾을 수 없습니다." >&2; exit 1; }
  docker compose version >/dev/null 2>&1 || { echo "Docker Compose v2를 사용할 수 없습니다." >&2; exit 1; }
  [ -f "$ENV_FILE" ] || { echo ".env.example을 .env로 복사하고 설정하세요." >&2; exit 1; }
  ! grep -q 'CHANGE_ME' "$ENV_FILE" || { echo ".env의 CHANGE_ME 값을 모두 변경하세요." >&2; exit 1; }
}

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

