#!/usr/bin/env python3
"""Gera imagens de benchmark com conteúdo mais próximo de discos reais.

Uso: make_bench_images.py <saida> <mkraw> [--escala N]

Não há jogos reais no ambiente de testes. Para aproximar a entropia de um
disco de jogo, as imagens misturam:
  - binários e bibliotecas reais do sistema (código executável);
  - texto real (código-fonte Python), comprimível;
  - dados aleatórios (simulam vídeo, áudio e texturas já comprimidos);
  - setores zerados (preenchimento, comum em DVDs);
  - áudio de CD sintético (tons + ruído rosa), que o FLAC não comprime perfeitamente.

dvd.iso  ≈ 360 MB × escala  (ISO9660+UDF)
cd.cue   ≈ 300 MB × escala  (trilha MODE1/2352 com EDC/ECC válidos + 2 trilhas de áudio)
"""
import io
import os
import random
import subprocess
import sys

import pycdlib

OUT = sys.argv[1]
MKRAW = sys.argv[2]
SCALE = float(sys.argv[sys.argv.index("--escala") + 1]) if "--escala" in sys.argv else 1.0
os.makedirs(OUT, exist_ok=True)
rng = random.Random(2026)
MB = 1024 * 1024


def gather(roots, exts, limit):
    """Concatena arquivos reais até 'limit' bytes (ordem determinística)."""
    buf = bytearray()
    for root in roots:
        for d, _, files in sorted(os.walk(root)):
            for f in sorted(files):
                if not f.endswith(exts):
                    continue
                p = os.path.join(d, f)
                try:
                    if os.path.islink(p) or not os.path.isfile(p):
                        continue
                    with open(p, "rb") as fh:
                        buf += fh.read(limit - len(buf))
                except OSError:
                    continue
                if len(buf) >= limit:
                    return bytes(buf)
    return bytes(buf)


def iso(name, files, udf, pad_mb=0):
    i = pycdlib.PyCdlib()
    i.new(interchange_level=3, vol_ident="BENCH", udf="2.60" if udf else None)
    for path, data in files:
        kw = {"iso_path": path.upper() + ";1"}
        if udf:
            kw["udf_path"] = path
        i.add_fp(io.BytesIO(data), len(data), **kw)
    p = os.path.join(OUT, name)
    i.write(p)
    i.close()
    if pad_mb:
        with open(p, "r+b") as f:
            f.seek(0, 2)
            f.write(b"\0" * int(pad_mb * MB))
    return p


libs = gather(["/usr/lib/x86_64-linux-gnu"], (".so",) + tuple(f".so.{n}" for n in range(10)), int(150 * MB * SCALE))
text = gather(["/usr/lib/python3.12", "/usr/lib/python3"], (".py",), int(45 * MB * SCALE))
noise = rng.randbytes(int(120 * MB * SCALE))

# ---------------- DVD ----------------
iso("dvd.iso", [("/LIBS.BIN", libs), ("/TEXT.BIN", text), ("/MOVIE.BIN", noise)], udf=True, pad_mb=40 * SCALE)

# ---------------- CD ----------------
data = iso("cd_data.iso", [("/LIBS.BIN", libs[: int(110 * MB * SCALE)]), ("/TEXT.BIN", text[: int(30 * MB * SCALE)]),
                           ("/MOVIE.BIN", noise[: int(60 * MB * SCALE)])], udf=False)
t1 = os.path.join(OUT, "cd (Track 01).bin")
subprocess.run([MKRAW, data, t1], check=True, stdout=subprocess.DEVNULL)
os.remove(data)
secs = max(20, int(240 * SCALE))
for n, f0 in ((2, 220), (3, 330)):
    dst = os.path.join(OUT, f"cd (Track 0{n}).bin")
    subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-y",
         "-f", "lavfi", "-i", f"anoisesrc=color=pink:amplitude=0.25:seed={n}:sample_rate=44100:duration={secs}",
         "-f", "lavfi", "-i", f"sine=frequency={f0}:sample_rate=44100:duration={secs}",
         "-f", "lavfi", "-i", f"sine=frequency={f0 * 1.5}:sample_rate=44100:duration={secs}",
         "-filter_complex", "[0][1][2]amix=inputs=3,aformat=channel_layouts=stereo",
         "-f", "s16le", "-acodec", "pcm_s16le", "-ar", "44100", "-ac", "2", dst],
        check=True)
    size = os.path.getsize(dst)
    with open(dst, "r+b") as f:  # completa o último quadro de 2352 bytes
        f.truncate((size + 2351) // 2352 * 2352)
with open(os.path.join(OUT, "cd.cue"), "w", newline="\r\n") as f:
    f.write('FILE "cd (Track 01).bin" BINARY\n  TRACK 01 MODE1/2352\n    INDEX 01 00:00:00\n')
    f.write('FILE "cd (Track 02).bin" BINARY\n  TRACK 02 AUDIO\n    INDEX 00 00:00:00\n    INDEX 01 00:02:00\n')
    f.write('FILE "cd (Track 03).bin" BINARY\n  TRACK 03 AUDIO\n    INDEX 01 00:00:00\n')

for n in sorted(os.listdir(OUT)):
    print(f"{n:28s} {os.path.getsize(os.path.join(OUT, n)) / MB:8.1f} MB")
