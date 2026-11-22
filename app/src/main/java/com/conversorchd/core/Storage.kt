package com.conversorchd.core

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import java.io.FileNotFoundException

/** Utilidades do Storage Access Framework. */
object Storage {

    /** Nome e tamanho de um documento. */
    fun describe(cr: ContentResolver, uri: Uri): InputFile? {
        return try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val name = c.getString(0) ?: uri.lastPathSegment ?: "arquivo"
                val size = if (c.isNull(1)) -1L else c.getLong(1)
                InputFile(uri, name, size)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Lista os filhos diretos de uma pasta (árvore SAF). */
    fun listTree(cr: ContentResolver, treeUri: Uri): List<InputFile> {
        val out = ArrayList<InputFile>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        try {
            cr.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(3) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val size = if (c.isNull(2)) -1L else c.getLong(2)
                    out.add(InputFile(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name, size))
                }
            }
        } catch (e: Exception) {
            // pasta inacessível: devolve o que foi possível ler
        }
        return out
    }

    fun treeDisplayName(cr: ContentResolver, treeUri: Uri): String {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        return try {
            cr.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            DocumentsContract.getTreeDocumentId(treeUri)
        }
    }

    fun takeReadPermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // provedor sem suporte a permissões persistentes: vale enquanto o app estiver aberto
        }
    }

    fun takeReadWritePermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
        }
    }

    fun releasePermission(context: Context, uri: Uri) {
        try {
            val held = context.contentResolver.persistedUriPermissions.any { it.uri == uri }
            if (held) context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
    }

    fun hasTreePermission(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }

    /** Resultado de [createPartialDocument]. */
    data class Output(val uri: Uri, val finalName: String, val currentName: String, val partial: Boolean)

    /**
     * Cria o arquivo de saída com o nome temporário "<nome>.chd.parcial". Só
     * depois da conversão (e da verificação) ele é renomeado para "<nome>.chd",
     * de modo que um arquivo com extensão .chd nunca fica incompleto no destino.
     * Se o provedor não suportar renomear, cria diretamente com o nome final.
     */
    @Throws(FileNotFoundException::class)
    fun createPartialDocument(cr: ContentResolver, treeUri: Uri, baseName: String, extension: String): Output {
        val existing = listTree(cr, treeUri).map { it.name.lowercase() }.toSet()
        var name = "$baseName.$extension"
        var n = 2
        while (name.lowercase() in existing || "$name$PARTIAL_SUFFIX".lowercase() in existing) {
            name = "$baseName ($n).$extension"
            n++
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val temp = DocumentsContract.createDocument(cr, parent, "application/octet-stream", name + PARTIAL_SUFFIX)
            ?: throw FileNotFoundException("não foi possível criar $name")
        if (supportsRename(cr, temp)) {
            return Output(temp, name, describe(cr, temp)?.name ?: name + PARTIAL_SUFFIX, true)
        }
        deleteDocument(cr, temp)
        val direct = DocumentsContract.createDocument(cr, parent, "application/octet-stream", name)
            ?: throw FileNotFoundException("não foi possível criar $name")
        val real = describe(cr, direct)?.name ?: name
        return Output(direct, real, real, false)
    }

    private fun supportsRename(cr: ContentResolver, uri: Uri): Boolean = try {
        cr.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
            c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0
        } ?: false
    } catch (e: Exception) {
        false
    }

    /** Renomeia; devolve o novo Uri (alguns provedores trocam o Uri) ou null em caso de falha. */
    fun rename(cr: ContentResolver, uri: Uri, name: String): Uri? = try {
        DocumentsContract.renameDocument(cr, uri, name)
    } catch (e: Exception) {
        null
    }

    const val PARTIAL_SUFFIX = ".parcial"

    fun deleteDocument(cr: ContentResolver, uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(cr, uri)
    } catch (e: Exception) {
        false
    }

    /** Tamanho real pelo descritor (fstat); -1 se não for um arquivo regular. */
    fun regularFileSize(pfd: ParcelFileDescriptor): Long = try {
        val st = Os.fstat(pfd.fileDescriptor)
        if (OsConstants.S_ISREG(st.st_mode)) st.st_size else -1L
    } catch (e: Exception) {
        -1L
    }

    /** Espaço livre no sistema de arquivos do descritor; -1 se desconhecido. */
    fun freeSpace(pfd: ParcelFileDescriptor): Long = try {
        val vfs = Os.fstatvfs(pfd.fileDescriptor)
        vfs.f_bavail * vfs.f_frsize
    } catch (e: Exception) {
        -1L
    }

    fun readSmallText(cr: ContentResolver, uri: Uri, limit: Int = 1 shl 20): ByteArray? = try {
        cr.openInputStream(uri)?.use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > limit) return null
            }
            buf.toByteArray()
        }
    } catch (e: Exception) {
        null
    }

    fun baseName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot + 1).lowercase() else ""
    }
}
