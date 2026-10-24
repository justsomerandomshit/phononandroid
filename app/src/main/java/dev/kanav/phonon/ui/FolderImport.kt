package dev.kanav.phonon.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File

/** Copies a checkpoint folder the user picked (a document tree) into a local folder. */
class FolderImport(private val resolver: ContentResolver, private val onFile: (String) -> Unit) {
    fun copy(tree: Uri, into: File) {
        val root = children(tree, DocumentsContract.getTreeDocumentId(tree))
        // The library loads the GGUF when both are there, so the other is not worth the space.
        val skip = if ("model.q8.gguf" in root) "model.safetensors" else null
        for ((name, entry) in root) {
            val (id, isDir) = entry
            when {
                !isDir && name in FILES && name != skip -> copy(tree, id, File(into, name))
                isDir && name in VOICE_DIRS -> for ((voice, child) in children(tree, id)) {
                    // The name comes from the document provider: keep it inside the voices folder.
                    if (!child.second && voice.endsWith(".safetensors") && '/' !in voice) {
                        copy(tree, child.first, File(into, "$name/$voice"))
                    }
                }
            }
        }
    }

    /** A folder's entries by name: each one's document id and whether it is a folder. */
    private fun children(tree: Uri, id: String): Map<String, Pair<String, Boolean>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val out = mutableMapOf<String, Pair<String, Boolean>>()
        resolver.query(uri, columns, null, null, null)?.use {
            while (it.moveToNext()) out[it.getString(1)] = it.getString(0) to (it.getString(2) == Document.MIME_TYPE_DIR)
        }
        return out
    }

    private fun copy(tree: Uri, id: String, to: File) {
        onFile(to.name)
        to.parentFile?.mkdirs()
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        val input = resolver.openInputStream(uri) ?: error("cannot read ${to.name}")
        input.use { from -> to.outputStream().use { from.copyTo(it, 1 shl 20) } }
    }

    private companion object {
        /** What a checkpoint folder holds. */
        val FILES = setOf("config.json", "tokenizer.json", "model.q8.gguf", "model.safetensors", "default-voice.safetensors")
        val VOICE_DIRS = setOf("voices", "embeddings")
    }
}
