package com.nyvibe.droid

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale

internal data class WorkspaceItem(
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String?
)

internal object WorkspaceRepository {
    const val MAX_EDITABLE_BYTES = 1024 * 1024L
    private const val PREFERENCES = "workspace_settings"
    private const val ROOT_URI_KEY = "root_uri"

    private val textExtensions = setOf(
        "c", "cc", "cfg", "conf", "cpp", "css", "csv", "dart", "gml", "go", "gradle",
        "h", "hpp", "htm", "html", "ini", "java", "js", "json", "kt", "kts", "less",
        "log", "lua", "md", "mjs", "mk", "mod", "properties", "py", "rb", "rs", "scss",
        "sh", "sql", "sum", "svg", "toml", "ts", "tsx", "txt", "xml", "yaml", "yml",
        "jsx"
    )
    private val textNames = setOf(
        ".env", ".editorconfig", ".gitattributes", ".gitignore", ".gitmodules",
        "dockerfile", "makefile", "license", "readme", "readme.md"
    )

    fun loadRootUri(context: Context): String? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ROOT_URI_KEY, null)

    fun saveRootUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(ROOT_URI_KEY, uri.toString())
            .apply()
    }

    fun rootName(context: Context, uri: String): String {
        val root = treeDocument(context, uri)
        if (!root.isDirectory) throw IOException("Le dossier choisi n'est plus accessible.")
        return root.name?.takeIf { it.isNotBlank() } ?: "Workspace"
    }

    fun list(context: Context, folderUri: String): List<WorkspaceItem> {
        val folder = treeDocument(context, folderUri)
        if (!folder.isDirectory) throw IOException("Ce document n'est pas un dossier.")
        return folder.listFiles()
            .mapNotNull { document ->
                val name = document.name ?: return@mapNotNull null
                WorkspaceItem(
                    uri = document.uri.toString(),
                    name = name,
                    isDirectory = document.isDirectory,
                    size = document.length(),
                    lastModified = document.lastModified(),
                    mimeType = document.type
                )
            }
            .sortedWith(
                compareBy<WorkspaceItem> { !it.isDirectory }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
    }

    fun readText(context: Context, item: WorkspaceItem): String {
        if (item.isDirectory) throw IOException("Un dossier ne peut pas être ouvert dans l'éditeur.")
        if (!isTextFile(item.name, item.mimeType)) {
            throw IOException("Ce type de fichier n'est pas reconnu comme du texte éditable.")
        }
        if (item.size > MAX_EDITABLE_BYTES) {
            throw IOException("Le fichier dépasse la limite d'édition de 1 Mio.")
        }

        val uri = Uri.parse(item.uri)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Impossible d'ouvrir ce fichier.")
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_EDITABLE_BYTES) {
                    throw IOException("Le fichier dépasse la limite d'édition de 1 Mio.")
                }
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            if (bytes.any { it == 0.toByte() }) {
                throw IOException("Le fichier semble binaire ; il n'est pas ouvert dans l'éditeur texte.")
            }
            return String(bytes, Charsets.UTF_8)
        }
    }

    fun saveText(context: Context, item: WorkspaceItem, content: String) {
        if (item.isDirectory) throw IOException("Impossible d'écrire dans un dossier.")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_EDITABLE_BYTES) {
            throw IOException("Le fichier dépasse la limite de sauvegarde de 1 Mio.")
        }
        val uri = Uri.parse(item.uri)
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Impossible d'écrire dans ce fichier. Vérifie les droits du dossier.")
        output.use { it.write(bytes) }
    }

    fun createTextFile(context: Context, parentUri: String, requestedName: String): WorkspaceItem {
        val name = requestedName.trim()
        require(name.isNotBlank()) { "Donne un nom au fichier." }
        require(name.length <= 120) { "Le nom du fichier doit faire au maximum 120 caractères." }
        require(name != "." && name != "..") { "Ce nom de fichier n'est pas autorisé." }
        require(name.none { it == '/' || it == '\\' || it.isISOControl() }) {
            "Le nom ne peut pas contenir de séparateur de chemin ou de caractère de contrôle."
        }
        if (!isTextFile(name, null)) {
            throw IOException("La création est limitée aux fichiers texte pris en charge.")
        }

        val parent = treeDocument(context, parentUri)
        if (!parent.isDirectory) throw IOException("Le dossier courant n'est plus accessible.")
        if (parent.findFile(name) != null) throw IOException("Un fichier ou dossier portant ce nom existe déjà.")
        val child = parent.createFile(mimeTypeFor(name), name)
            ?: throw IOException("Android n'a pas pu créer le fichier dans ce dossier.")
        return WorkspaceItem(
            uri = child.uri.toString(),
            name = child.name ?: name,
            isDirectory = false,
            size = child.length(),
            lastModified = child.lastModified(),
            mimeType = child.type
        )
    }

    fun isTextFile(name: String, mimeType: String?): Boolean {
        val normalized = name.lowercase(Locale.ROOT)
        if (normalized in textNames) return true
        if (normalized.substringAfterLast('.', missingDelimiterValue = "").let { it in textExtensions }) {
            return true
        }
        return mimeType?.lowercase(Locale.ROOT)?.let {
            it.startsWith("text/") ||
                it == "application/json" ||
                it == "application/xml" ||
                it == "application/yaml" ||
                it == "application/x-yaml" ||
                it == "application/javascript"
        } ?: false
    }

    private fun mimeTypeFor(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "kt", "kts", "java", "js", "ts", "tsx", "jsx", "py", "rs", "go", "c", "h", "cpp", "hpp", "sh" -> "text/plain"
            "json" -> "application/json"
            "xml", "html", "svg" -> "application/xml"
            "md", "txt", "properties", "gradle", "yml", "yaml", "toml", "css", "sql" -> "text/plain"
            else -> "text/plain"
        }
    }

    private fun treeDocument(context: Context, uri: String): DocumentFile {
        val parsed = Uri.parse(uri)
        return DocumentFile.fromTreeUri(context, parsed)
            ?: throw IOException("Le dossier est inaccessible. Sélectionne-le à nouveau.")
    }
}
