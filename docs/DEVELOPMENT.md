# Documentação técnica

[Voltar ao README](../README.md)

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

