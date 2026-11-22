#!/usr/bin/env bash
# Testes reais do motor de conversão (build de host).
#
# Compara a saída do motor com o chdman oficial compilado do MESMO código-fonte,
# confere SHA-1, faz ida-e-volta (extract) e cobre cancelamento, falta de espaço,
# arquivos inválidos/corrompidos e imagens maiores que 4 GB.
#
# Uso: tests/native/run_tests.sh <dir-build-host> [--big]
set -uo pipefail

BUILD="$(cd "${1:?informe o diretório do build de host}" && pwd)"
BIG="${2:-}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENG="$BUILD/chdengine_cli"
REF="$BUILD/chdman"
WORK="$(mktemp -d)"
FX="$WORK/fx"
OUT="$WORK/out"
mkdir -p "$OUT"
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0
ok()   { echo "  [OK]    $*"; PASS=$((PASS + 1)); }
bad()  { echo "  [FALHA] $*"; FAIL=$((FAIL + 1)); }
code() { python3 -c 'import json,sys; print(json.loads(sys.stdin.readline())["code"])'; }
field() { python3 -c "import json,sys; print(json.loads(sys.stdin.readline())['$1'])"; }

echo "== Gerando imagens de teste"
python3 "$ROOT/tests/native/make_fixtures.py" "$FX" $BIG >/dev/null || { echo "falha ao gerar fixtures"; exit 1; }

echo "== Análise (probe)"
p=$("$ENG" probe "$FX/ps2_dvd.iso")
[[ $p == *'"platform":"ps2"'* && $p == *'"udf":true'* ]] && ok "PS2 DVD detectado (ISO9660+UDF, BOOT2)" || bad "probe ps2: $p"
p=$("$ENG" probe "$FX/psp_umd.iso");  [[ $p == *'"platform":"psp"'* ]] && ok "PSP detectado" || bad "probe psp: $p"
p=$("$ENG" probe "$FX/ps1_2048.iso"); [[ $p == *'"platform":"ps1"'* ]] && ok "PS1 em 2048 detectado" || bad "probe ps1: $p"
p=$("$ENG" probe "$FX/truncated.iso"); [[ $p == *'"truncated":true'* ]] && ok "ISO truncada detectada" || bad "probe truncada: $p"
p=$("$ENG" probe "$FX/odd_size.bin"); [[ $p == *'"layout":"unknown"'* ]] && ok "tamanho inválido detectado" || bad "probe odd: $p"
p=$("$ENG" probe "$FX/mixed (Track 01).bin"); [[ $p == *'"layout":"2352"'* && $p == *'"iso9660":true'* ]] && ok "setores brutos 2352 detectados" || bad "probe 2352: $p"
p=$("$ENG" probetoc "$FX/mixed.cue"); [[ $p == *'"numTracks":3'* && $p == *'"audio":true'* ]] && ok "CUE com 3 trilhas (dados+áudio)" || bad "probetoc: $p"

echo "== DVD (createdvd)"
for iso in ps2_dvd psp_umd data_cd; do
	r=$("$ENG" dvd "$FX/$iso.iso" "$OUT/$iso.chd" --quiet)
	[[ $(echo "$r" | code) == 0 ]] || { bad "$iso: $r"; continue; }
	"$REF" createdvd -i "$FX/$iso.iso" -o "$OUT/$iso.ref.chd" -f >/dev/null 2>&1
	cmp -s "$OUT/$iso.chd" "$OUT/$iso.ref.chd" && ok "$iso.chd idêntico byte a byte ao chdman oficial" || bad "$iso difere do chdman"
	want=$(sha1sum "$FX/$iso.iso" | cut -c1-40)
	[[ $(echo "$r" | field rawSha1) == "$want" ]] && ok "$iso SHA-1 dos dados == SHA-1 da ISO" || bad "$iso SHA-1"
	[[ $("$ENG" verify "$OUT/$iso.chd" --quiet | code) == 0 ]] && ok "$iso verificação (motor)" || bad "$iso verify motor"
	"$REF" verify -i "$OUT/$iso.chd" >/dev/null 2>&1 && ok "$iso verificação (chdman oficial)" || bad "$iso verify chdman"
	"$REF" extractdvd -i "$OUT/$iso.chd" -o "$OUT/$iso.back" -f >/dev/null 2>&1
	cmp -s "$OUT/$iso.back" "$FX/$iso.iso" && ok "$iso ida-e-volta (extractdvd) idêntica" || bad "$iso extract"
	rm -f "$OUT/$iso.back"
done

echo "== CD (createcd)"
for src in mixed.cue single.cue wav.cue data_cd.iso; do
	n="${src%.*}_cd"
	r=$("$ENG" cd "$FX/$src" "$OUT/$n.chd" --quiet)
	[[ $(echo "$r" | code) == 0 ]] || { bad "$src: $r"; continue; }
	"$REF" createcd -i "$FX/$src" -o "$OUT/$n.ref.chd" -f >/dev/null 2>&1
	cmp -s "$OUT/$n.chd" "$OUT/$n.ref.chd" && ok "$src -> CHD idêntico ao chdman oficial" || bad "$src difere do chdman"
	[[ $("$ENG" verify "$OUT/$n.chd" --quiet | code) == 0 ]] && ok "$src verificação" || bad "$src verify"
done
# Modo do app Android: dados lidos por descritores, parse_toc sobre espelhos esparsos
for src in mixed.cue single.cue wav.cue data_cd.iso; do
	n="${src%.*}_cd"
	r=$("$ENG" cd "$FX/$src" "$OUT/$n.fd.chd" --via-fd --quiet)
	cmp -s "$OUT/$n.fd.chd" "$OUT/$n.ref.chd" && ok "$src via descritores (modo Android) idêntico ao chdman" || bad "via-fd $src: $r"
done
r=$("$ENG" cd "$FX/mixed.cue" "$OUT/cancel_fd.chd" --via-fd --cancel-at 0.4 --quiet)
[[ $(echo "$r" | code) == 1 ]] && ok "CD via descritores cancelado" || bad "cancel via-fd: $r"
( cd "$OUT" && "$REF" extractcd -i mixed_cd.chd -o rt.cue -ob "rt%t.bin" -sb -f >/dev/null 2>&1 )
all=1; for i in 1 2 3; do cmp -s "$OUT/rt$i.bin" "$FX/mixed (Track 0$i).bin" || all=0; done
[[ $all == 1 ]] && ok "trilhas de dados e áudio idênticas após extractcd" || bad "extractcd trilhas"
[[ $("$ENG" info "$OUT/mixed_cd.chd") == *'"type":"cd","tracks":3'* ]] && ok "metadados de 3 trilhas no CHD" || bad "info cd"

echo "== Cancelamento"
r=$("$ENG" dvd "$FX/ps2_dvd.iso" "$OUT/cancel.chd" --cancel-at 0.3 --quiet)
[[ $(echo "$r" | code) == 1 && ! -e "$OUT/cancel.chd" ]] && ok "DVD cancelado a 30% (código 1, sem arquivo parcial)" || bad "cancel dvd: $r"
r=$("$ENG" cd "$FX/data_cd.iso" "$OUT/cancel2.chd" --cancel-at 0.5 --quiet)
[[ $(echo "$r" | code) == 1 ]] && ok "CD cancelado a 50%" || bad "cancel cd: $r"
r=$("$ENG" dvd "$FX/ps2_dvd.iso" "$OUT/after_cancel.chd" --quiet)
[[ $(echo "$r" | code) == 0 ]] && ok "nova conversão após cancelamento funciona" || bad "pós-cancel: $r"

echo "== Falta de espaço"
r=$(bash -c 'trap "" XFSZ; ulimit -f 4096; exec "$0" dvd "$1" "$2" --quiet' "$ENG" "$FX/ps2_dvd.iso" "$OUT/nospace.chd")
[[ $(echo "$r" | code) == 3 ]] && ok "sem espaço detectado (código 3)" || bad "nospace: $r"

echo "== Entradas inválidas / corrompidas"
r=$("$ENG" dvd "$FX/odd_size.bin" "$OUT/x.chd" --quiet); [[ $(echo "$r" | code) == 2 ]] && ok "tamanho não múltiplo de 2048 recusado" || bad "odd: $r"
sed 's/Track 03/Track 99/' "$FX/mixed.cue" > "$FX/missing.cue"
r=$("$ENG" cd "$FX/missing.cue" "$OUT/x.chd" --quiet); [[ $(echo "$r" | code) == 2 ]] && ok "CUE com .bin ausente recusado" || bad "missing: $r"
printf 'lixo\nFILE\n' > "$FX/bad.cue"
r=$("$ENG" cd "$FX/bad.cue" "$OUT/x.chd" --quiet); [[ $(echo "$r" | code) == 2 ]] && ok "CUE inválido recusado" || bad "badcue: $r"
cp "$OUT/ps2_dvd.chd" "$OUT/corrupt.chd"
python3 - "$OUT/corrupt.chd" <<'EOF'
import sys
f = open(sys.argv[1], "r+b"); f.seek(5 * 1024 * 1024); d = bytes(b ^ 0x5A for b in f.read(64)); f.seek(5 * 1024 * 1024); f.write(d)
EOF
r=$("$ENG" verify "$OUT/corrupt.chd" --quiet); [[ $(echo "$r" | code) == 6 ]] && ok "CHD corrompido reprovado na verificação" || bad "corrupt: $r"
r=$("$ENG" verify "$FX/data_cd.iso" --quiet); [[ $(echo "$r" | code) == 6 ]] && ok "arquivo que não é CHD reprovado" || bad "notchd: $r"

if [[ "$BIG" == "--big" ]]; then
	echo "== Arquivo maior que 4 GB"
	r=$("$ENG" dvd "$FX/big_4g.iso" "$OUT/big.chd" --quiet)
	want=$(sha1sum "$FX/big_4g.iso" | cut -c1-40)
	[[ $(echo "$r" | code) == 0 && $(echo "$r" | field rawSha1) == "$want" ]] && ok ">4 GB convertido, SHA-1 confere ($want)" || bad "big: $r"
	[[ $("$ENG" verify "$OUT/big.chd" --quiet | code) == 0 ]] && ok ">4 GB verificação" || bad "big verify"
fi

echo
echo "Resultado: $PASS OK, $FAIL falha(s)"
[[ $FAIL == 0 ]]
