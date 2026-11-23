# Benchmarks do motor de conversão

Medidos com `tests/bench/bench.py` (tempo de parede, CPU de usuário + sistema e
pico de RAM do processo via `wait4`) sobre o harness `chdengine_cli`, que executa
o mesmo código nativo do app.

## Ambiente e limitações

- **Máquina:** contêiner Linux x86_64 com **2 núcleos**, compilação Release (GCC 13, -O3).
- **Não é um aparelho Android.** Não foi possível medir em ARM64 real nem
  observar temperatura e consumo de bateria. Os testes de ARM64 (QEMU) validam a
  correção, mas não o desempenho.
- **As imagens são sintéticas**, porque não havia jogos reais no ambiente
  (`make_bench_images.py`). Elas misturam binários e textos reais do sistema,
  dados aleatórios (que simulam vídeo e texturas já comprimidos), preenchimento
  zerado e áudio sintético de CD. A trilha de dados do CD tem EDC/ECC válidos,
  gerados pelo próprio MAME. Com jogos reais, as taxas de compressão serão
  diferentes; as proporções entre perfis tendem a se manter.
- Todos os resultados conferem o **SHA-1 dos dados** contra o SHA-1 da entrada
  (nenhum perfil perde dados).

Imagens: `dvd.iso` 333,2 MB (ISO9660+UDF); `cd.cue` 302,0 MB (trilha
MODE1/2352 de 221 MB + 2 trilhas de áudio de 40 MB).

## Referência (versão anterior)

| Operação | Tempo | Velocidade | CPU média | RAM pico | Saída |
|---|---|---|---|---|---|
| DVD (padrão do chdman) | 81,5 s | 4,1 MB/s | 105% | 15,5 MB | 179,97 MB (54,02%) |
| DVD verificação | 6,4 s | 52 MB/s | 100% | 13,4 MB | — |
| CD (padrão do chdman) | 66,2 s | 4,6 MB/s | 104% | 25,0 MB | 123,67 MB (40,95%) |
| CD verificação | 7,0 s | 43 MB/s | 99% | 13,6 MB | — |

**Gargalo encontrado:** a fila de trabalho do MAME (`WORK_QUEUE_FLAG_MULTI`) cria
`N − 1` threads de compressão, e a thread que chama `compress_continue()` só lê,
grava e espera. Com o padrão (`N` = núcleos), um núcleo fica ocioso: aqui,
metade da CPU. A verificação, como no `chdman verify`, usa uma única thread.

## Depois das otimizações

| Perfil | Operação | Tempo | Velocidade | CPU | RAM pico | Saída |
|---|---|---|---|---|---|---|
| Rápido | DVD | **9,3 s** | 35,7 MB/s | 190% | 16,9 MB | 177,58 MB (53,30%) |
| Rápido | verificação | 2,2 s | 154 MB/s | 177% | 15,3 MB | — |
| Equilibrado | DVD | 42,9 s | 7,8 MB/s | 197% | 16,2 MB | 179,97 MB (54,02%) |
| Equilibrado | verificação | 3,5 s | 96 MB/s | 185% | 17,4 MB | — |
| Máximo | DVD | 43,6 s | 7,6 MB/s | 197% | 28,1 MB | **167,79 MB (50,36%)** |
| Máximo | verificação | 3,8 s | 89 MB/s | 179% | 15,5 MB | — |
| Rápido | CD | **17,9 s** | 16,9 MB/s | 194% | 22,8 MB | 128,51 MB (42,55%) |
| Rápido | verificação | 3,3 s | 91 MB/s | 184% | 15,7 MB | — |
| Equilibrado | CD | 35,2 s | 8,6 MB/s | 197% | 26,0 MB | 123,67 MB (40,95%) |
| Equilibrado | verificação | 3,8 s | 80 MB/s | 190% | 15,9 MB | — |
| Máximo | CD | 38,5 s | 7,8 MB/s | 197% | 36,9 MB | **121,30 MB (40,16%)** |
| Máximo | verificação | 3,5 s | 85 MB/s | 192% | 15,8 MB | — |

O perfil Equilibrado continua gerando arquivos **idênticos byte a byte** aos do
chdman oficial, agora em cerca de metade do tempo neste ambiente.

### Ganhos por otimização

| Mudança | Efeito medido | Observação |
|---|---|---|
| Threads de compressão = núcleos + 1 (`-np` do chdman) | DVD 81,5 → 40,6 s; CD 66,2 → 33,0 s; saída idêntica | Aqui o ganho é 2×, porque com 2 núcleos um deles ficava parado. Em um aparelho de 8 núcleos, o ganho esperado é de até 8/7 (~14%) — **não medido**. |
| Verificação paralela (até 6 threads, SHA-1 em ordem) | DVD 6,4 → 3,4 s; CD 7,0 → 3,7 s | Mesmo SHA-1 com 1 ou N threads (testado). Memória: 2 blocos de 2 MB por thread. |
| Perfil Rápido | DVD 4,4× e CD 1,9× mais rápido que o padrão | Codecs zlib/FLAC (lidos por qualquer leitor de CHD). |
| Perfil Máximo | DVD −3,7 pontos percentuais; CD −0,8 p.p. | Hunks maiores: DVD 32 KiB, CD 16 quadros. |
| `posix_fadvise(SEQUENTIAL)` na entrada | não mensurável aqui | Com o arquivo em cache, não há diferença. É só uma dica de leitura antecipada ao kernel/FUSE. |
| Prioridade de thread 4 durante a conversão | não mensurável aqui | Mantém a interface fluida; valores ≥ 10 limitariam o Android aos núcleos de eficiência. |

### Candidatos avaliados e descartados

| Candidato | Resultado | Motivo do descarte |
|---|---|---|
| zstd (DVD, hunk 4K) | 32,0 s, 54,56% | Mais lento e maior que o lzma; leitores antigos não suportam |
| zstd + lzma/zlib/huff/flac | 54,8 s, 54,48% | Mais lento, sem ganho de tamanho |
| DVD padrão, hunk 64 KiB | 46,6 s, 49,64%, 45 MB de RAM | Ganho pequeno (−0,7 p.p.) sobre 32 KiB, com mais RAM e mais custo por leitura aleatória no emulador; disponível nas opções avançadas |
| CD padrão, 32 quadros | 39,3 s, 39,53%, 60 MB de RAM | Mesmo motivo; disponível nas opções avançadas |
| lzma sozinho, hunk 16K (DVD) | 20,3 s, 51,29% | Bom equilíbrio, mas difere do chdman padrão; o perfil Equilibrado foi mantido idêntico ao chdman, por compatibilidade |

O nível de compressão LZMA do MAME é fixo no código do chdman. Ele não foi
alterado, porque o código do MAME é mantido sem modificações.

## Reproduzir

```bash
pip install pycdlib
cmake -S app/src/main/cpp -B build-host -G Ninja && cmake --build build-host
python3 tests/bench/make_bench_images.py /tmp/bench build-host/mkraw
tests/bench/run_bench.sh build-host /tmp/bench
```

## Modos de CPU (CHDify 1.0.0)

Máquina de build (x86-64, 2 núcleos), imagem DVD de 178 MB (`make_bench_images.py --escala 0.5`),
`chdengine_cli` com `--threads 1` (equivale ao Econômico num aparelho de 2 núcleos) e `--threads 2`.
Tempos reais medidos em 23/11/2026:

| Operação | 1 thread | 2 threads | ganho |
|---|---|---|---|
| Converter ISO → CHD (padrão chdman) | 37,63 s | 19,67 s | 1,91× |
| Verificar CHD | 2,45 s | 1,70 s | 1,44× |
| Extrair CHD → ISO | 2,85 s | 1,80 s | 1,58× |
| Recompactar (zlib, hunk 16 KiB) | 6,97 s | 5,64 s | 1,24× |

A ISO extraída é byte a byte idêntica à original nas duas execuções.
Conclusão: a compressão escala quase linearmente com os núcleos; o modo Econômico
(metade dos núcleos, prioridade baixa) troca velocidade por menos calor de forma previsível.
Os perfis de compactação não foram alterados: nenhuma medição mostrou ganho que justificasse mudar.
Medições num aparelho ARM real (big.LITTLE, limitação térmica) ficam pendentes de hardware.
