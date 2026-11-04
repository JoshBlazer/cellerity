#!/usr/bin/env bash
# Downloads every file in corpus/manifest.tsv into corpus/downloads/ and verifies its checksum.
# Files that already verify are skipped. A file that fails verification is deleted, and the script
# exits non-zero. Downloaded files are data only: nothing in them is ever executed.
set -euo pipefail

root="$(cd "$(dirname "$0")" && pwd)"
dest="$root/downloads"
mkdir -p "$dest"

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

fetched=0 present=0 failed=0
while IFS=$'\t' read -r sum _bytes _license url path; do
  target="$dest/$path"
  if [ -f "$target" ] && [ "$(sha256 "$target")" = "$sum" ]; then
    present=$((present + 1))
    continue
  fi
  mkdir -p "$(dirname "$target")"
  if curl -fsSL --retry 3 "$url" -o "$target" && [ "$(sha256 "$target")" = "$sum" ]; then
    fetched=$((fetched + 1))
  else
    echo "FAILED: $path" >&2
    rm -f "$target"
    failed=$((failed + 1))
  fi
done < <(tail -n +2 "$root/manifest.tsv" | tr -d '\r')

echo "corpus: $present already present, $fetched downloaded, $failed failed"
[ "$failed" -eq 0 ]
