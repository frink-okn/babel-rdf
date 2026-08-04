#!/usr/bin/env bash
set -euo pipefail

if (( $# < 3 )); then
  echo "usage: $0 URL TARGET COMPRESSOR [COMPRESSOR-ARG ...]" >&2
  exit 2
fi

url=$1
target=$2
shift 2

raw="${target}.download"
download_complete="${raw}.complete"
compressed_part="${target}.part"

cleanup_partial() {
  status=$?
  if (( status != 0 )); then
    rm -f "$compressed_part"
  fi
  if [[ -e "$target" ]]; then
    rm -f "$raw" "$download_complete"
  fi
  exit "$status"
}
trap cleanup_partial EXIT

if [[ ! -e "$download_complete" ]]; then
  curl \
    --fail \
    --location \
    --retry 8 \
    --retry-all-errors \
    --continue-at - \
    --output "$raw" \
    "$url"
  touch "$download_complete"
fi

"$@" -n -c "$raw" > "$compressed_part"
mv "$compressed_part" "$target"
rm -f "$raw" "$download_complete"
trap - EXIT
