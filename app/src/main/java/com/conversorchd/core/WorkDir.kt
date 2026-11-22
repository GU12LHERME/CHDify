package com.conversorchd.core

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Prepara a entrada de uma conversão de CD.
 *
 * O parser de trilhas do MAME (cdrom_file::parse_toc) trabalha com caminhos.
 * Para não reabrir arquivos do armazenamento compartilhado pelo caminho (o que
 * pode ser negado no Android 11+), o app cria, no cache privado:
 *   - uma cópia da folha (.cue/.gdi) com nomes de arquivo apelidados;
 *   - um "espelho" esparso de cada trilha, com o tamanho exato do original e
 *     os primeiros 64 KiB reais (necessários para cabeçalhos WAVE).
 * O parser lê os espelhos; o motor nativo lê os dados pelos descritores reais.
 * Os espelhos ocupam poucos KiB em disco, independentemente do tamanho da trilha.
 */
class PreparedCd(
    val dir: File,
    val tocPath: String,
    val trackPaths: Array<String>,
    private val pfds: List<ParcelFileDescriptor>,
) : Closeable {
    val trackFds: IntArray get() = pfds.map { it.fd }.toIntArray()

    override fun close() {
        pfds.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }
}

object WorkDir {
    private const val HEAD_BYTES = 64 * 1024

    fun root(context: Context) = File(context.cacheDir, "trabalho")

    /** Remove restos de execuções anteriores (ex.: processo encerrado no meio). */
    fun cleanStale(context: Context) {
        root(context).listFiles()?.forEach { it.deleteRecursively() }
    }

    @Throws(IOException::class)
    fun prepareCd(context: Context, job: Job): PreparedCd {
        val dir = File(root(context), "job-${job.id}-${System.nanoTime()}")
        if (!dir.mkdirs()) throw IOException("não foi possível criar ${dir.path}")
        val pfds = ArrayList<ParcelFileDescriptor>()
        try {
            val cr = context.contentResolver
            val paths = ArrayList<String>()
            val toc: File
            if (job.kind == SourceKind.IMAGE) {
                // ISO tratada como CD: uma única trilha de dados (parse_iso do MAME)
                val pfd = cr.openFileDescriptor(job.main.uri, "r") ?: throw IOException("não foi possível abrir ${job.main.name}")
                pfds.add(pfd)
                toc = File(dir, "imagem.iso")
                mirror(pfd, toc)
                paths.add(toc.absolutePath)
            } else {
                val bytes = Storage.readSmallText(cr, job.main.uri) ?: throw IOException("não foi possível ler ${job.main.name}")
                val text = Sheets.decode(bytes)
                val names = job.tracks.map { it.nameInSheet }
                val aliases = Sheets.aliasesFor(names)
                for (t in job.tracks) {
                    val file = t.file ?: throw IOException("arquivo ausente: ${t.nameInSheet}")
                    val pfd = cr.openFileDescriptor(file.uri, "r") ?: throw IOException("não foi possível abrir ${file.name}")
                    pfds.add(pfd)
                    val m = File(dir, aliases.getValue(t.nameInSheet))
                    mirror(pfd, m)
                    paths.add(m.absolutePath)
                }
                val rewritten = if (job.kind == SourceKind.CUE) Sheets.rewriteCue(text, aliases) else Sheets.rewriteGdi(text, aliases)
                toc = File(dir, if (job.kind == SourceKind.CUE) "disco.cue" else "disco.gdi")
                toc.writeText(rewritten, Charsets.UTF_8)
            }
            return PreparedCd(dir, toc.absolutePath, paths.toTypedArray(), pfds)
        } catch (e: Exception) {
            pfds.forEach { runCatching { it.close() } }
            dir.deleteRecursively()
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    private fun mirror(pfd: ParcelFileDescriptor, dest: File) {
        val size = Storage.regularFileSize(pfd)
        if (size < 0) throw IOException("${dest.name}: não é um arquivo comum (fluxo ou nuvem não suportados)")
        val head = ByteArray(minOf(HEAD_BYTES.toLong(), size).toInt())
        var got = 0
        while (got < head.size) {
            val n = Os.pread(pfd.fileDescriptor, head, got, head.size - got, got.toLong())
            if (n <= 0) break
            got += n
        }
        RandomAccessFile(dest, "rw").use { raf ->
            raf.write(head, 0, got)
            raf.setLength(size) // restante esparso: não ocupa espaço
        }
    }
}
