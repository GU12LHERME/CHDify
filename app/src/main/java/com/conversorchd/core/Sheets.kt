package com.conversorchd.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Leitura e reescrita de folhas de trilhas (.cue / .gdi).
 *
 * O app não reabre os arquivos de trilha pelo caminho: cria um diretório de
 * trabalho com uma cópia da folha em que cada arquivo referenciado recebe um
 * apelido ASCII simples ("t01.bin"). Isso evita problemas de codificação
 * (folhas em Latin-1, acentos, espaços) e deixa o parser oficial do MAME
 * interpretar trilhas, índices e pregaps exatamente como no chdman.
 */
object Sheets {

    private val CUE_FILE = Regex("^(\\s*FILE\\s+)(?:\"([^\"]*)\"|(\\S+))(.*)$", RegexOption.IGNORE_CASE)

    fun decode(bytes: ByteArray): String {
        var start = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) start = 3
        val body = ByteBuffer.wrap(bytes, start, bytes.size - start)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(body).toString()
        } catch (e: Exception) {
            String(bytes, start, bytes.size - start, StandardCharsets.ISO_8859_1)
        }
    }

    /** Nome base (sem diretórios) para comparação com os arquivos escolhidos. */
    fun leafName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    // ------------------------------------------------------------------ CUE

    fun cueFiles(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (line in text.lines()) {
            val m = CUE_FILE.matchEntire(line.trimEnd('\r')) ?: continue
            val name = m.groupValues[2].ifEmpty { m.groupValues[3] }
            if (name.isNotBlank()) out.add(name)
        }
        return out.toList()
    }

    fun rewriteCue(text: String, aliases: Map<String, String>): String {
        val sb = StringBuilder()
        for (raw in text.lines()) {
            val line = raw.trimEnd('\r')
            val m = CUE_FILE.matchEntire(line)
            if (m != null) {
                val name = m.groupValues[2].ifEmpty { m.groupValues[3] }
                val alias = aliases[name] ?: name
                sb.append(m.groupValues[1]).append('"').append(alias).append('"').append(m.groupValues[4])
            } else {
                sb.append(line)
            }
            sb.append("\r\n")
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ GDI

    /** Divide uma linha respeitando aspas. */
    private fun tokens(line: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < line.length) {
            while (i < line.length && line[i].isWhitespace()) i++
            if (i >= line.length) break
            if (line[i] == '"') {
                val end = line.indexOf('"', i + 1).let { if (it < 0) line.length else it }
                out.add(line.substring(i + 1, end))
                i = end + 1
            } else {
                val start = i
                while (i < line.length && !line[i].isWhitespace()) i++
                out.add(line.substring(start, i))
            }
        }
        return out
    }

    fun gdiFiles(text: String): List<String> {
        val out = LinkedHashSet<String>()
        text.lines().drop(1).forEach { line ->
            val t = tokens(line.trimEnd('\r'))
            if (t.size >= 5) out.add(t[4])
        }
        return out.toList()
    }

    fun rewriteGdi(text: String, aliases: Map<String, String>): String {
        val lines = text.lines()
        val sb = StringBuilder()
        lines.forEachIndexed { idx, raw ->
            val line = raw.trimEnd('\r')
            val t = tokens(line)
            if (idx > 0 && t.size >= 5) {
                val fields = t.toMutableList()
                fields[4] = aliases[t[4]] ?: t[4]
                sb.append(fields.joinToString(" "))
            } else {
                sb.append(line)
            }
            sb.append("\r\n")
        }
        return sb.toString()
    }

    /** Apelidos estáveis "t01.bin", "t02.wav"... para cada arquivo referenciado. */
    fun aliasesFor(names: List<String>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        names.forEachIndexed { i, n ->
            val ext = Storage.extension(leafName(n)).filter { it.isLetterOrDigit() }.ifEmpty { "bin" }
            map[n] = String.format(java.util.Locale.ROOT, "t%02d.%s", i + 1, ext)
        }
        return map
    }
}
