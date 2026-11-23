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

/** Uma pasta dentro de uma árvore SAF concedida pelo usuário. */
data class Folder(val tree: Uri, val docUri: Uri, val path: String) {
    val docId: String get() = DocumentsContract.getDocumentId(docUri)
}

/** Utilidades do Storage Access Framework (armazenamento interno, cartão SD e USB). */
object Storage {

    const val PARTIAL_SUFFIX = ".parcial"

    /** Nome e tamanho de um documento. */
    fun describe(cr: ContentResolver, uri: Uri): InputFile? = try {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val name = c.getString(0) ?: uri.lastPathSegment ?: "arquivo"
            val size = if (c.isNull(1)) -1L else c.getLong(1)
            InputFile(uri, name, size)
        }
    } catch (e: Exception) {
        null
    }

    fun root(tree: Uri): Folder =
        Folder(tree, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), "")

    private data class Child(val file: InputFile, val isDir: Boolean, val docId: String)

    private fun children(cr: ContentResolver, folder: Folder): List<Child> {
        val out = ArrayList<Child>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(folder.tree, folder.docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        try {
            cr.query(uri, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val size = if (c.isNull(2)) -1L else c.getLong(2)
                    val dir = c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR
                    out.add(Child(InputFile(DocumentsContract.buildDocumentUriUsingTree(folder.tree, id), name, size), dir, id))
                }
            }
        } catch (e: Exception) {
            // pasta inacessível (permissão perdida, mídia removida): devolve o que foi possível ler
        }
        return out
    }

    /** Arquivos (não pastas) de uma pasta. */
    fun listFiles(cr: ContentResolver, folder: Folder): List<InputFile> = children(cr, folder).filter { !it.isDir }.map { it.file }

    /** Arquivos da raiz de uma árvore. */
    fun listTree(cr: ContentResolver, treeUri: Uri): List<InputFile> = listFiles(cr, root(treeUri))

    /** Subpasta com o nome dado; criada se não existir. */
    @Throws(FileNotFoundException::class)
    fun childFolder(cr: ContentResolver, parent: Folder, name: String): Folder {
        val clean = safeName(name)
        children(cr, parent).firstOrNull { it.isDir && it.file.name.equals(clean, ignoreCase = true) }?.let {
            return Folder(parent.tree, it.file.uri, join(parent.path, it.file.name))
        }
        val created = DocumentsContract.createDocument(cr, parent.docUri, DocumentsContract.Document.MIME_TYPE_DIR, clean)
            ?: throw FileNotFoundException("não foi possível criar a pasta $clean")
        return Folder(parent.tree, created, join(parent.path, describe(cr, created)?.name ?: clean))
    }

    /** Cria sempre uma pasta nova (nunca reutiliza uma existente). */
    fun newFolder(cr: ContentResolver, parent: Folder, name: String): Folder {
        val clean = safeName(name)
        val created = DocumentsContract.createDocument(cr, parent.docUri, DocumentsContract.Document.MIME_TYPE_DIR, clean)
            ?: throw FileNotFoundException("não foi possível criar a pasta $clean")
        return Folder(parent.tree, created, join(parent.path, describe(cr, created)?.name ?: clean))
    }

    private fun join(a: String, b: String) = if (a.isEmpty()) b else "$a/$b"

    /** Remove caracteres que provedores de arquivos costumam recusar. */
    fun safeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimEnd('.').ifEmpty { "sem_nome" }

    fun treeDisplayName(cr: ContentResolver, treeUri: Uri): String {
        val docUri = root(treeUri).docUri
        return try {
            cr.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            DocumentsContract.getTreeDocumentId(treeUri)
        }
    }

    // ------------------------------------------------------------------ permissões

    /** Leitura e (quando o provedor conceder) escrita, necessária para excluir originais. */
    fun takeInputPermission(context: Context, uri: Uri) {
        val cr = context.contentResolver
        try {
            cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            try {
                cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e2: SecurityException) {
                // provedor sem permissões persistentes: vale enquanto o app estiver aberto
            }
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
            val held = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
            var flags = 0
            if (held.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (held.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
        }
    }

    fun hasTreePermission(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }

    // ------------------------------------------------------------------ saída

    /** Resultado de [createPartialDocument]. */
    data class Output(val uri: Uri, val finalName: String, val currentName: String, val partial: Boolean)

    /** Já existe um arquivo com esse nome na pasta? */
    fun exists(cr: ContentResolver, folder: Folder, name: String): Boolean =
        listFiles(cr, folder).any { it.name.equals(name, ignoreCase = true) }

    /**
     * Nome livre na pasta: "Nome.ext", "Nome (2).ext"... considerando também o
     * nome temporário ".parcial". Nada existente é sobrescrito.
     */
    fun uniqueName(existing: Set<String>, base: String, ext: String): String {
        var name = "$base.$ext"
        var n = 2
        while (name.lowercase() in existing || "$name$PARTIAL_SUFFIX".lowercase() in existing) {
            name = "$base ($n).$ext"
            n++
        }
        return name
    }

    /**
     * Cria o arquivo de saída com o nome temporário "<nome>.<ext>.parcial". Só
     * depois da operação (e da verificação) ele é renomeado para o nome final,
     * de modo que um arquivo com o nome definitivo nunca fica incompleto no
     * destino. Se o provedor não suportar renomear, cria com o nome final.
     */
    @Throws(FileNotFoundException::class)
    fun createPartialDocument(cr: ContentResolver, folder: Folder, finalName: String, mime: String = "application/octet-stream"): Output {
        val temp = DocumentsContract.createDocument(cr, folder.docUri, mime, finalName + PARTIAL_SUFFIX)
            ?: throw FileNotFoundException("não foi possível criar $finalName")
        if (supportsRename(cr, temp)) {
            return Output(temp, finalName, describe(cr, temp)?.name ?: finalName + PARTIAL_SUFFIX, true)
        }
        deleteDocument(cr, temp)
        val direct = DocumentsContract.createDocument(cr, folder.docUri, mime, finalName)
            ?: throw FileNotFoundException("não foi possível criar $finalName")
        val real = describe(cr, direct)?.name ?: finalName
        return Output(direct, real, real, false)
    }

    private fun supportsRename(cr: ContentResolver, uri: Uri): Boolean = try {
        cr.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
            c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0
        } ?: false
    } catch (e: Exception) {
        false
    }

    /** O documento aceita exclusão (flag do provedor)? */
    fun supportsDelete(cr: ContentResolver, uri: Uri): Boolean = try {
        cr.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
            c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
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

    fun deleteDocument(cr: ContentResolver, uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(cr, uri)
    } catch (e: Exception) {
        false
    }

    /** Tamanho atual de um documento (consulta ao provedor), -1 se inacessível. */
    fun documentSize(cr: ContentResolver, uri: Uri): Long = describe(cr, uri)?.size ?: -1L

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

    /**
     * Espaço livre no destino. O SAF não expõe isso para pastas, então cria um
     * arquivo vazio temporário, consulta o sistema de arquivos e o apaga.
     */
    fun freeSpaceOf(cr: ContentResolver, folder: Folder): Long {
        val probe = try {
            DocumentsContract.createDocument(cr, folder.docUri, "application/octet-stream", ".chdify-espaco")
        } catch (e: Exception) {
            null
        } ?: return -1L
        return try {
            cr.openFileDescriptor(probe, "r")?.use { freeSpace(it) } ?: -1L
        } catch (e: Exception) {
            -1L
        } finally {
            deleteDocument(cr, probe)
        }
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

    /** Grava um texto pequeno (folha .cue/.gdi, .m3u) num documento. */
    fun writeText(cr: ContentResolver, uri: Uri, text: String): Boolean = try {
        cr.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)); true } ?: false
    } catch (e: Exception) {
        false
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
