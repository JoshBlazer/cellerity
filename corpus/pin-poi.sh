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
# Part names may contain spaces and other characters that are not valid in a URL.
encode() { printf '%s' "$1" | sed -e 's/%/%25/g' -e 's/ /%20/g' -e 's/#/%23/g' -e 's/\[/%5B/g' -e 's/\]/%5D/g'; }

curl -fsSL "https://api.github.com/repos/apache/poi/git/trees/${commit}:test-data/spreadsheet" -o "$work/tree.json"
grep -o '"path": *"[^"]*\.xlsx"' "$work/tree.json" | sed 's/.*"\([^"]*\)"$/\1/' | LC_ALL=C sort > "$work/files.txt"

base="https://raw.githubusercontent.com/apache/poi/${commit}/test-data/spreadsheet"
n=0
: > "$work/curl.cfg"
while IFS= read -r name; do
  n=$((n + 1))
  printf 'url = "%s/%s"\noutput = "%d.bin"\n' "$base" "$(encode "$name")" "$n" >> "$work/curl.cfg"
done < "$work/files.txt"
# Relative output paths, because a native Windows curl cannot resolve POSIX paths in a config file.
(cd "$work" && curl -fsSL --retry 3 --parallel --parallel-max 16 -K curl.cfg)

printf 'sha256\tbytes\tlicense\tsource_url\tpath\n' > "$manifest"
n=0
while IFS= read -r name; do
  n=$((n + 1))
  file="$work/$n.bin"
  printf '%s\t%s\tApache-2.0\t%s/%s\tpoi/%s\n' "$(sha256 "$file")" "$(wc -c < "$file" | tr -d ' ')" "$base" "$(encode "$name")" "$name" >> "$manifest"
done < "$work/files.txt"

echo "Pinned $n files at apache/poi@$commit"
