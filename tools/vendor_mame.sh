#!/usr/bin/env bash
# Copia do repositório oficial do MAME apenas os arquivos necessários
# para o núcleo CHD (biblioteca util + chdman + dependências de terceiros).
# Uso: tools/vendor_mame.sh <caminho-do-checkout-mame>
set -euo pipefail
SRC="${1:?informe o caminho do checkout do MAME}"
DST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/cpp/mame"
rm -rf "$DST"; mkdir -p "$DST"
cp_rel() { for f in "$@"; do mkdir -p "$DST/$(dirname "$f")"; cp -a "$SRC/$f" "$DST/$f"; done; }
cd "$SRC"
# biblioteca util do MAME (BSD-3-Clause) e chdman
cp_rel $(ls src/lib/util/*.{cpp,h,hpp,ipp} 2>/dev/null)
cp_rel src/tools/chdman.cpp
# camada OSD mínima
cp_rel src/osd/osdcore.cpp src/osd/osdcore.h src/osd/osdfile.h src/osd/osdsync.cpp src/osd/osdsync.h \
       src/osd/osdcomm.h src/osd/eminline.h src/osd/eigcc.h src/osd/eigccarm.h src/osd/eigccx86.h src/osd/eigccppc.h \
       src/osd/strconv.cpp src/osd/strconv.h \
       src/osd/modules/lib/osdlib.h src/osd/modules/lib/osdlib_unix.cpp \
       src/osd/modules/file/posixfile.cpp src/osd/modules/file/posixfile.h src/osd/modules/file/posixdir.cpp \
       src/osd/modules/file/posixptty.cpp src/osd/modules/file/posixsocket.cpp
# terceiros
cp_rel $(ls 3rdparty/zlib/*.c 3rdparty/zlib/*.h) 3rdparty/zlib/LICENSE
cp_rel $(ls 3rdparty/lzma/C/*.c 3rdparty/lzma/C/*.h)
cp_rel $(find 3rdparty/flac/include 3rdparty/flac/src/libFLAC -name '*.[ch]') 3rdparty/flac/COPYING.Xiph
cp_rel $(find 3rdparty/zstd/lib -name '*.[chS]') 3rdparty/zstd/LICENSE
cp_rel 3rdparty/utf8proc/utf8proc.c 3rdparty/utf8proc/utf8proc.h 3rdparty/utf8proc/utf8proc_data.c 3rdparty/utf8proc/LICENSE.md
cp_rel docs/legal/BSD-3-Clause
cp_rel 3rdparty/lzma/DOC/lzma-sdk.txt
git rev-parse HEAD > "$DST/MAME_COMMIT"
echo "MAME $(cat "$DST/MAME_COMMIT") copiado para $DST"
