# Componentes de terceiros

Todos os componentes abaixo estão em `app/src/main/cpp/mame/`, copiados do repositório oficial do MAME
(https://github.com/mamedev/mame) no commit indicado em `MAME_COMMIT`, sem modificações.

| Componente | Arquivos usados | Licença | Texto |
|---|---|---|---|
| MAME — biblioteca util (CHD, codecs, CD-ROM) e chdman | `src/lib/util/*`, `src/tools/chdman.cpp` | BSD-3-Clause (copyright Aaron Giles e colaboradores do MAME) | `docs/legal/BSD-3-Clause` e cabeçalho de cada arquivo |
| MAME — camada OSD mínima | `src/osd/osdcore.*`, `osdsync.*`, `strconv.*`, `modules/lib/osdlib_unix.cpp`, `modules/file/posix*.cpp` | BSD-3-Clause | idem |
| MD5 (Colin Plumb) | `src/lib/util/md5.*` | Domínio público | cabeçalho do arquivo |
| zlib | `3rdparty/zlib` | zlib | `3rdparty/zlib/LICENSE` |
| LZMA SDK (Igor Pavlov) | `3rdparty/lzma/C` | Domínio público | `3rdparty/lzma/DOC/lzma-sdk.txt` |
| libFLAC (Xiph.Org) | `3rdparty/flac` | BSD-3-Clause (Xiph) | `3rdparty/flac/COPYING.Xiph` |
| Zstandard (Meta) | `3rdparty/zstd/lib` | BSD-3-Clause (ou GPLv2, à escolha; usado sob BSD) | `3rdparty/zstd/LICENSE` |
| utf8proc | `3rdparty/utf8proc` | MIT | `3rdparty/utf8proc/LICENSE.md` |

O arquivo `app/src/main/cpp/compat/SDL2/SDL.h` é um substituto mínimo, escrito para este projeto,
que fornece funções inertes de área de transferência exigidas por `osdlib_unix.cpp`. O app não usa SDL.

O texto "CHD" do ícone foi convertido em vetor a partir da fonte DejaVu Sans Bold (licença Bitstream
Vera / domínio público para as alterações do DejaVu). A fonte em si não é distribuída com o app.

MAME é marca registrada de Gregory Ember.
