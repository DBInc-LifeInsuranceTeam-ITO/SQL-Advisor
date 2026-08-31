#!/usr/bin/env sh
set -eu
. "$(dirname "$0")/common.sh"
check_prerequisites
if [ "$#" -gt 0 ]; then compose logs --tail 200 -f "$1"; else compose logs --tail 200 -f; fi

