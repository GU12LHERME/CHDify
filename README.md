# Conversor CHD (Android)

Aplicativo Android nativo que converte imagens de disco (**ISO**, **CUE/BIN**, **GDI**) para **CHD**
direto no aparelho, usando o código oficial do **chdman / biblioteca CHD do MAME** compilado para ARM64.
Funciona offline, sem root, sem Termux e sem aplicativos adicionais.

## Resumo

| Item | Situação |
|---|---|
| Núcleo | `chd.cpp`, `chdcodec.cpp`, `cdrom.cpp` e `chdman.cpp` do MAME, sem modificações (commit em `app/src/main/cpp/mame/MAME_COMMIT`) |
| Saída | Os CHDs gerados pelo motor são **idênticos byte a byte** aos do `chdman createdvd` / `createcd` oficial (verificado nos testes) |
| Modos | DVD (`createdvd`, codecs lzma/zlib/huff/flac, hunk de 4 KiB) e CD (`createcd`, cdlz/cdzl/cdfl, 8 quadros por hunk) |
| Entradas | `.iso`, `.img`, `.bin` avulso, `.cue` (+ `.bin`/`.wav`), `.gdi` (GD-ROM) |
| Arquivos > 4 GB | Sim (E/S de 64 bits, testado com 4,3 GB) |
| Plataforma | Android 10+ (API 29), alvo API 36 (Android 16), arm64-v8a, páginas de 16 KB |

## Funcionalidades

- Seleção de arquivos pelo gerenciador do Android (seleção múltipla) ou de uma pasta inteira.
- Fila com conversão em sequência; cada item pode ter o modo CD/DVD ajustado quando os dois são válidos.
- Detecção do tipo de imagem antes da conversão: layout de setores (2048/2336/2352), ISO9660, UDF,
  imagem truncada e plataforma (PS2, PS1, PSP, GameCube, Wii, Xbox, Saturn, Sega CD, Dreamcast).
- Escolha do modo adequado (veja abaixo) e bloqueio de entradas inválidas, com a explicação.
- Serviço em primeiro plano (`dataSync`) com notificação de progresso, wake lock parcial e botão Cancelar.
- Progresso real (porcentagem do núcleo), velocidade, tempo decorrido, tempo restante,
  tamanho original e tamanho final (estimativa durante a conversão, valor real ao final).
- Cancelamento seguro: o núcleo para no próximo bloco, o arquivo parcial é apagado e a fila pode continuar depois.
- Verificação de integridade opcional (padrão: ligada), equivalente a `chdman verify`: todos os hunks são
  descomprimidos e os SHA-1 (dados e global) conferidos. O SHA-1 dos dados também é comparado com o da conversão.
- O arquivo original é aberto somente para leitura; nada no destino é sobrescrito (`Jogo (2).chd`, ...).
- Log detalhado por item (mensagens do chdman, trilhas, codecs, SHA-1), com botão para copiar.
- Interface em português, fundo preto puro (OLED), navegação por toque, D-pad e gamepad.

### Controles físicos

| Botão | Ação |
|---|---|
| D-pad / analógico | navega entre os controles (anel de foco branco) |
| A | confirma |
| B | voltar |
| Start | iniciar conversão |
| X | adicionar arquivos |
| Y | cancelar (com confirmação) |
| Select | log do item atual |

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

## Arquitetura

```
app/src/main/
├── cpp/
│   ├── mame/                  código do MAME copiado por tools/vendor_mame.sh (BSD-3-Clause)
│   ├── engine/
│   │   ├── chd_engine.cpp     inclui chdman.cpp inalterado; laço de compressão com progresso,
│   │   │                      cancelamento e E/S por descritor; verify; info
│   │   ├── fd_file.cpp        util::random_read_write sobre pread/pwrite de 64 bits
│   │   └── disc_probe.cpp     análise ISO9660/UDF/plataforma
│   ├── jni/chdconv_jni.cpp    ponte JNI (libchdconv.so)
│   ├── host/                  harness de testes (Linux): chdengine_cli e chdman de referência
│   └── CMakeLists.txt
└── java/com/conversorchd/
    ├── nativebridge/          NativeChd (externals) e NativeListener
    ├── core/                  análise, folhas .cue/.gdi, diretório de trabalho, execução, fila
    ├── service/               ConversionService (primeiro plano, wake lock, notificação)
    └── ui/                    MainActivity e componentes (sem AndroidX)
```

### Acesso a arquivos (Storage Access Framework)

- ISO em modo DVD: o descritor do SAF é lido diretamente pelo núcleo (sem cópia).
- CD: o parser do MAME trabalha com caminhos. Em vez de reabrir arquivos de `/storage` pelo caminho
  (o que pode ser negado a apps sem permissão ampla no Android 11+), o app cria no cache privado uma cópia
  da folha com nomes ASCII (`t01.bin`, `t02.wav`) e **espelhos esparsos** de cada trilha: mesmo tamanho,
  os primeiros 64 KiB reais (cabeçalhos WAVE) e o resto vazio. O parser lê os espelhos; os dados vêm dos
  descritores reais. Os espelhos ocupam poucos KiB, mesmo para trilhas de vários GB.
- Saída: `DocumentsContract.createDocument` na pasta escolhida, aberta em `rw`.

### Desempenho

- Compressão multinúcleo pela fila de trabalho do MAME (`osd_work_queue`), automática ou com número de núcleos fixo.
- Processamento por blocos: o compressor mantém 256 hunks em memória (≈1 MB em DVD, ≈5 MB em CD).
  A verificação usa um buffer de 4 MB. O consumo de RAM não depende do tamanho do arquivo.
- `-O3`, NEON no ARM64, alinhamento de 16 KB para Android 15+.

## Testes

### Motor nativo (`tests/native/run_tests.sh`)

Gera imagens de teste sintéticas (sem conteúdo protegido) e executa 46 verificações, todas aprovadas em
x86_64 (GCC e Clang/libc++) e em ARM64 (compilação cruzada executada no QEMU):

- DVD: CHD idêntico byte a byte ao `chdman createdvd`, SHA-1 dos dados igual ao SHA-1 da ISO,
  `verify` do motor e do chdman oficial, ida e volta com `extractdvd` idêntica.
- CD: `.cue` com trilhas separadas, `.bin` único com INDEX/pregap, áudio em `.wav`, ISO como CD.
  Todos idênticos ao `chdman createcd`, inclusive no modo do app (descritores e espelhos esparsos).
  Trilhas de dados e áudio idênticas após `extractcd`.
- Cancelamento (DVD, CD e CD por descritores) sem arquivo parcial, e nova conversão em seguida.
- Falta de espaço (código 3), tamanho inválido, `.cue` com arquivo ausente, `.cue` inválido,
  CHD corrompido reprovado, arquivo que não é CHD reprovado.
- Imagem de 4,3 GB com dados aleatórios depois da fronteira de 4 GiB: SHA-1 confere e a verificação passa.

Para executar localmente:

```bash
pip install pycdlib
cmake -S app/src/main/cpp -B build-host -G Ninja && cmake --build build-host
tests/native/run_tests.sh build-host --big
```

### App

- Testes de unidade (JVM): leitura e reescrita de `.cue`/`.gdi`, Latin-1, formatação.
- Testes instrumentados (`ConversionInstrumentedTest`), executados no emulador pelo CI (API 34 e 35):
  conversão DVD com SHA-1, `.cue` com áudio pelo mesmo caminho do serviço (WorkDir), cancelamento,
  entrada inválida, CHD corrompido e arquivo maior que 4 GB.

## Compilação

Requisitos: JDK 17, Android SDK 36, NDK 27.2.12479018, CMake 3.22.1.

```bash
./gradlew assembleDebug assembleRelease
# APKs em app/build/outputs/apk/
```

O workflow `.github/workflows/build.yml` roda tudo isso automaticamente e publica os APKs como artefato
`ConversorCHD-apk`, além dos logs de compilação e de testes.

### Assinatura

Sem configuração, o APK de release é assinado com a chave de debug do runner (instalável, mas a chave
muda a cada execução do CI). Para uma assinatura estável, crie os segredos do repositório
`CONVERSOR_KEYSTORE_BASE64` (keystore em base64), `CONVERSOR_KEYSTORE_PASSWORD`, `CONVERSOR_KEY_ALIAS`
e `CONVERSOR_KEY_PASSWORD`.

### Atualizar o código do MAME

```bash
git clone --depth 1 https://github.com/mamedev/mame.git /tmp/mame
tools/vendor_mame.sh /tmp/mame
tests/native/run_tests.sh <build-host>
```

## Limitações conhecidas

- `.nrg` (Nero) não é aceito: o parser do MAME lê a tabela do próprio arquivo de imagem, o que o esquema
  de espelhos não cobre.
- Arquivos que o provedor entrega como fluxo (nuvem/rede, sem tamanho fixo) são recusados. Copie-os
  para o armazenamento do aparelho antes.
- A verificação é sequencial (um núcleo), como no chdman.
- Ao cancelar uma conversão, a fila de trabalho do MAME deixa algumas dezenas de KB sem liberar até o
  processo terminar. É um comportamento do código original, que nunca é interrompido no meio pelo chdman.
- A fila fica na memória: se o sistema encerrar o app, os itens precisam ser adicionados de novo.
  O serviço em primeiro plano evita isso durante a conversão.
- Android 15+: serviços `dataSync` têm limite de 6 h por dia. Ao atingir o limite, a conversão é
  cancelada com segurança.

## Licenças

O código do app é distribuído sob a licença BSD-3-Clause (`LICENSE`). Componentes de terceiros e suas
licenças estão em `THIRD_PARTY_NOTICES.md`. Todos os arquivos do MAME usados são BSD-3-Clause (ou de
domínio público, no caso de `md5.cpp`). Nenhum arquivo GPL do MAME é compilado.

MAME é marca registrada de Gregory Ember. Este projeto não é afiliado ao MAMEdev.
