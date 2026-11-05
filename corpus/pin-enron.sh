#!/usr/bin/env bash
# Regenerates corpus/enron-manifest.tsv from the cleaned Nuix subset of SheetJS/enron_xls (CC0).
# The uncleaned edrm/ directory contains personal information and is never fetched (ADR-0013).
#
# Usage: corpus/pin-enron.sh <enron_xls-commit-sha>
set -euo pipefail

commit="${1:?usage: pin-enron.sh <enron_xls-commit-sha>}"
root="$(cd "$(dirname "$0")" && pwd)"
manifest="$root/enron-manifest.tsv"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
encode() { printf '%s' "$1" | sed -e 's/%/%25/g' -e 's/ /%20/g' -e 's/#/%23/g' -e 's/\[/%5B/g' -e 's/\]/%5D/g'; }

curl -fsSL "https://api.github.com/repos/SheetJS/enron_xls/git/trees/${commit}?recursive=1" -o "$work/tree.json"
grep -o '"path": *"nuix/[^"]*"' "$work/tree.json" | sed 's/.*"\(nuix\/[^"]*\)"$/\1/' | grep -iE '\.xls$' | LC_ALL=C sort > "$work/files.txt"

base="https://raw.githubusercontent.com/SheetJS/enron_xls/${commit}"
n=0
: > "$work/curl.cfg"
while IFS= read -r name; do
  n=$((n + 1))
  printf 'url = "%s/%s"\noutput = "%d.bin"\n' "$base" "$(encode "$name")" "$n" >> "$work/curl.cfg"
done < "$work/files.txt"
(cd "$work" && curl -fsSL --retry 3 --parallel --parallel-max 16 -K curl.cfg)

printf 'sha256\tbytes\tlicense\tsource_url\tpath\n' > "$manifest"
n=0
while IFS= read -r name; do
  n=$((n + 1))
  file="$work/$n.bin"
  printf '%s\t%s\tCC0-1.0\t%s/%s\tenron-%s\n' "$(sha256 "$file")" "$(wc -c < "$file" | tr -d ' ')" "$base" "$(encode "$name")" "$name" >> "$manifest"
done < "$work/files.txt"

echo "Pinned $n files at SheetJS/enron_xls@$commit"
