package com.conversorchd.core

/**
 * Perfis de compactação. Todos são sem perdas e usam apenas codecs e tamanhos
 * de hunk aceitos pelo chdman oficial e lidos pelo libchdr (base dos emuladores
 * com suporte a CHD). Valores escolhidos a partir de medições (tests/bench):
 *
 *  DVD (imagem de 333 MB, 2 núcleos)
 *   - Rápido:      zlib, hunk 16 KiB  ->  9,3 s, 53,3%  (4,4x mais rápido e menor que o padrão)
 *   - Equilibrado: padrão do chdman   -> 40,6 s, 54,0%  (lzma/zlib/huff/flac, hunk 4 KiB)
 *   - Máximo:      padrão, hunk 32 KiB -> 43,1 s, 50,4%
 *  CD (302 MB com 2 trilhas de áudio)
 *   - Rápido:      cdzl+cdfl, 8 quadros   -> 17,5 s, 42,6%
 *   - Equilibrado: padrão do chdman        -> 33,0 s, 41,0%  (cdlz/cdzl/cdfl, 8 quadros)
 *   - Máximo:      padrão, 16 quadros      -> 36,8 s, 40,2%
 *
 * O zstd foi medido e descartado: no MAME ficou mais lento que o lzma e gerou
 * arquivos maiores, além de não ser lido por leitores de CHD mais antigos.
 */
enum class Profile { FAST, BALANCED, MAX }

data class CompressionParams(val codecs: String, val hunkBytes: Int) {
    /** Descrição curta para o log. */
    fun describe(mode: DiscMode): String {
        val c = codecs.ifEmpty { if (mode == DiscMode.CD) "cdlz,cdzl,cdfl" else "lzma,zlib,huff,flac" }
        val h = if (hunkBytes > 0) hunkBytes else if (mode == DiscMode.CD) CD_FRAME * 8 else 4096
        val hs = if (mode == DiscMode.CD) "${h / CD_FRAME} quadros ($h B)" else "${h / 1024} KiB"
        return "$c, hunk $hs"
    }

    companion object {
        const val CD_FRAME = 2448

        /** Tamanhos de hunk oferecidos nas opções avançadas (0 = do perfil). */
        val DVD_HUNKS = listOf(0, 4096, 8192, 16384, 32768, 65536)
        val CD_HUNKS = listOf(0, CD_FRAME * 8, CD_FRAME * 16, CD_FRAME * 32)

        fun of(profile: Profile, mode: DiscMode, dvdHunkOverride: Int = 0, cdHunkOverride: Int = 0): CompressionParams {
            val base = when (mode) {
                DiscMode.DVD -> when (profile) {
                    Profile.FAST -> CompressionParams("zlib", 16384)
                    Profile.BALANCED -> CompressionParams("", 0) // exatamente o chdman createdvd
                    Profile.MAX -> CompressionParams("lzma,zlib,huff,flac", 32768)
                }
                DiscMode.CD -> when (profile) {
                    Profile.FAST -> CompressionParams("cdzl,cdfl", 0)
                    Profile.BALANCED -> CompressionParams("", 0) // exatamente o chdman createcd
                    Profile.MAX -> CompressionParams("", CD_FRAME * 16)
                }
            }
            val override = if (mode == DiscMode.DVD) dvdHunkOverride else cdHunkOverride
            return if (override > 0) base.copy(hunkBytes = override) else base
        }
    }
}
