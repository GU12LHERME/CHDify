#!/usr/bin/env python3
"""Mede uma conversão/verificação: tempo, CPU, pico de RAM, tamanho e taxa.

Uso:
  bench.py <rótulo> <bytes_entrada> <saida.chd|-> -- <comando...>

Imprime uma linha JSON por execução (para acumular em resultados.jsonl).
A medição usa os4.wait4 (rusage do processo filho): tempo de CPU de usuário +
sistema e pico de memória residente (ru_maxrss).
"""
import json
import os
import subprocess
import sys
import time

label, in_bytes, out_path = sys.argv[1], int(sys.argv[2]), sys.argv[3]
cmd = sys.argv[sys.argv.index("--") + 1:]

t0 = time.monotonic()
p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
_, status, ru = os.wait4(p.pid, 0)
wall = time.monotonic() - t0
stdout = p.stdout.read().decode(errors="replace")
code = os.waitstatus_to_exitcode(status)

out_bytes = os.path.getsize(out_path) if out_path != "-" and os.path.exists(out_path) else 0
cpu = ru.ru_utime + ru.ru_stime
res = {
    "rotulo": label,
    "codigo": code,
    "tempo_s": round(wall, 2),
    "velocidade_MBps": round(in_bytes / wall / 1048576, 2) if wall > 0 else 0,
    "cpu_s": round(cpu, 2),
    "cpu_medio_pct": round(100 * cpu / wall, 0) if wall > 0 else 0,
    "ram_pico_MB": round(ru.ru_maxrss / 1024, 1),
    "entrada_MB": round(in_bytes / 1048576, 1),
    "saida_MB": round(out_bytes / 1048576, 2),
    "taxa_pct": round(100 * out_bytes / in_bytes, 2) if out_bytes else None,
}
try:
    last = [l for l in stdout.splitlines() if l.startswith("{")][-1]
    j = json.loads(last)
    res["sha1_dados"] = j.get("rawSha1", "")
except (IndexError, ValueError):
    pass
print(json.dumps(res, ensure_ascii=False))
sys.exit(0)
