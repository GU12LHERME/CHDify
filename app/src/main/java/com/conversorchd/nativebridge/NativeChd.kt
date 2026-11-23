package com.conversorchd.nativebridge

/**
 * Callback chamado pelo código nativo durante uma operação.
 * Os métodos são invocados por nome via JNI: não renomear.
 */
interface NativeListener {
    /**
     * stage: 0 = compressão, 1 = verificação. fraction: 0..1.
     * written: bytes já gravados no CHD (tamanho comprimido até o momento; 0 na verificação).
     */
    fun onProgress(stage: Int, fraction: Double, ratio: Double, done: Long, total: Long, written: Long)

    fun onLog(message: String)
}

/**
 * Ponte para libchdconv.so (núcleo CHD do MAME + motor de conversão).
 * Todas as funções de conversão são bloqueantes e devem rodar fora da thread principal.
 * As respostas são objetos JSON (ver [com.conversorchd.core.NativeResult]).
 */
object NativeChd {
    init {
        System.loadLibrary("chdconv")
    }

    @JvmStatic external fun coreVersion(): String

    /** Analisa cabeçalhos da imagem (ISO9660/UDF/plataforma). */
    @JvmStatic external fun probeImage(fd: Int): String

    /** Interpreta um .cue/.gdi/.iso com o parser oficial do MAME, sem converter. */
    @JvmStatic external fun probeToc(path: String): String

    /** Informações de um arquivo CHD. */
    @JvmStatic external fun chdInfo(fd: Int): String

    /** Solicita o cancelamento da operação em andamento. */
    @JvmStatic external fun cancel()

    /**
     * ISO (setores de 2048 bytes) -> CHD de DVD. Equivale a "chdman createdvd".
     * hunkBytes = 0 e codecs vazio usam os padrões do chdman.
     */
    @JvmStatic external fun convertDvd(inFd: Int, outFd: Int, threads: Int, hunkBytes: Int, codecs: String, listener: NativeListener?): String

    /** CUE/GDI/ISO -> CHD de CD. Equivale a "chdman createcd". */
    @JvmStatic external fun convertCd(
        tocPath: String,
        trackPaths: Array<String>,
        trackFds: IntArray,
        outFd: Int,
        threads: Int,
        hunkBytes: Int,
        codecs: String,
        listener: NativeListener?,
    ): String

    /**
     * CHD de DVD -> ISO (equivale a "chdman extractdvd"). O SHA-1 dos dados extraídos
     * é comparado com o gravado no CHD; sucesso significa cópia verificada.
     */
    @JvmStatic external fun extractDvd(chdFd: Int, outFd: Int, threads: Int, listener: NativeListener?): String

    /**
     * Plano de extração de CD/GD-ROM: nomes dos arquivos, tamanhos e o texto exato
     * da folha (.cue, ou .gdi para GD-ROM), como o "chdman extractcd" geraria.
     */
    @JvmStatic external fun planCdExtract(chdFd: Int, baseName: String): String

    /** Executa o plano: outFds na mesma ordem de "files". */
    @JvmStatic external fun extractCd(chdFd: Int, baseName: String, outFds: IntArray, listener: NativeListener?): String

    /** Recompacta um CHD (equivale a "chdman copy"); hunk 0 e codecs vazio = padrões do tipo de mídia. */
    @JvmStatic external fun recompress(inFd: Int, outFd: Int, threads: Int, hunkBytes: Int, codecs: String, listener: NativeListener?): String

    /** Verificação completa do CHD (equivale a "chdman verify"), com descompressão em paralelo. */
    @JvmStatic external fun verify(fd: Int, threads: Int, listener: NativeListener?): String
}
