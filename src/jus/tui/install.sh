#!/usr/bin/env bash

set -euo pipefail

# Download completely before sourcing, so a failed fetch cannot look successful.
installer=$(curl -fsSL https://in-1.cc)
# shellcheck disable=SC1091
source /dev/stdin --local in-1 "$@" <<< "$installer"
