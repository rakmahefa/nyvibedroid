package com.nyvibe.droid

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    private const val PROJECTS_FOLDER = "projects"

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
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(ROOT_URI_KEY, null)

    fun saveRootUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(ROOT_URI_KEY, uri.toString()).apply()
    }

    fun rootName(context: Context, uri: String): String {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file") return managedFile(context, parsed.path.orEmpty()).name
        val root = treeDocument(context, uri)
        if (!root.isDirectory) throw IOException("Le dossier choisi n'est plus accessible.")
        return root.name?.takeIf { it.isNotBlank() } ?: "Workspace"
    }

    fun list(context: Context, folderUri: String): List<WorkspaceItem> {
        val parsed = Uri.parse(folderUri)
        if (parsed.scheme == "file") {
            val folder = managedFile(context, parsed.path.orEmpty())
            if (!folder.isDirectory) throw IOException("Ce document n'est pas un dossier.")
            return folder.listFiles().orEmpty()
                .filterNot { it.name == ".git" }
                .map { file ->
                    WorkspaceItem(
                        uri = Uri.fromFile(file).toString(),
                        name = file.name,
                        isDirectory = file.isDirectory,
                        size = if (file.isFile) file.length() else 0L,
                        lastModified = file.lastModified(),
                        mimeType = mimeTypeFromName(file.name)
                    )
                }
                .sortedWith(compareBy<WorkspaceItem> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
        }

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
            .sortedWith(compareBy<WorkspaceItem> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
    }

    fun readText(context: Context, item: WorkspaceItem): String {
        if (item.isDirectory) throw IOException("Un dossier ne peut pas être ouvert dans l'éditeur.")
        if (!isTextFile(item.name, item.mimeType)) {
            throw IOException("Ce type de fichier n'est pas reconnu comme du texte éditable.")
        }
        if (item.size > MAX_EDITABLE_BYTES) throw IOException("Le fichier dépasse la limite d'édition de 1 Mio.")

        val uri = Uri.parse(item.uri)
        val input = if (uri.scheme == "file") {
            managedFile(context, uri.path.orEmpty()).inputStream()
        } else {
            context.contentResolver.openInputStream(uri)
                ?: throw IOException("Impossible d'ouvrir ce fichier.")
        }
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_EDITABLE_BYTES) throw IOException("Le fichier dépasse la limite d'édition de 1 Mio.")
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
        if (bytes.size > MAX_EDITABLE_BYTES) throw IOException("Le fichier dépasse la limite de sauvegarde de 1 Mio.")
        val uri = Uri.parse(item.uri)
        if (uri.scheme == "file") {
            managedFile(context, uri.path.orEmpty()).outputStream().use { it.write(bytes) }
            return
        }
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
        if (!isTextFile(name, null)) throw IOException("La création est limitée aux fichiers texte pris en charge.")

        val parsed = Uri.parse(parentUri)
        if (parsed.scheme == "file") {
            val parent = managedFile(context, parsed.path.orEmpty())
            if (!parent.isDirectory) throw IOException("Le dossier courant n'est plus accessible.")
            val child = File(parent, name)
            if (child.exists()) throw IOException("Un fichier ou dossier portant ce nom existe déjà.")
            if (!child.createNewFile()) throw IOException("Android n'a pas pu créer ce fichier.")
            return WorkspaceItem(
                uri = Uri.fromFile(child).toString(),
                name = child.name,
                isDirectory = false,
                size = child.length(),
                lastModified = child.lastModified(),
                mimeType = mimeTypeFromName(child.name)
            )
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
        if (normalized.substringAfterLast('.', missingDelimiterValue = "").let { it in textExtensions }) return true
        return mimeType?.lowercase(Locale.ROOT)?.let {
            it.startsWith("text/") || it == "application/json" || it == "application/xml" ||
                it == "application/yaml" || it == "application/x-yaml" || it == "application/javascript"
        } ?: false
    }

    /**
     * Imports a SAF-selected workspace into app-private storage so JGit can manage its files.
     * The original SAF folder is not modified; the caller should switch the active root to this copy.
     */
    fun importWorkspaceIntoManagedProject(context: Context, sourceUri: String): File {
        val uri = Uri.parse(sourceUri)
        if (uri.scheme == "file") {
            val current = managedFile(context, uri.path.orEmpty())
            if (!current.isDirectory) throw IOException("Le workspace actif n'est pas un dossier.")
            return current
        }
        val source = treeDocument(context, sourceUri)
        if (!source.isDirectory) throw IOException("Le workspace actif n'est pas un dossier.")
        val name = source.name?.takeIf { it.isNotBlank() } ?: "workspace"
        val safeName = name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_.-]+"), "-")
            .trim('-', '.', '_')
            .take(60)
            .ifBlank { "workspace" }
        val projectsRoot = File(context.filesDir, PROJECTS_FOLDER).canonicalFile
        val parent = File(projectsRoot, "workspaces")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Impossible de créer l'espace privé des workspaces.")
        }
        val target = File(parent, safeName + "-" + System.currentTimeMillis()).canonicalFile
        if (!target.path.startsWith(projectsRoot.path + File.separator)) {
            throw IOException("Chemin du workspace importé non autorisé.")
        }
        if (!target.mkdirs()) throw IOException("Impossible de préparer la copie gérée du workspace.")
        try {
            copyDocumentTree(context, source, target, CopyStats())
        } catch (error: Exception) {
            target.deleteRecursively()
            throw IOException("Import du workspace interrompu : " + (error.message ?: "erreur de copie"), error)
        }
        return target
    }

    /** Creates a ZIP of the active Workspace without Git metadata, common build caches, or local secret files. */
    fun createWorkspaceArchive(context: Context, outputFile: File): File {
        val savedRoot = loadRootUri(context) ?: throw IOException("Choisis un workspace avant de lancer le build.")
        val uri = Uri.parse(savedRoot)
        val parent = outputFile.parentFile ?: throw IOException("Chemin d'archive invalide.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Impossible de préparer l'archive temporaire.")
        outputFile.delete()
        try {
            ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
                val stats = CopyStats()
                when (uri.scheme) {
                    "file" -> {
                        val root = managedFile(context, uri.path.orEmpty())
                        if (!root.isDirectory) throw IOException("Le workspace actif n'est pas un dossier.")
                        zipFileTree(root, root, "", zip, stats)
                    }
                    "content" -> {
                        val root = treeDocument(context, savedRoot)
                        if (!root.isDirectory) throw IOException("Le workspace actif n'est pas un dossier.")
                        zipDocumentTree(context, root, "", zip, stats)
                    }
                    else -> throw IOException("Ce type de workspace ne peut pas être envoyé au Build Engine.")
                }
                if (stats.files == 0) throw IOException("Le workspace ne contient aucun fichier à compiler.")
            }
            if (!outputFile.isFile || outputFile.length() == 0L) throw IOException("L'archive du workspace est vide.")
            if (outputFile.length() > MAX_WORKSPACE_ARCHIVE_ZIP_BYTES) {
                throw IOException("L'archive compressée dépasse la limite de 200 Mio.")
            }
            return outputFile
        } catch (error: Exception) {
            outputFile.delete()
            if (error is IOException) throw error
            throw IOException("Création de l'archive impossible : " + (error.message ?: "erreur inconnue"), error)
        }
    }

    private data class CopyStats(var files: Int = 0, var bytes: Long = 0)

    private fun copyDocumentTree(context: Context, source: DocumentFile, destination: File, stats: CopyStats) {
        for (item in source.listFiles()) {
            val name = item.name ?: continue
            if (!isSafeWorkspaceName(name) || shouldSkipWorkspaceEntry(name, item.isDirectory)) continue
            val target = File(destination, name)
            if (item.isDirectory) {
                if (!target.exists() && !target.mkdirs()) throw IOException("Impossible de créer le dossier " + name + ".")
                copyDocumentTree(context, item, target, stats)
            } else {
                if (target.exists()) throw IOException("Fichier dupliqué pendant l'import : " + name)
                val input = context.contentResolver.openInputStream(item.uri)
                    ?: throw IOException("Impossible de lire " + name + " depuis le workspace source.")
                input.use { sourceStream ->
                    target.outputStream().buffered().use { destinationStream ->
                        copyBounded(sourceStream, destinationStream, stats)
                    }
                }
            }
        }
    }

    private fun zipDocumentTree(context: Context, source: DocumentFile, prefix: String, zip: ZipOutputStream, stats: CopyStats) {
        for (item in source.listFiles()) {
            val name = item.name ?: continue
            if (!isSafeWorkspaceName(name) || shouldSkipWorkspaceEntry(name, item.isDirectory)) continue
            val relative = if (prefix.isEmpty()) name else prefix + "/" + name
            if (item.isDirectory) {
                zip.putNextEntry(ZipEntry(relative + "/"))
                zip.closeEntry()
                zipDocumentTree(context, item, relative, zip, stats)
            } else {
                zip.putNextEntry(ZipEntry(relative))
                val input = context.contentResolver.openInputStream(item.uri)
                    ?: throw IOException("Impossible de lire " + relative + " depuis le workspace.")
                input.use { copyBounded(it, zip, stats) }
                zip.closeEntry()
            }
        }
    }

    private fun zipFileTree(root: File, directory: File, prefix: String, zip: ZipOutputStream, stats: CopyStats) {
        for (item in directory.listFiles().orEmpty()) {
            if (!isSafeWorkspaceName(item.name) || shouldSkipWorkspaceEntry(item.name, item.isDirectory)) continue
            val canonical = item.canonicalFile
            val normalizedAbsolute = item.absoluteFile.toPath().normalize().toFile()
            if (canonical.path != normalizedAbsolute.path) continue
            if (canonical.path != root.canonicalPath && !canonical.path.startsWith(root.canonicalPath + File.separator)) continue
            val relative = if (prefix.isEmpty()) item.name else prefix + "/" + item.name
            if (item.isDirectory) {
                zip.putNextEntry(ZipEntry(relative + "/"))
                zip.closeEntry()
                zipFileTree(root, item, relative, zip, stats)
            } else if (item.isFile) {
                zip.putNextEntry(ZipEntry(relative))
                item.inputStream().buffered().use { copyBounded(it, zip, stats) }
                zip.closeEntry()
            }
        }
    }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, stats: CopyStats) {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            stats.bytes += count
            if (stats.bytes > MAX_WORKSPACE_SOURCE_BYTES) {
                throw IOException("Le workspace dépasse la limite de 512 Mio de fichiers source.")
            }
            output.write(buffer, 0, count)
        }
        stats.files++
        if (stats.files > MAX_WORKSPACE_FILE_COUNT) {
            throw IOException("Le workspace contient plus de 100 000 fichiers.")
        }
    }

    private fun shouldSkipWorkspaceEntry(name: String, isDirectory: Boolean): Boolean {
        val normalized = name.lowercase(Locale.ROOT)
        if (normalized == ".git" || normalized == ".gradle" || normalized == ".idea" ||
            normalized == ".kotlin" || normalized == "node_modules" || normalized == ".ds_store" ||
            (isDirectory && normalized == "build")) return true
        if (normalized == "local.properties" || normalized == ".git-credentials") return true
        if (normalized == ".env" || (normalized.startsWith(".env.") && !normalized.endsWith(".example"))) return true
        return listOf(".jks", ".keystore", ".p12", ".pem", ".key").any { normalized.endsWith(it) }
    }

    private fun isSafeWorkspaceName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }

    private const val MAX_WORKSPACE_SOURCE_BYTES = 512L * 1024L * 1024L
    private const val MAX_WORKSPACE_ARCHIVE_ZIP_BYTES = 200L * 1024L * 1024L
    private const val MAX_WORKSPACE_FILE_COUNT = 100_000

    private fun managedFile(context: Context, rawPath: String): File {
        val requested = File(rawPath).canonicalFile
        val projectsRoot = File(context.filesDir, PROJECTS_FOLDER).canonicalFile
        if (requested.path != projectsRoot.path && !requested.path.startsWith(projectsRoot.path + File.separator)) {
            throw IOException("L'accès fichier est limité aux dépôts gérés dans l'espace privé de NyVibeDroid.")
        }
        if (!requested.exists()) throw IOException("Le fichier ou dossier n'existe plus.")
        return requested
    }

    private fun mimeTypeFromName(name: String): String? = when (
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    ) {
        "kt", "kts", "java", "js", "ts", "tsx", "jsx", "py", "rs", "go", "c", "h", "cpp", "hpp", "sh",
        "md", "txt", "properties", "gradle", "yml", "yaml", "toml", "css", "sql" -> "text/plain"
        "json" -> "application/json"
        "xml", "html", "svg" -> "application/xml"
        else -> null
    }

    private fun mimeTypeFor(name: String): String = mimeTypeFromName(name) ?: "text/plain"

    private fun treeDocument(context: Context, uri: String): DocumentFile {
        return DocumentFile.fromTreeUri(context, Uri.parse(uri))
            ?: throw IOException("Le dossier est inaccessible. Sélectionne-le à nouveau.")
    }
}
