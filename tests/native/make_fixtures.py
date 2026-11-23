#!/usr/bin/env python3
"""Gera imagens de disco de teste (sem conteúdo protegido) para validar o motor.

Uso: make_fixtures.py <diretorio_saida> [--big]

Imagens geradas:
  ps2_dvd.iso      ISO9660+UDF com SYSTEM.CNF BOOT2 (estilo PS2 DVD), ~48 MB
  psp_umd.iso      ISO9660 com PSP_GAME/ e UMD_DATA.BIN (estilo PSP), ~12 MB
  data_cd.iso      ISO9660 simples de dados (CD modo 1, 2048), ~20 MB
  ps1_2048.iso     ISO9660 com SYSTEM.CNF BOOT = cdrom: (PS1 em 2048, deve gerar alerta)
  mixed.cue + mixed (Track 01..03).bin   CD com trilha de dados MODE1/2352 + 2 trilhas de áudio
  single.cue + single.bin               mesmo disco em um único .bin (INDEX com deslocamentos)
  wav.cue + wav_track*.wav              mesmo disco com áudio em arquivos WAVE
  gd.gdi + gd_track0*.{bin,raw}         GD-ROM (Dreamcast) com área de alta densidade
  odd_size.bin     tamanho não múltiplo de 2048 (inválido)
  truncated.iso    ISO cortada (PVD declara mais setores do que existem)
  big_4g.iso       (--big) ISO esparsa de ~4,3 GB com dados aleatórios além de 4 GiB
"""
import io
import math
import os
import random
import struct
import sys

import pycdlib

OUT = sys.argv[1]
BIG = "--big" in sys.argv
os.makedirs(OUT, exist_ok=True)
rng = random.Random(1234)


def blob(size, compressible=0.5):
    """Mistura dados aleatórios e repetitivos para exercitar todos os codecs."""
    out = bytearray()
    while len(out) < size:
        if rng.random() < compressible:
            out += (b"ConversorCHD-teste-" * 128)[:4096]
        else:
            out += rng.randbytes(4096)
    return bytes(out[:size])


def add_file(iso, path, data, udf=False):
    kw = {"iso_path": path.upper() + ";1"}
    if udf:
        kw["udf_path"] = path
    iso.add_fp(io.BytesIO(data), len(data), **kw)


def make_iso(name, files, dirs=(), sys_ident="", vol_ident="CONVERSOR", udf=False, pad_to=None):
    iso = pycdlib.PyCdlib()
    iso.new(interchange_level=3, sys_ident=sys_ident, vol_ident=vol_ident, udf="2.60" if udf else None)
    for d in dirs:
        kw = {"iso_path": d.upper()}
        if udf:
            kw["udf_path"] = d
        iso.add_directory(**kw)
    for path, data in files:
        add_file(iso, path, data, udf)
    full = os.path.join(OUT, name)
    iso.write(full)
    iso.close()
    if pad_to:
        with open(full, "r+b") as f:
            f.truncate(pad_to)
    return full


# ---------------- ISOs de dados ----------------
make_iso("ps2_dvd.iso",
         [("/SYSTEM.CNF", b"BOOT2 = cdrom0:\\SLUS_999.99;1\r\nVER = 1.00\r\nVMODE = NTSC\r\n"),
          ("/SLUS_999.99", blob(3 * 1024 * 1024)),
          ("/DATA/PACK.BIN", blob(44 * 1024 * 1024, 0.6))],
         dirs=["/DATA"], sys_ident="PLAYSTATION", vol_ident="TESTE_PS2", udf=True)

make_iso("psp_umd.iso",
         [("/UMD_DATA.BIN", b"ULUS99999|0000000000000001|0001|G"),
          ("/PSP_GAME/PARAM.SFO", blob(4096)),
          ("/PSP_GAME/USRDIR/DATA.BIN", blob(11 * 1024 * 1024))],
         dirs=["/PSP_GAME", "/PSP_GAME/USRDIR"], sys_ident="PSP GAME", vol_ident="TESTE_PSP")

data_iso = make_iso("data_cd.iso",
                    [("/LEIAME.TXT", b"Disco de dados de teste\n"), ("/DADOS.BIN", blob(20 * 1024 * 1024))],
                    vol_ident="DADOS_CD")

make_iso("ps1_2048.iso",
         [("/SYSTEM.CNF", b"BOOT = cdrom:\\SLUS_000.01;1\r\nTCB = 4\r\nEVENT = 10\r\n"),
          ("/SLUS_000.01", blob(2 * 1024 * 1024))],
         sys_ident="PLAYSTATION", vol_ident="TESTE_PS1")

# ---------------- CD misto (dados + áudio) ----------------
def raw_mode1(iso_bytes):
    """Converte setores de 2048 bytes em setores brutos MODE1/2352 (sync + cabeçalho + dados + EDC/ECC zerados)."""
    out = bytearray()
    nsec = len(iso_bytes) // 2048
    for lba in range(nsec):
        a = lba + 150
        m, s, f = a // 4500, (a // 75) % 60, a % 75
        bcd = lambda v: ((v // 10) << 4) | (v % 10)
        out += b"\x00" + b"\xff" * 10 + b"\x00" + bytes([bcd(m), bcd(s), bcd(f), 1])
        out += iso_bytes[lba * 2048:(lba + 1) * 2048]
        out += b"\x00" * 288
    return bytes(out)


def audio_track(seconds, freq):
    frames = int(seconds * 75)
    nsamp = frames * 588
    buf = bytearray()
    for i in range(nsamp):
        v = int(12000 * math.sin(2 * math.pi * freq * i / 44100))
        buf += struct.pack("<hh", v, -v)
    return bytes(buf)


small_iso = make_iso("cd_track1.iso",
                     [("/LEIAME.TXT", b"Trilha de dados do CD misto\n"), ("/D.BIN", blob(3 * 1024 * 1024))],
                     vol_ident="CD_MISTO")
with open(small_iso, "rb") as f:
    t1 = raw_mode1(f.read())
os.remove(small_iso)
t2 = audio_track(6, 440)
t3 = audio_track(4, 660)

names = ["mixed (Track 01).bin", "mixed (Track 02).bin", "mixed (Track 03).bin"]
for n, d in zip(names, (t1, t2, t3)):
    with open(os.path.join(OUT, n), "wb") as f:
        f.write(d)
with open(os.path.join(OUT, "mixed.cue"), "w", newline="\r\n") as f:
    f.write(f'FILE "{names[0]}" BINARY\n  TRACK 01 MODE1/2352\n    INDEX 01 00:00:00\n')
    f.write(f'FILE "{names[1]}" BINARY\n  TRACK 02 AUDIO\n    INDEX 00 00:00:00\n    INDEX 01 00:02:00\n')
    f.write(f'FILE "{names[2]}" BINARY\n  TRACK 03 AUDIO\n    INDEX 01 00:00:00\n')

# mesmo disco em um único .bin
def msf(nbytes):
    fr = nbytes // 2352
    return f"{fr // 4500:02d}:{(fr // 75) % 60:02d}:{fr % 75:02d}"

with open(os.path.join(OUT, "single.bin"), "wb") as f:
    f.write(t1 + t2 + t3)
with open(os.path.join(OUT, "single.cue"), "w", newline="\r\n") as f:
    f.write('FILE "single.bin" BINARY\n  TRACK 01 MODE1/2352\n    INDEX 01 00:00:00\n')
    f.write(f'  TRACK 02 AUDIO\n    INDEX 00 {msf(len(t1))}\n    INDEX 01 {msf(len(t1) + 2 * 75 * 2352)}\n')
    f.write(f'  TRACK 03 AUDIO\n    INDEX 01 {msf(len(t1) + len(t2))}\n')

# mesmo disco com áudio em WAVE (exercita a leitura de cabeçalho RIFF)
import wave
for i, d in ((2, t2), (3, t3)):
    with wave.open(os.path.join(OUT, f"wav_track{i}.wav"), "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(44100); w.writeframes(d)
with open(os.path.join(OUT, "wav.cue"), "w", newline="\r\n") as f:
    f.write(f'FILE "{names[0]}" BINARY\n  TRACK 01 MODE1/2352\n    INDEX 01 00:00:00\n')
    f.write('FILE "wav_track2.wav" WAVE\n  TRACK 02 AUDIO\n    INDEX 00 00:00:00\n    INDEX 01 00:02:00\n')
    f.write('FILE "wav_track3.wav" WAVE\n  TRACK 03 AUDIO\n    INDEX 01 00:00:00\n')

# ---------------- GD-ROM (Dreamcast) ----------------
# área de baixa densidade: trilha 1 (dados) + trilha 2 (áudio); área de alta
# densidade a partir do LBA 45000: trilha 3 (dados)
gd1 = make_iso("gd_t1.iso", [("/LEIAME.TXT", b"GD-ROM de teste\n"), ("/A.BIN", blob(600 * 2048))], vol_ident="GD_LOW")
gd3 = make_iso("gd_t3.iso", [("/1ST_READ.BIN", blob(1800 * 2048))], vol_ident="GD_HIGH")
for src, name in ((gd1, "gd_track01.bin"), (gd3, "gd_track03.bin")):
    with open(src, "rb") as fsrc, open(os.path.join(OUT, name), "wb") as fdst:
        fdst.write(raw_mode1(fsrc.read()))
    os.remove(src)
with open(os.path.join(OUT, "gd_track02.raw"), "wb") as f:
    f.write(audio_track(8, 523))
with open(os.path.join(OUT, "gd.gdi"), "w", newline="\r\n") as f:
    f.write("3\n1 0 4 2352 gd_track01.bin 0\n2 1000 0 2352 gd_track02.raw 0\n3 45000 4 2352 gd_track03.bin 0\n")

# ---------------- inválidos ----------------
with open(os.path.join(OUT, "odd_size.bin"), "wb") as f:
    f.write(rng.randbytes(2048 * 10 + 777))

with open(data_iso, "rb") as f:
    d = f.read()
with open(os.path.join(OUT, "truncated.iso"), "wb") as f:
    f.write(d[: (len(d) // 2) // 2048 * 2048])

# ---------------- > 4 GB ----------------
if BIG:
    p = os.path.join(OUT, "big_4g.iso")
    with open(data_iso, "rb") as f:
        head = f.read()
    size = (4 * 1024 ** 3 + 300 * 1024 ** 2) // 2048 * 2048
    with open(p, "wb") as f:
        f.write(head)
        f.truncate(size)
        # dados aleatórios atravessando a fronteira de 4 GiB e no final
        for off in (4 * 1024 ** 3 - 3 * 1024 * 1024, size - 2 * 1024 * 1024):
            f.seek(off)
            f.write(rng.randbytes(2 * 1024 * 1024))

print("fixtures em", OUT)
