#!/usr/bin/env bash
# Downloads every file in corpus/manifest.tsv into corpus/downloads/ and verifies its checksum.
# Files that already verify are skipped. A file that fails verification is deleted, and the script
# exits non-zero. Downloaded files are data only: nothing in them is ever executed.
set -euo pipefail

root="$(cd "$(dirname "$0")" && pwd)"
dest="$root/downloads"
# --verify-only checks files without fetching or deleting anything.
verify_only=false
[ "${1:-}" = "--verify-only" ] && verify_only=true
mkdir -p "$dest"

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
tail -n +2 "$root/manifest.tsv" | tr -d '\r' > "$work/entries.tsv"

present=0
: > "$work/curl.cfg"
while IFS=$'\t' read -r sum _bytes _license url path; do
  target="$dest/$path"
  if [ -f "$target" ] && [ "$(sha256 "$target")" = "$sum" ]; then
    present=$((present + 1))
  else
    printf 'url = "%s"\noutput = "%s"\n' "$url" "$path" >> "$work/curl.cfg"
  fi
done < "$work/entries.tsv"

if [ -s "$work/curl.cfg" ] && [ "$verify_only" = false ]; then
  # A failed fetch shows up in the checksum pass below, so curl's own status is not fatal here.
  # Relative output paths, because a native Windows curl cannot resolve POSIX paths in a config file.
  cp "$work/curl.cfg" "$dest/.curl.cfg"
  (cd "$dest" && curl -sSL --create-dirs --fail --retry 3 --parallel --parallel-max 16 -K .curl.cfg) || true
  rm -f "$dest/.curl.cfg"
fi

verified=0 failed=0
while IFS=$'\t' read -r sum _bytes _license _url path; do
  target="$dest/$path"
  if [ -f "$target" ] && [ "$(sha256 "$target")" = "$sum" ]; then
    verified=$((verified + 1))
  else
    echo "FAILED: $path" >&2
    [ "$verify_only" = true ] || rm -f "$target"
    failed=$((failed + 1))
  fi
done < "$work/entries.tsv"

echo "corpus: $verified verified ($present already present), $failed failed"
[ "$failed" -eq 0 ]
