[English](README.md) | **Português (Brasil)**

# CHDify

<img src="tools/icon/chdify_512.png" width="96" align="right" alt="Ícone do CHDify">

Aplicativo Android nativo que converte imagens de disco para **CHD** e de volta, direto no aparelho,
usando o código oficial do **chdman / biblioteca CHD do MAME** compilado para ARM64.
Funciona offline, sem root, sem Termux e sem aplicativos adicionais.

## Download

[Baixar APK na página de releases](https://github.com/GU12LHERME/CHDify/releases/latest) · [GitHub Actions](https://github.com/GU12LHERME/CHDify/actions) · [Notas de atualização](RELEASE_NOTES.md)

## Resumo

| Item | Situação |
|---|---|
| Núcleo | `chd.cpp`, `chdcodec.cpp`, `cdrom.cpp` e `chdman.cpp` do MAME, sem modificações (commit em `app/src/main/cpp/mame/MAME_COMMIT`) |
| Conversão | ISO, BIN/CUE e GDI → CHD; CHD de DVD → ISO; CHD de CD → BIN/CUE; CHD de GD-ROM (Dreamcast) → GDI |
| Fidelidade | CHDs **idênticos byte a byte** aos do `chdman createdvd`/`createcd`; extrações idênticas às do `chdman extractdvd`/`extractcd` (verificado nos testes) |
| Perfis | Rápido, Equilibrado (= padrão do chdman) e Máximo |
| Arquivos > 4 GB | Sim (E/S de 64 bits, testado com 4,3 GB) |
| Plataforma | Android 10+ (API 29), alvo API 36 (Android 16), arm64-v8a, páginas de 16 KB |
| Pacote | `com.conversorchd` (mantido para permitir atualizações) |

## Funcionalidades

**Conversão.** Seleção de arquivos (múltipla) ou de uma pasta inteira; o tipo é detectado automaticamente
(layout de setores 2048/2336/2352, ISO9660, UDF, imagem truncada, plataforma: PS2, PS1, PSP, GameCube, Wii,
Xbox, Saturn, Sega CD, Dreamcast). Entradas inválidas são bloqueadas com a explicação.

**Fila inteligente.** Vários itens, reordenar, remover, duplicados ignorados, progresso individual e geral,
erro isolado não interrompe a fila, cancelar item ou fila inteira, resumo final. A fila é salva em disco
(gravação atômica): se o app for encerrado, os itens pendentes voltam, e o que estava em andamento aparece
como **Interrompido**, para ser refeito do início. Não existe retomada parcial de CHD: o formato grava o mapa
de hunks no fim, e um arquivo incompleto não é recuperável com segurança.

**Ferramentas CHD.** Inspecionar (versão, tipo, trilhas, codecs, hunk, tamanhos, taxa, SHA-1, metadados),
verificar integridade, recompactar com outro perfil (equivale a `chdman copy`, com comparação de tamanho),
extrair e exportar as informações técnicas em texto.

**Excluir original após converter** (desligado por padrão). Só apaga quando o item terminou, a verificação de
integridade passou (ela fica obrigatória) e o arquivo gerado existe com o tamanho esperado. Nunca apaga após
falha, cancelamento ou interrupção; nunca apaga arquivo usado por outro item da fila, por outra folha
`.cue`/`.gdi` ou por playlist `.m3u` da mesma pasta. Para BIN/CUE e GDI exige que o jogo tenha sido adicionado
pela opção Pasta (sem acesso à pasta não há como provar que as trilhas não são compartilhadas). Por padrão,
cada exclusão pede confirmação. Em extração e recompactação, apagar o CHD de origem exige uma segunda autorização.

**Armazenamento.** Checagem de espaço livre com estimativa conservadora antes de começar; saída gravada como
`Nome.parcial` e renomeada só no fim; nada é sobrescrito (renomeia `Jogo (2).chd` ou pula, conforme o ajuste);
restos temporários limpos na abertura; cartão SD e USB pelo seletor do Android; perda de acesso (mídia removida,
permissão revogada) vira erro claro, com o parcial apagado.

**Organização.** Nomes preservados; opção de subpastas por console; jogos multidisco reconhecidos e playlist
`.m3u` gerada apontando para os CHDs (sem quebrar referências existentes). Extração de CD/GD-ROM vai para uma
subpasta própria, para não misturar trilhas de jogos diferentes.

**Histórico local.** Nome, operação, data e hora, duração, tamanhos, economia, perfil e resultado. Pode ser apagado.
Nenhum dado sai do aparelho.

**Execução.** Serviço em primeiro plano (`dataSync`) com notificação (progresso, cancelar item, cancelar fila),
wake lock parcial, progresso real do núcleo, velocidade, tempo decorrido e restante. Verificação de integridade
(equivalente a `chdman verify`, em paralelo) ligada por padrão. Modos de CPU Econômico, Equilibrado e Desempenho.

**Interface.** Seções Conversão, Fila, Ferramentas, Histórico e Ajustes; preto puro (OLED) e branco;
avançado recolhido; tela de abertura preta com o símbolo do app (SplashScreen API no Android 12+);
toque, D-pad e gamepad; voltar preditivo do Android 16.

### Controles físicos

| Botão | Ação |
|---|---|
| D-pad / analógico | navega entre os controles (anel de foco branco) |
| A | confirma |
| B | volta para Conversão / sai |
| Start | inicia a fila |
| X | adicionar arquivos |
| Y | cancelar a fila (com confirmação) |
| Select | log do item atual |
| L1 / R1 | seção anterior / próxima |

## Perfis de compactação

Todos são sem perdas e usam apenas codecs e tamanhos de hunk aceitos pelo chdman oficial. Os valores foram
escolhidos por medição (`tests/bench/RESULTADOS.md`; imagens sintéticas, 2 núcleos x86_64):

| Perfil | DVD | CD | Resultado medido (DVD / CD) |
|---|---|---|---|
| Rápido | zlib, hunk 16 KiB | cdzl + cdfl, 8 quadros | 4,4× / 1,9× mais rápido que o padrão; DVD até menor, CD +1,6 p.p. |
| Equilibrado (padrão) | padrão do chdman (lzma/zlib/huff/flac, 4 KiB) | padrão (cdlz/cdzl/cdfl, 8 quadros) | idêntico byte a byte ao chdman |
| Máximo | lzma/zlib/huff/flac, hunk 32 KiB | padrão, 16 quadros | −3,7 p.p. / −0,8 p.p. de tamanho |

O zstd foi avaliado e descartado: no MAME, ficou mais lento e maior que o lzma, e leitores antigos não o
suportam. Hunks maiores (DVD 64 KiB, CD 32 quadros) estão nas opções avançadas.

### Threads

A fila de trabalho do MAME cria `N − 1` threads de compressão, e a thread principal só lê e grava. O modo
automático passa `N = núcleos + 1` (o mesmo que `chdman -np`), para que todos os núcleos comprimam; neste
ambiente de 2 núcleos, isso dobrou a velocidade. A fila distribui os hunks dinamicamente, então os núcleos de
desempenho processam mais itens que os de eficiência. As threads rodam com prioridade levemente reduzida, para
a interface continuar fluida. O número de threads é fixado no início de cada item; não há ajuste dinâmico.

## Como o modo (CD ou DVD) é escolhido

Uma ISO tem apenas setores de 2048 bytes. Ela não diz quantas trilhas o disco tinha, nem se havia áudio
ou dados em modo 2. Por isso o app **não presume** que qualquer ISO pode virar um CHD de CD:

| Entrada | Modo padrão | CD permitido? | Observação |
|---|---|---|---|
| `.cue` / `.gdi` | CD | — | Trilhas, pregaps e índices interpretados pelo parser oficial do MAME |
| ISO maior que um CD | DVD | não | |
| ISO de PSP (UMD) | DVD | não | compatível com PPSSPP |
| ISO de PS2 | DVD | se couber num CD | aviso para jogos pequenos (podem ter sido CD) |
| ISO de PS1, Saturn, Sega CD, Dreamcast | CD | — | aviso forte: prefira o `.cue/.bin` original |
| ISO de GameCube/Wii/Xbox | DVD | não | aviso: esses emuladores em geral não leem CHD |
| ISO sem plataforma identificada | DVD | sim, com aviso | CD gera uma única trilha MODE1 |
| Imagem bruta 2352/2336 sem `.cue` | CD | — | aviso: uma única trilha de dados |
| Tamanho fora de 2048/2336/2352, ISO truncada | — | — | bloqueado |

## Desenvolvimento

Consulte a [documentação técnica](docs/DEVELOPMENT.md) para arquitetura, compilação, testes, assinatura e atualização do MAME.

## Limitações conhecidas

- `.nrg` (Nero) não é aceito: o parser do MAME lê a tabela do próprio arquivo de imagem, o que o esquema
  de espelhos não cobre.
- Arquivos que o provedor entrega como fluxo (nuvem/rede, sem tamanho fixo) são recusados. Copie-os
  para o armazenamento do aparelho antes.
- Ao cancelar uma conversão, a fila de trabalho do MAME deixa algumas dezenas de KB sem liberar até o
  processo terminar. É um comportamento do código original, que nunca é interrompido no meio pelo chdman.
- Um item interrompido (app encerrado à força) é refeito do início; não há retomada parcial.
- Extração de CD para BIN/CUE omite o subcódigo, como o chdman (o formato `.cue` não o comporta).
- Android 15+: serviços `dataSync` têm limite de 6 h por dia. Ao atingir o limite, a conversão é
  cancelada com segurança.

## Licenças

O código do app é distribuído sob a licença BSD-3-Clause (`LICENSE`). Componentes de terceiros e suas
licenças estão em `THIRD_PARTY_NOTICES.md`. Todos os arquivos do MAME usados são BSD-3-Clause (ou de
domínio público, no caso de `md5.cpp`). Nenhum arquivo GPL do MAME é compilado.

MAME é marca registrada de Gregory Ember. Este projeto não é afiliado ao MAMEdev.

## Publicação de versões

Atualize a versão do aplicativo e as notas de atualização antes de criar uma tag numérica correspondente (por exemplo, `2.0.0`). O GitHub Actions gera o APK ARM64 assinado e publica a release com `SHA256SUMS.txt`. Releases existentes não são substituídas. Builds comuns ficam nos artefatos do Actions.
