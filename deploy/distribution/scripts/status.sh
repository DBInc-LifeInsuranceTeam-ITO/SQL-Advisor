#!/usr/bin/env sh
set -eu
. "$(dirname "$0")/common.sh"
check_prerequisites
compose ps

