cmake_minimum_required(VERSION 3.22.1)
project(conversorchd C CXX)

# ---------------------------------------------------------------------------
#  Núcleo CHD do MAME (código oficial, BSD-3-Clause) + dependências
# ---------------------------------------------------------------------------
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

set(MAME ${CMAKE_CURRENT_SOURCE_DIR}/mame)

# Otimizações: -O3 em todo o núcleo; no Android ARM64 o NDK já habilita NEON.
set(CORE_OPT_FLAGS -O3 -fno-strict-aliasing)

# ---------------- zlib ----------------
file(GLOB ZLIB_SRC ${MAME}/3rdparty/zlib/*.c)
add_library(mame_zlib STATIC ${ZLIB_SRC})
target_include_directories(mame_zlib PUBLIC ${MAME}/3rdparty/zlib)
target_compile_definitions(mame_zlib PRIVATE HAVE_UNISTD_H ZLIB_CONST)
target_compile_options(mame_zlib PRIVATE ${CORE_OPT_FLAGS} -w)

# ---------------- LZMA (SDK 7-Zip, domínio público) ----------------
add_library(mame_lzma STATIC
  ${MAME}/3rdparty/lzma/C/Alloc.c
  ${MAME}/3rdparty/lzma/C/CpuArch.c
  ${MAME}/3rdparty/lzma/C/LzFind.c
  ${MAME}/3rdparty/lzma/C/LzFindOpt.c
  ${MAME}/3rdparty/lzma/C/LzmaDec.c
  ${MAME}/3rdparty/lzma/C/LzmaEnc.c)
target_include_directories(mame_lzma PUBLIC ${MAME}/3rdparty)
target_compile_definitions(mame_lzma PUBLIC Z7_ST)
target_compile_options(mame_lzma PRIVATE ${CORE_OPT_FLAGS} -w)

# ---------------- FLAC (BSD/Xiph) ----------------
set(FLAC_DIR ${MAME}/3rdparty/flac/src/libFLAC)
add_library(mame_flac STATIC
  ${FLAC_DIR}/bitmath.c ${FLAC_DIR}/bitreader.c ${FLAC_DIR}/bitwriter.c ${FLAC_DIR}/cpu.c
  ${FLAC_DIR}/crc.c ${FLAC_DIR}/fixed.c ${FLAC_DIR}/float.c ${FLAC_DIR}/format.c
  ${FLAC_DIR}/lpc.c ${FLAC_DIR}/md5.c ${FLAC_DIR}/memory.c
  # variantes com intrínsecos (compiladas condicionalmente conforme a arquitetura)
  ${FLAC_DIR}/lpc_intrin_neon.c ${FLAC_DIR}/lpc_intrin_sse2.c ${FLAC_DIR}/lpc_intrin_sse41.c
  ${FLAC_DIR}/lpc_intrin_avx2.c ${FLAC_DIR}/lpc_intrin_fma.c ${FLAC_DIR}/fixed_intrin_sse2.c
  ${FLAC_DIR}/fixed_intrin_ssse3.c ${FLAC_DIR}/fixed_intrin_sse42.c ${FLAC_DIR}/fixed_intrin_avx2.c
  ${FLAC_DIR}/stream_encoder_intrin_sse2.c ${FLAC_DIR}/stream_encoder_intrin_ssse3.c
  ${FLAC_DIR}/stream_encoder_intrin_avx2.c
  ${FLAC_DIR}/stream_decoder.c ${FLAC_DIR}/stream_encoder.c
  ${FLAC_DIR}/stream_encoder_framing.c ${FLAC_DIR}/window.c)
target_include_directories(mame_flac PUBLIC ${MAME}/3rdparty/flac/include)
target_include_directories(mame_flac PRIVATE ${FLAC_DIR}/include)
target_compile_definitions(mame_flac PUBLIC FLAC__NO_DLL)
target_compile_definitions(mame_flac PRIVATE
  NDEBUG HAVE_CONFIG_H HAVE_FSEEKO ENABLE_64_BIT_WORDS=1 OGG_FOUND=0 FLAC__HAS_OGG=0
  HAVE_LROUND=1 HAVE_INTTYPES_H HAVE_STDBOOL_H HAVE_STDINT_H HAVE_STDIO_H HAVE_STDLIB_H
  HAVE_STRING_H _FILE_OFFSET_BITS=64 _LARGEFILE_SOURCE HAVE_BSWAP16 HAVE_BSWAP32
  CPU_IS_BIG_ENDIAN=0 CPU_IS_LITTLE_ENDIAN=1 WORDS_BIGENDIAN=0 FLAC__SYS_LINUX)
target_compile_options(mame_flac PRIVATE ${CORE_OPT_FLAGS} -w)

# ---------------- zstd (BSD) ----------------
file(GLOB ZSTD_SRC
  ${MAME}/3rdparty/zstd/lib/common/*.c
  ${MAME}/3rdparty/zstd/lib/compress/*.c
  ${MAME}/3rdparty/zstd/lib/decompress/*.c)
add_library(mame_zstd STATIC ${ZSTD_SRC})
target_include_directories(mame_zstd PUBLIC ${MAME}/3rdparty/zstd/lib)
target_compile_definitions(mame_zstd PRIVATE XXH_NAMESPACE=ZSTD_ DEBUGLEVEL=0 ZSTD_DISABLE_ASM NDEBUG)
target_compile_options(mame_zstd PRIVATE ${CORE_OPT_FLAGS} -w)

# ---------------- utf8proc (MIT) ----------------
add_library(mame_utf8proc STATIC ${MAME}/3rdparty/utf8proc/utf8proc.c)
target_include_directories(mame_utf8proc PUBLIC ${MAME}/3rdparty/utf8proc)
target_compile_definitions(mame_utf8proc PUBLIC UTF8PROC_STATIC)
target_compile_options(mame_utf8proc PRIVATE ${CORE_OPT_FLAGS} -w)

# ---------------- MAME util + OSD (BSD-3-Clause) ----------------
set(U ${MAME}/src/lib/util)
add_library(mame_chd STATIC
  ${U}/avhuff.cpp ${U}/aviio.cpp ${U}/bitmap.cpp ${U}/cdrom.cpp ${U}/chd.cpp ${U}/chdcodec.cpp
  ${U}/corealloc.cpp ${U}/corefile.cpp ${U}/corestr.cpp ${U}/coreutil.cpp ${U}/flac.cpp
  ${U}/hashing.cpp ${U}/huffman.cpp ${U}/ioprocs.cpp ${U}/ioprocsfilter.cpp ${U}/ioprocsstream.cpp
  ${U}/md5.cpp ${U}/palette.cpp ${U}/path.cpp ${U}/strformat.cpp ${U}/unicode.cpp ${U}/vbiparse.cpp
  ${MAME}/src/osd/osdcore.cpp ${MAME}/src/osd/osdsync.cpp ${MAME}/src/osd/strconv.cpp
  ${MAME}/src/osd/modules/lib/osdlib_unix.cpp
  ${MAME}/src/osd/modules/file/posixfile.cpp ${MAME}/src/osd/modules/file/posixdir.cpp
  ${MAME}/src/osd/modules/file/posixptty.cpp ${MAME}/src/osd/modules/file/posixsocket.cpp)
target_include_directories(mame_chd PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/compat)
target_include_directories(mame_chd PUBLIC ${MAME}/src/osd ${MAME}/src/lib ${MAME}/src/lib/util ${MAME}/3rdparty ${MAME}/src/osd/modules/file)
target_compile_definitions(mame_chd PUBLIC ZLIB_CONST PTR64=1 NDEBUG LSB_FIRST CRLF=2)
if(ANDROID)
  target_compile_definitions(mame_chd PUBLIC SDLMAME_ANDROID=1)
endif()
target_compile_options(mame_chd PRIVATE ${CORE_OPT_FLAGS} -Wno-deprecated-declarations)
target_link_libraries(mame_chd PUBLIC mame_zlib mame_lzma mame_flac mame_zstd mame_utf8proc)
find_package(Threads REQUIRED)
target_link_libraries(mame_chd PUBLIC Threads::Threads)

# ---------------------------------------------------------------------------
#  Motor de conversão (frontend adaptado do chdman, com progresso/cancelamento)
# ---------------------------------------------------------------------------
add_library(chdengine STATIC engine/chd_engine.cpp engine/fd_file.cpp engine/disc_probe.cpp)
target_include_directories(chdengine PUBLIC engine)
target_link_libraries(chdengine PUBLIC mame_chd)
target_compile_options(chdengine PRIVATE -O2 -Wall -Wno-sign-compare)
if(EXISTS ${MAME}/MAME_COMMIT)
  file(READ ${MAME}/MAME_COMMIT MAME_COMMIT_FULL)
  string(STRIP "${MAME_COMMIT_FULL}" MAME_COMMIT_FULL)
  string(SUBSTRING "${MAME_COMMIT_FULL}" 0 12 MAME_COMMIT_SHORT)
  target_compile_definitions(chdengine PRIVATE CHDCONV_MAME_COMMIT="${MAME_COMMIT_SHORT}")
endif()

if(ANDROID)
  # Biblioteca JNI carregada pelo aplicativo
  add_library(chdconv SHARED jni/chdconv_jni.cpp)
  target_link_libraries(chdconv PRIVATE chdengine log android)
  target_link_options(chdconv PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
  target_compile_options(chdconv PRIVATE -O2 -Wall -Wno-sign-compare)
else()
  # Builds de host para testes: chdman oficial (referência) e harness do motor
  add_executable(chdman ${MAME}/src/tools/chdman.cpp host/version.cpp)
  target_link_libraries(chdman PRIVATE mame_chd)
  target_compile_options(chdman PRIVATE -O2 -w)

  add_executable(chdengine_cli host/engine_cli.cpp)
  target_link_libraries(chdengine_cli PRIVATE chdengine)
endif()
