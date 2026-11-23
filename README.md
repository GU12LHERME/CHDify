# CHDify

<img src="tools/icon/chdify_512.png" width="96" align="right" alt="Ícone do CHDify">

Aplicativo Android nativo que converte imagens de disco para **CHD** e de volta, direto no aparelho,
usando o código oficial do **chdman / biblioteca CHD do MAME** compilado para ARM64.
Funciona offline, sem root, sem Termux e sem aplicativos adicionais.

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

## Arquitetura

```
app/src/main/
├── cpp/
│   ├── mame/                  só os arquivos do MAME usados pelo build (lista em tools/mame_files.txt), BSD-3-Clause
│   ├── engine/
│   │   ├── chd_engine.cpp     inclui chdman.cpp inalterado; laço de compressão com progresso,
│   │   │                      cancelamento e E/S por descritor; verify; extração; recompactação; info
│   │   ├── fd_file.cpp        util::random_read_write sobre pread/pwrite de 64 bits
│   │   └── disc_probe.cpp     análise ISO9660/UDF/plataforma
│   ├── jni/chdconv_jni.cpp    ponte JNI (libchdconv.so)
│   ├── host/                  harness de testes (Linux): chdengine_cli e chdman de referência
│   └── CMakeLists.txt
└── java/com/conversorchd/
    ├── nativebridge/          NativeChd (externals) e NativeListener
    ├── core/                  análise, folhas .cue/.gdi, fila persistente, execução, exclusão segura, histórico, M3U
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
- Processamento por blocos: o compressor mantém 256 hunks em memória (de ≈1 MB a 16 MB, conforme o hunk).
  A verificação usa 2 blocos de 2 MB por thread. O consumo de RAM não depende do tamanho do arquivo (picos medidos de 15 a 37 MB).
- `-O3`, NEON no ARM64, alinhamento de 16 KB para Android 15+.

## Testes

### Motor nativo (`tests/native/run_tests.sh`)

Gera imagens de teste sintéticas (sem conteúdo protegido) e executa 86 verificações, todas aprovadas em
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
- Perfis (zlib 16K, hunks de 32K e 64K, CD 8/16 quadros): verificados e extraídos pelo chdman oficial, com dados idênticos.
- Verificação paralela: mesmo SHA-1 com 1 e 4 threads, cancelamento, corrupção detectada em vários pontos do arquivo.
- Extração pelo motor: ISO, `.cue`/`.bin` e `.gdi` idênticos aos do chdman oficial (inclusive GD-ROM) e trilhas
  idênticas às originais; recompactação equivalente a `chdman copy`.

Sanitizadores (CHDify 1.0.0): a suíte completa passa com ASan + UBSan (86/86). Os únicos avisos vêm de código
original do MAME e foram suprimidos apenas na análise: alinhamento de 4 bytes dos alocadores de codec do MAME
(zlib/LZMA; acesso desalinhado a memória normal é permitido em ARM64 e x86-64) e corridas apontadas pelo TSan no
compressor `chd_file_compressor` / `osdsync` (o mesmo código do chdman oficial). Com essas supressões, o TSan não
encontra nenhuma corrida no código do CHDify (verificação e extração paralelas, recompactação).

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
  entrada inválida, CHD corrompido, arquivo maior que 4 GB, extração DVD e CD (trilhas idênticas às
  originais), recompactação, persistência da fila em JSON e regras de exclusão segura.

## Compilação

Requisitos: JDK 17, Android SDK 36, NDK 27.2.12479018, CMake 3.22.1.

```bash
./gradlew assembleDebug assembleRelease
# APKs em app/build/outputs/apk/
```

O workflow `.github/workflows/build.yml` roda tudo isso automaticamente e publica os APKs como artefato
`CHDify-apk` e na Release `ultima-versao` (`CHDify-arm64-v8a.apk`), além dos logs de compilação e de testes.

### Assinatura

Para que cada versão instale por cima da anterior, o APK precisa ser sempre assinado com a mesma chave.
Configure em *Settings → Secrets and variables → Actions* do repositório:

| Segredo | Conteúdo |
|---|---|
| `CONVERSOR_KEYSTORE_BASE64` | a keystore em base64 |
| `CONVERSOR_KEYSTORE_PASSWORD` | senha da keystore |
| `CONVERSOR_KEY_ALIAS` | alias da chave |
| `CONVERSOR_KEY_PASSWORD` | senha da chave |

A keystore e as senhas nunca devem ser colocadas no repositório (`*.jks` está no `.gitignore`). Guarde uma
cópia de segurança: sem ela não é possível publicar atualizações. Sem os segredos, o CI assina com uma chave
temporária de depuração e avisa isso nas notas da Release.

### Atualizar o código do MAME

```bash
git clone --depth 1 https://github.com/mamedev/mame.git /tmp/mame
tools/vendor_mame.sh /tmp/mame      # copia os arquivos de tools/mame_files.txt
tests/native/run_tests.sh <build-host>
```

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
