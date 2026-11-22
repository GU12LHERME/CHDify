#!/usr/bin/env bash
# Copia do repositório oficial do MAME apenas os arquivos usados pelo build
# (lista em tools/mame_files.txt: biblioteca util/CHD, chdman, camada OSD mínima
# e dependências de terceiros), sem modificações.
#
# Uso: tools/vendor_mame.sh <caminho-do-checkout-mame>
#
# Ao atualizar o MAME, se o build passar a precisar de um arquivo novo, adicione-o
# a tools/mame_files.txt (o compilador indica o cabeçalho ausente).
set -euo pipefail
SRC="${1:?informe o caminho do checkout do MAME}"
HERE="$(cd "$(dirname "$0")" && pwd)"
DST="$(cd "$HERE/.." && pwd)/app/src/main/cpp/mame"
LIST="$HERE/mame_files.txt"

rm -rf "$DST"
mkdir -p "$DST"
missing=0
while IFS= read -r f; do
	[[ -z "$f" || "$f" == \#* || "$f" == MAME_COMMIT ]] && continue
	if [[ -f "$SRC/$f" ]]; then
		mkdir -p "$DST/$(dirname "$f")"
		cp -a "$SRC/$f" "$DST/$f"
	else
		echo "aviso: $f não existe nesta versão do MAME" >&2
		missing=1
	fi
done < "$LIST"
git -C "$SRC" rev-parse HEAD > "$DST/MAME_COMMIT"
echo "MAME $(cat "$DST/MAME_COMMIT") copiado para $DST"
exit $missing
