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

echo "== Perfis de compactação (codecs e hunk), lidos pelo chdman oficial"
want=$(sha1sum "$FX/ps2_dvd.iso" | cut -c1-40)
for prof in "zlib:16384" "lzma,zlib,huff,flac:32768" "lzma,zlib,huff,flac:65536"; do
	c="${prof%%:*}"; h="${prof##*:}"
	r=$("$ENG" dvd "$FX/ps2_dvd.iso" "$OUT/prof.chd" --codecs "$c" --hunk "$h" --quiet)
	[[ $(echo "$r" | code) == 0 && $(echo "$r" | field rawSha1) == "$want" ]] && ok "DVD $c hunk $h: SHA-1 dos dados confere" || bad "perfil dvd $prof: $r"
	"$REF" verify -i "$OUT/prof.chd" >/dev/null 2>&1 && ok "DVD $c hunk $h: chdman oficial verifica" || bad "chdman verify $prof"
	"$REF" extractdvd -i "$OUT/prof.chd" -o "$OUT/prof.iso" -f >/dev/null 2>&1
	cmp -s "$OUT/prof.iso" "$FX/ps2_dvd.iso" && ok "DVD $c hunk $h: extractdvd idêntico" || bad "extract $prof"
	rm -f "$OUT/prof.iso"
done
for prof in "cdzl,cdfl:19584" "cdlz,cdzl,cdfl:39168"; do
	c="${prof%%:*}"; h="${prof##*:}"
	r=$("$ENG" cd "$FX/mixed.cue" "$OUT/profcd.chd" --codecs "$c" --hunk "$h" --quiet)
	[[ $(echo "$r" | code) == 0 ]] && ok "CD $c hunk $h convertido" || bad "perfil cd $prof: $r"
	"$REF" verify -i "$OUT/profcd.chd" >/dev/null 2>&1 && ok "CD $c hunk $h: chdman oficial verifica" || bad "chdman verify cd $prof"
	( cd "$OUT" && rm -f pc*.bin && "$REF" extractcd -i profcd.chd -o pc.cue -ob "pc%t.bin" -sb -f >/dev/null 2>&1 )
	all=1; for i in 1 2 3; do cmp -s "$OUT/pc$i.bin" "$FX/mixed (Track 0$i).bin" || all=0; done
	[[ $all == 1 ]] && ok "CD $c hunk $h: trilhas idênticas após extractcd" || bad "extractcd $prof"
done
r=$("$ENG" dvd "$FX/ps2_dvd.iso" "$OUT/x.chd" --hunk 3000 --quiet); [[ $(echo "$r" | code) == 2 ]] && ok "hunk inválido recusado" || bad "hunk inválido: $r"
r=$("$ENG" cd "$FX/mixed.cue" "$OUT/x.chd" --codecs lzma --quiet); [[ $(echo "$r" | code) == 2 ]] && ok "codec de DVD recusado em CD" || bad "codec cd: $r"

echo "== Verificação paralela"
s1=$("$ENG" verify "$OUT/ps2_dvd.chd" --threads 1 --quiet | field rawSha1)
s4=$("$ENG" verify "$OUT/ps2_dvd.chd" --threads 4 --quiet | field rawSha1)
[[ -n "$s1" && "$s1" == "$s4" ]] && ok "1 thread e 4 threads calculam o mesmo SHA-1" || bad "verify paralelo: $s1 / $s4"
r=$("$ENG" verify "$OUT/ps2_dvd.chd" --threads 4 --cancel-at 0.3 --quiet); [[ $(echo "$r" | code) == 1 ]] && ok "verificação cancelada" || bad "cancel verify: $r"
for pos in 0.2 0.5 0.9; do
	cp "$OUT/ps2_dvd.chd" "$OUT/corr.chd"
	python3 - "$OUT/corr.chd" "$pos" <<'PY'
import os, sys
p = sys.argv[1]; off = int(os.path.getsize(p) * float(sys.argv[2]))
f = open(p, "r+b"); f.seek(off); d = bytes(b ^ 0xA5 for b in f.read(32)); f.seek(off); f.write(d)
PY
	r=$("$ENG" verify "$OUT/corr.chd" --threads 4 --quiet); [[ $(echo "$r" | code) == 6 ]] && ok "corrupção a ${pos} do arquivo detectada (4 threads)" || bad "corr $pos: $r"
done

cp "$OUT/ps2_dvd.chd" "$OUT/corrupt.chd"
python3 - "$OUT/corrupt.chd" <<'PY'
import sys
f = open(sys.argv[1], "r+b"); f.seek(5 * 1024 * 1024); d = bytes(b ^ 0x5A for b in f.read(64)); f.seek(5 * 1024 * 1024); f.write(d)
PY
echo "== GD-ROM (Dreamcast, .gdi)"
r=$("$ENG" cd "$FX/gd.gdi" "$OUT/gd.chd" --quiet)
"$REF" createcd -i "$FX/gd.gdi" -o "$OUT/gd.ref.chd" -f >/dev/null 2>&1
[[ $(echo "$r" | code) == 0 ]] && cmp -s "$OUT/gd.chd" "$OUT/gd.ref.chd" && ok "GDI -> CHD idêntico ao chdman" || bad "gdi: $r"
r=$("$ENG" cd "$FX/gd.gdi" "$OUT/gd.fd.chd" --via-fd --quiet)
cmp -s "$OUT/gd.fd.chd" "$OUT/gd.ref.chd" && ok "GDI por descritores (modo Android) idêntico" || bad "gdi via-fd: $r"

echo "== Extração (CHD -> ISO, BIN/CUE, GDI)"
mkdir -p "$OUT/ex" "$OUT/exref"
r=$("$ENG" extractdvd "$OUT/ps2_dvd.chd" "$OUT/ex/back.iso" --quiet)
[[ $(echo "$r" | code) == 0 ]] && cmp -s "$OUT/ex/back.iso" "$FX/ps2_dvd.iso" && ok "CHD de DVD -> ISO idêntica à original" || bad "extractdvd: $r"
r=$("$ENG" extractdvd "$OUT/mixed_cd.chd" "$OUT/ex/x.iso" --quiet); [[ $(echo "$r" | code) == 2 ]] && ok "CHD de CD recusado na extração de DVD" || bad "dvd de cd: $r"
r=$("$ENG" extractcd "$OUT/mixed_cd.chd" "$OUT/ex" "Jogo" --quiet)
( cd "$OUT/exref" && "$REF" extractcd -i ../mixed_cd.chd -o Jogo.cue -ob "Jogo (Track %t).bin" -sb -f >/dev/null 2>&1 )
[[ $(echo "$r" | code) == 0 ]] && cmp -s "$OUT/ex/Jogo.cue" "$OUT/exref/Jogo.cue" && ok "CHD de CD -> .cue idêntico ao chdman" || bad "extractcd cue: $r"
all=1; for i in 1 2 3; do cmp -s "$OUT/ex/Jogo (Track $i).bin" "$FX/mixed (Track 0$i).bin" || all=0; done
[[ $all == 1 ]] && ok "trilhas de dados e áudio extraídas idênticas às originais" || bad "extractcd trilhas"
r=$("$ENG" extractcd "$OUT/data_cd_cd.chd" "$OUT/ex" "Dados" --quiet)
[[ $(echo "$r" | code) == 0 && -f "$OUT/ex/Dados.bin" && -f "$OUT/ex/Dados.cue" ]] && ok "CD de uma trilha -> Dados.bin + Dados.cue" || bad "single: $r"
r=$("$ENG" extractcd "$OUT/gd.chd" "$OUT/ex" "Disco" --quiet)
( cd "$OUT/exref" && "$REF" extractcd -i ../gd.chd -o Disco.gdi -f >/dev/null 2>&1 )
[[ $(echo "$r" | code) == 0 ]] && cmp -s "$OUT/ex/Disco.gdi" "$OUT/exref/Disco.gdi" && ok "GD-ROM -> .gdi idêntico ao chdman" || bad "extract gdi: $r"
all=1; for f in 01.bin 02.raw 03.bin; do cmp -s "$OUT/ex/Disco$f" "$FX/gd_track$f" || all=0; done
[[ $all == 1 ]] && ok "trilhas do GD-ROM idênticas às originais" || bad "gdi trilhas"
r=$("$ENG" extractdvd "$OUT/ps2_dvd.chd" "$OUT/ex/c.iso" --cancel-at 0.3 --quiet); [[ $(echo "$r" | code) == 1 && ! -e "$OUT/ex/c.iso" ]] && ok "extração cancelada sem arquivo parcial" || bad "cancel extract: $r"
r=$(bash -c 'trap "" XFSZ; ulimit -f 4096; exec "$0" extractdvd "$1" "$2" --quiet' "$ENG" "$OUT/ps2_dvd.chd" "$OUT/ex/ns.iso"); [[ $(echo "$r" | code) == 3 ]] && ok "extração sem espaço (código 3)" || bad "extract nospace: $r"
r=$("$ENG" extractdvd "$OUT/corrupt.chd" "$OUT/ex/k.iso" --quiet 2>/dev/null); [[ $(echo "$r" | code) == 6 ]] && ok "CHD corrompido: extração recusada" || bad "extract corrupt: $r"

echo "== Recompactação (chdman copy)"
r=$("$ENG" recompress "$OUT/ps2_dvd.chd" "$OUT/rc.chd" --codecs lzma,zlib,huff,flac --hunk 32768 --quiet)
"$REF" copy -i "$OUT/ps2_dvd.chd" -o "$OUT/rc.ref.chd" -c lzma,zlib,huff,flac -hs 32768 -f >/dev/null 2>&1
[[ $(echo "$r" | code) == 0 ]] && cmp -s "$OUT/rc.chd" "$OUT/rc.ref.chd" && ok "DVD recompactado idêntico ao chdman copy" || bad "recompress dvd: $r"
[[ $(echo "$r" | field rawSha1) == $(sha1sum "$FX/ps2_dvd.iso" | cut -c1-40) ]] && ok "recompactação preserva o SHA-1 dos dados" || bad "recompress sha"
r=$("$ENG" recompress "$OUT/mixed_cd.chd" "$OUT/rcd.chd" --hunk 39168 --quiet)
"$REF" copy -i "$OUT/mixed_cd.chd" -o "$OUT/rcd.ref.chd" -hs 39168 -f >/dev/null 2>&1
[[ $(echo "$r" | code) == 0 ]] && cmp -s "$OUT/rcd.chd" "$OUT/rcd.ref.chd" && ok "CD recompactado idêntico ao chdman copy" || bad "recompress cd: $r"
r=$("$ENG" recompress "$OUT/gd.chd" "$OUT/rgd.chd" --quiet)
"$REF" copy -i "$OUT/gd.chd" -o "$OUT/rgd.ref.chd" -f >/dev/null 2>&1
[[ $(echo "$r" | code) == 0 ]] && cmp -s "$OUT/rgd.chd" "$OUT/rgd.ref.chd" && ok "GD-ROM recompactado idêntico ao chdman copy" || bad "recompress gd: $r"
r=$("$ENG" recompress "$OUT/ps2_dvd.chd" "$OUT/x.chd" --hunk 6144 --quiet); [[ $(echo "$r" | code) == 2 ]] && ok "hunk incompatível recusado na recompactação" || bad "rc hunk: $r"
r=$("$ENG" recompress "$OUT/ps2_dvd.chd" "$OUT/x.chd" --cancel-at 0.3 --quiet); [[ $(echo "$r" | code) == 1 ]] && ok "recompactação cancelada" || bad "rc cancel: $r"

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
