#!/usr/bin/env bash
# Executa o benchmark dos três perfis (DVD e CD), com verificação.
# Uso: tests/bench/run_bench.sh <dir-build-host> <dir-imagens> [saida.jsonl]
set -euo pipefail
B="$(cd "$1" && pwd)"; IMG="$(cd "$2" && pwd)"; OUT="${3:-resultados.jsonl}"
HERE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
DS=$(stat -c%s "$IMG/dvd.iso"); CS=$(cat "$IMG"/*.bin | wc -c)
run() { python3 "$HERE/bench.py" "$1" "$2" "$3" -- "${@:4}" | tee -a "$OUT"; }
for p in "Rapido|--codecs zlib --hunk 16384" "Equilibrado|" "Maximo|--codecs lzma,zlib,huff,flac --hunk 32768"; do
	n=${p%%|*}; a=${p#*|}
	run "dvd $n" "$DS" "$TMP/f.chd" "$B/chdengine_cli" dvd "$IMG/dvd.iso" "$TMP/f.chd" --quiet $a
	run "dvd $n verificacao" "$DS" - "$B/chdengine_cli" verify "$TMP/f.chd" --quiet
done
for p in "Rapido|--codecs cdzl,cdfl" "Equilibrado|" "Maximo|--hunk 39168"; do
	n=${p%%|*}; a=${p#*|}
	run "cd $n" "$CS" "$TMP/f.chd" "$B/chdengine_cli" cd "$IMG/cd.cue" "$TMP/f.chd" --quiet $a
	run "cd $n verificacao" "$CS" - "$B/chdengine_cli" verify "$TMP/f.chd" --quiet
done
