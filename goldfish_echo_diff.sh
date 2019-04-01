#!/usr/bin/env bash

set -euo pipefail

readonly my_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

groovy -cp "${my_dir}/src/" "${my_dir}/groovy_scripts/goldfish_echo_diff.groovy" "$@"
