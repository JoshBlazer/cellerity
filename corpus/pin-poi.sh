#!/usr/bin/env bash
# Regenerates corpus/manifest.tsv from Apache POI's test data at a pinned commit.
# Only needed to move the pin; normal use is corpus/download.sh.
#
# Usage: corpus/pin-poi.sh <poi-commit-sha>
set -euo pipefail

commit="${1:?usage: pin-poi.sh <poi-commit-sha>}"
root="$(cd "$(dirname "$0")" && pwd)"
manifest="$root/manifest.tsv"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

curl -fsSL "https://api.github.com/repos/apache/poi/git/trees/${commit}:test-data/spreadsheet" -o "$work/tree.json"
grep -o '"path": *"[^"]*\.xlsx"' "$work/tree.json" | sed 's/.*"\([^"]*\)"$/\1/' | LC_ALL=C sort > "$work/files.txt"

base="https://raw.githubusercontent.com/apache/poi/${commit}/test-data/spreadsheet"
printf 'sha256\tbytes\tlicense\tsource_url\tpath\n' > "$manifest"
while IFS= read -r name; do
  url="$base/$name"
  curl -fsSL "$url" -o "$work/file"
  printf '%s\t%s\tApache-2.0\t%s\tpoi/%s\n' "$(sha256 "$work/file")" "$(wc -c < "$work/file" | tr -d ' ')" "$url" "$name" >> "$manifest"
done < "$work/files.txt"

echo "Pinned $(($(wc -l < "$manifest") - 1)) files at apache/poi@$commit"
