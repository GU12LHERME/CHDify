#!/usr/bin/env python3
"""Gera todos os recursos de ícone do CHDify a partir do ícone oficial.

Fonte: tools/icon/chdify_icon_source.png (o ícone escolhido: quadrado branco
arredondado com o disco estilizado e as duas setas circulares, em preto).
O símbolo é vetorizado com potrace sem redesenho, preservando o traço original.

Requer: potrace, rsvg-convert, Pillow.
Uso: tools/make_icon.py
"""
import os
import re
import subprocess
import tempfile

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "tools", "icon", "chdify_icon_source.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")

# ---------------------------------------------------------------- vetorização
im = Image.open(SRC).convert("L").point(lambda v: 0 if v < 128 else 255)
W = im.size[0]
for p in [(0, 0), (W - 1, 0), (0, W - 1), (W - 1, W - 1)]:
    ImageDraw.floodfill(im, p, 255)          # remove a moldura preta externa
x0, y0, x1, y1 = im.point(lambda v: 255 if v < 128 else 0).getbbox()
cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
S = max(x1 - x0, y1 - y0) + 20
crop = im.crop((int(cx - S / 2), int(cy - S / 2), int(cx - S / 2) + S, int(cy - S / 2) + S))

# raio do menor círculo, centrado, que contém todo o símbolo (para caber nas máscaras)
px = crop.load()
R = max(((x - S / 2) ** 2 + (y - S / 2) ** 2) ** 0.5 for y in range(0, S, 2) for x in range(0, S, 2) if px[x, y] < 128)

with tempfile.TemporaryDirectory() as t:
    crop.convert("1").save(os.path.join(t, "s.pbm"))
    subprocess.run(["potrace", os.path.join(t, "s.pbm"), "-s", "-o", os.path.join(t, "s.svg"),
                    "--turdsize", "10", "--opttolerance", "0.2"], check=True)
    svg = open(os.path.join(t, "s.svg")).read()
PATH = " ".join(" ".join(p.split()) for p in re.findall(r'<path d="([^"]+)"', svg, re.S))


def vector(size_dp, viewport, radius, color, comment):
    """VectorDrawable com o símbolo centrado e circunscrito num círculo de 'radius'."""
    k = radius / R
    o = viewport / 2 - k * S / 2
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- {comment}
     Gerado por tools/make_icon.py a partir de tools/icon/chdify_icon_source.png. Não editar à mão. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size_dp}dp" android:height="{size_dp}dp"
    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">
    <group android:translateX="{o:.4f}" android:translateY="{o:.4f}" android:scaleX="{k:.6f}" android:scaleY="{k:.6f}">
        <group android:translateY="{S}" android:scaleX="0.1" android:scaleY="-0.1">
            <path android:fillColor="{color}" android:pathData="{PATH}"/>
        </group>
    </group>
</vector>
'''


def write(rel, text):
    p = os.path.join(RES, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w").write(text)
    print("gerado", rel)


# Ícone adaptativo: tela de 108 dp, zona segura = círculo de 66 dp (raio 33).
write("drawable/ic_launcher_foreground.xml", vector(108, 108, 31.5, "#FF000000", "CHDify: símbolo do ícone adaptativo (preto sobre fundo branco)"))
write("drawable/ic_launcher_monochrome.xml", vector(108, 108, 31.5, "#FF000000", "CHDify: ícone temático (Android 13+); o sistema aplica a cor"))
# Notificação: silhueta branca (o sistema usa apenas o canal alfa).
write("drawable/ic_notification.xml", vector(24, 24, 11.5, "#FFFFFFFF", "CHDify: ícone da barra de status"))
# Splash (Android 12+): ícone de 288 dp que deve caber num círculo de 192 dp; símbolo branco sobre preto.
write("drawable/splash_icon.xml", vector(288, 288, 88, "#FFFFFFFF", "CHDify: símbolo da tela de abertura"))


# ---------------------------------------------------------------- PNGs legados
def svg_icon(px_size, round_icon):
    k = (px_size * (0.40 if round_icon else 0.42)) / R
    o = px_size / 2 - k * S / 2
    shape = (f'<circle cx="{px_size/2}" cy="{px_size/2}" r="{px_size/2}" fill="#fff"/>' if round_icon
             else f'<rect width="{px_size}" height="{px_size}" rx="{px_size*0.22}" fill="#fff"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{px_size}" height="{px_size}">{shape}'
            f'<g transform="translate({o},{o}) scale({k}) translate(0,{S}) scale(0.1,-0.1)">'
            f'<path fill="#000" d="{PATH}"/></g></svg>')


def render(svg_text, out):
    os.makedirs(os.path.dirname(out), exist_ok=True)
    subprocess.run(["rsvg-convert", "-o", out], input=svg_text.encode(), check=True)
    print("gerado", os.path.relpath(out, ROOT))


for dens, px_size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    render(svg_icon(px_size, False), os.path.join(RES, f"mipmap-{dens}", "ic_launcher.png"))
    render(svg_icon(px_size, True), os.path.join(RES, f"mipmap-{dens}", "ic_launcher_round.png"))
render(svg_icon(512, False), os.path.join(ROOT, "tools", "icon", "chdify_512.png"))
