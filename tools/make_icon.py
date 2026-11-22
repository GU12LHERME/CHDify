#!/usr/bin/env python3
"""Gera o ícone do app: CD branco com "CHD" em arco (requer fonttools e a fonte DejaVu Sans Bold).
Uso: tools/make_icon.py [pasta-para-previews-svg]"""
import math, re
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen
f = TTFont('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf')
gs = f.getGlyphSet(); cmap = f.getBestCmap(); hmtx = f['hmtx']
TEXT, H = "CHD", 10.5          # altura das maiúsculas
R = 16.5                      # raio da linha de base (o anel vai até 12, o disco até 30)
TRACK = 3.0                   # espaço extra entre letras
bp = BoundsPen(gs); gs[cmap[ord('H')]].draw(bp); cap = bp.bounds[3]; s = H / cap
Rm = R + H * 0.5              # o espaçamento segue o meio da altura das letras
glyphs = [cmap[ord(c)] for c in TEXT]
widths = [hmtx[g][0] * s for g in glyphs]
total = sum(widths) + TRACK * (len(TEXT) - 1)
pen = SVGPathPen(gs); pos = -total / 2
for g, w in zip(glyphs, widths):
    th = (pos + w / 2) / Rm               # ângulo do centro da letra (0 = topo)
    co, si = math.cos(th), math.sin(th)
    a, b, c, d = s * co, s * si, s * si, -s * co
    e = 54 - (w / 2) * co + R * si
    ff = 54 - (w / 2) * si - R * co
    gs[g].draw(TransformPen(pen, (a, b, c, d, e, ff)))
    pos += w + TRACK
d = re.sub(r'-?\d+\.\d+', lambda m: f"{float(m.group()):.2f}".rstrip('0').rstrip('.'), pen.getCommands())
disc = "M54,24a30,30 0,1 1,0 60a30,30 0,1 1,0 -60z"
ring = "M54,42a12,12 0,1 1,0 24a12,12 0,1 1,0 -24zM54,44a10,10 0,1 0,0 20a10,10 0,1 0,0 -20z"
hole = "M54,49a5,5 0,1 1,0 10a5,5 0,1 1,0 -10z"
import os, sys
base = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'res', 'drawable') + os.sep
open(base + 'ic_launcher_foreground.xml', 'w').write(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Ícone adaptativo: CD branco com "CHD" em arco acompanhando o disco (DejaVu Sans Bold convertida em vetor) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFFFF" android:pathData="{disc}"/>
    <path android:fillColor="#FF8C8C8C" android:fillType="evenOdd" android:pathData="{ring}"/>
    <path android:fillColor="#FF000000" android:pathData="{hole}"/>
    <path android:fillColor="#FF000000" android:pathData="{d}"/>
</vector>
''')
open(base + 'ic_launcher_monochrome.xml', 'w').write(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Camada monocromática do ícone: disco com "CHD" em arco, anel e furo recortados -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{disc}{ring}{hole}{d}"/>
</vector>
''')
svg = lambda mono: f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">
<rect width="108" height="108" fill="#000"/>''' + (f'<path fill="#fff" fill-rule="evenodd" d="{disc}{ring}{hole}{d}"/>' if mono else
f'<path fill="#fff" d="{disc}"/><path fill="#8C8C8C" fill-rule="evenodd" d="{ring}"/><path fill="#000" d="{hole}"/><path fill="#000" d="{d}"/>') + '</svg>'
open(os.path.join(sys.argv[1] if len(sys.argv) > 1 else '.', 'icone.svg'), 'w').write(svg(False))
open(os.path.join(sys.argv[1] if len(sys.argv) > 1 else '.', 'icone_mono.svg'), 'w').write(svg(True))
print("arco:", round(math.degrees(total / Rm)), "graus")
