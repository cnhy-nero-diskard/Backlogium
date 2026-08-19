#!/usr/bin/env bash
set -euo pipefail

tag="${1:-}"
if [[ ! "$tag" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "tag must be vX.Y.Z" >&2
  exit 2
fi

major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
patch="${BASH_REMATCH[3]}"
for component in "$major" "$minor" "$patch"; do
  if (( 10#$component >= 1000 )); then
    echo "version components must be below 1000" >&2
    exit 2
  fi
done

printf '%s\n' "$((10#$major * 1000000 + 10#$minor * 1000 + 10#$patch))"
