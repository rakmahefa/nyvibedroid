package com.nyvibe.droid

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.filter.PathFilter

internal data class LocalGitRepository(
    val directory: String,
    val name: String,
    val remoteUrl: String,
    val currentBranch: String
)

internal data class GitFileChange(
    val path: String,
    val status: String,
    val staged: Boolean,
    val summary: String
)

internal data class GitRepositoryState(
    val repository: LocalGitRepository,
    val changes: List<GitFileChange>,
    val branches: List<String>,
    val ahead: Int,
    val behind: Int,
    val lastCommit: String?
)

internal data class WorkspaceBuildTarget(
    val repository: String,
    val ref: String,
    val directory: String,
    val source: String,
    val hasLocalChanges: Boolean,
    val commitsAhead: Int
)

internal object GitRepositoryManager {
    private const val PREFS = "git_repository_settings"
    private const val SELECTED_REPOSITORY = "selected_repository"
    private const val COMMIT_NAME = "commit_name"
    private const val COMMIT_EMAIL = "commit_email"
    private const val PROJECTS_FOLDER = "projects"
    private const val MAX_DIFF_CHARS = 32_000

    fun normalizeRemoteUrl(input: String): String {
        val value = input.trim()
        require(value.isNotBlank()) { "Saisis une URL GitHub ou owner/repository." }
        val candidate = if (value.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"))) {
            "https://github.com/$value"
        } else value
        val uri = runCatching { URI(candidate) }.getOrElse {
            throw IllegalArgumentException("URL invalide. Utilise https://github.com/owner/repository.")
        }
        require(uri.scheme.equals("https", true) && uri.host.equals("github.com", true)) {
            "Cette première version utilise les dépôts HTTPS hébergés sur github.com."
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "L'URL ne doit pas contenir d'identifiants, de paramètres ou de fragment."
        }
        val parts = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        require(parts.size == 2) { "L'URL doit désigner un dépôt au format owner/repository." }
        val owner = parts[0]
        val repository = parts[1].removeSuffix(".git")
        val validName = Regex("^[A-Za-z0-9_.-]+$")
        require(owner.matches(validName) && repository.matches(validName) &&
            owner != "." && owner != ".." && repository != "." && repository != "..") {
            "Le propriétaire ou le nom du dépôt n'est pas valide."
        }
        return "https://github.com/$owner/$repository.git"
    }

    fun saveCredential(context: Context, token: String) = GitCredentialStore(context).save(token)
    fun loadCredential(context: Context): String? = GitCredentialStore(context).load()
    fun clearCredential(context: Context) = GitCredentialStore(context).clear()

    fun loadIdentity(context: Context): Pair<String, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(COMMIT_NAME, "").orEmpty() to prefs.getString(COMMIT_EMAIL, "").orEmpty()
    }

    fun listRepositories(context: Context): List<LocalGitRepository> {
        val root = projectsDirectory(context)
        if (!root.exists()) return emptyList()
        return root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .flatMap { ownerDir ->
                ownerDir.listFiles().orEmpty().filter {
                    it.isDirectory && File(it, ".git").isDirectory
                }
            }
            .mapNotNull { runCatching { describe(it) }.getOrNull() }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun selectedRepository(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SELECTED_REPOSITORY, null)

    /** Resolves the current Workspace first, then the explicit active repository selected in the Git tab. */
    fun currentWorkspaceBuildTarget(context: Context): WorkspaceBuildTarget? {
        val savedRoot = WorkspaceRepository.loadRootUri(context)
        val workspaceRoot = savedRoot?.let { saved ->
            val uri = Uri.parse(saved)
            if (uri.scheme != "file") null else runCatching {
                val candidate = File(uri.path.orEmpty()).canonicalFile
                val projectsRoot = projectsDirectory(context)
                candidate.takeIf {
                    it.path.startsWith(projectsRoot.path + File.separator) && File(it, ".git").isDirectory
                }
            }.getOrNull()
        }
        val selectedGitRoot = selectedRepository(context)?.let { selected ->
            runCatching { managedRepository(context, selected) }.getOrNull()
        }
        val root = workspaceRoot ?: selectedGitRoot ?: return null
        val source = if (workspaceRoot != null) "Workspace" else "dépôt Git actif"

        return try {
            openRepository(root).use { git ->
                val gitRepository = git.repository
                val branch = gitRepository.branch ?: throw IOException("Le workspace n'a pas de branche Git active.")
                val rawRemote = gitRepository.config.getString("remote", "origin", "url")
                    ?: throw IOException("Le workspace n'a pas de dépôt distant origin.")
                val normalizedRemote = normalizeRemoteUrl(rawRemote)
                val parts = URI(normalizedRemote).path.trim('/').split('/')
                if (parts.size != 2) throw IOException("Le dépôt distant du workspace n'est pas reconnu.")

                val remoteName = gitRepository.config.getString("branch", branch, "remote")
                val mergeRef = gitRepository.config.getString("branch", branch, "merge")
                val tracking = if (!remoteName.isNullOrBlank() && !mergeRef.isNullOrBlank()) {
                    "refs/remotes/" + remoteName + "/" + Repository.shortenRefName(mergeRef)
                } else null
                val ahead = if (!tracking.isNullOrBlank() && gitRepository.resolve(tracking) != null) {
                    runCatching { countAheadBehind(gitRepository, tracking).first }.getOrDefault(0)
                } else 0

                WorkspaceBuildTarget(
                    repository = parts[0] + "/" + parts[1].removeSuffix(".git"),
                    ref = branch,
                    directory = root.canonicalPath,
                    source = source,
                    hasLocalChanges = !git.status().call().isClean,
                    commitsAhead = ahead
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Converts the active Workspace into an app-managed local Git repository.
     * SAF workspaces are copied to private storage because JGit needs filesystem access.
     */
    fun makeWorkspaceManaged(context: Context): LocalGitRepository {
        val savedRoot = WorkspaceRepository.loadRootUri(context)
            ?: throw IOException("Choisis un dossier dans Workspace avant de le transformer.")
        val uri = Uri.parse(savedRoot)
        val root = when (uri.scheme) {
            "file" -> {
                val candidate = File(uri.path.orEmpty()).canonicalFile
                val projectsRoot = projectsDirectory(context)
                if (!candidate.path.startsWith(projectsRoot.path + File.separator) || !candidate.isDirectory) {
                    throw IOException("Ce Workspace n'est pas dans l'espace privé géré. Sélectionne un dossier puis importe-le comme projet géré.")
                }
                candidate
            }
            "content" -> WorkspaceRepository.importWorkspaceIntoManagedProject(context, savedRoot).canonicalFile
            else -> throw IOException("Ce type de Workspace ne peut pas être converti en dépôt géré.")
        }
        val projectsRoot = projectsDirectory(context)
        if (!root.path.startsWith(projectsRoot.path + File.separator)) {
            throw IOException("Le dossier cible n'est pas autorisé comme dépôt géré.")
        }

        if (File(root, ".git").isDirectory) {
            openRepository(root).use { }
        } else {
            try {
                Git.init().setDirectory(root).call().use { }
            } catch (error: Exception) {
                throw IOException("Impossible d'initialiser le dépôt Git local : " + gitError(error), error)
            }
        }
        selectRepository(context, root.path)
        WorkspaceRepository.saveRootUri(context, Uri.fromFile(root))
        return describe(root)
    }

    fun selectRepository(context: Context, directory: String) {
        val repo = managedRepository(context, directory)
        openRepository(repo).use { }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(SELECTED_REPOSITORY, repo.canonicalPath).apply()
    }

    fun cloneRepository(context: Context, remoteInput: String, token: String?): LocalGitRepository {
        val remoteUrl = normalizeRemoteUrl(remoteInput)
        val path = URI(remoteUrl).path.trim('/').split('/')
        val owner = path[0]
        val repositoryName = path[1].removeSuffix(".git")
        val parent = File(projectsDirectory(context), owner)
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Impossible de créer l'espace privé des dépôts.")
        val target = File(parent, repositoryName).canonicalFile
        val allowed = projectsDirectory(context).canonicalFile
        require(target.path.startsWith(allowed.path + File.separator)) { "Chemin de dépôt non autorisé." }

        if (target.exists()) {
            if (File(target, ".git").isDirectory) {
                selectRepository(context, target.path)
                return describe(target)
            }
            if (target.listFiles().orEmpty().isNotEmpty()) {
                throw IOException("Le dossier de destination existe déjà et n'est pas un dépôt Git.")
            }
        } else if (!target.mkdirs()) {
            throw IOException("Impossible de créer le dossier du dépôt.")
        }

        try {
            val command = Git.cloneRepository().setURI(remoteUrl).setDirectory(target).setCloneAllBranches(true)
            credentialsProvider(token)?.let { command.setCredentialsProvider(it) }
            command.call().use { }
        } catch (error: Exception) {
            target.deleteRecursively()
            throw IOException(gitError(error), error)
        }
        selectRepository(context, target.path)
        return describe(target)
    }

    fun refresh(context: Context, directory: String): GitRepositoryState {
        val root = managedRepository(context, directory)
        openRepository(root).use { git ->
            val status = git.status().call()
            val changes = buildList {
                status.added.forEach { add(GitFileChange(it, "A", true, "Ajout indexé")) }
                status.changed.forEach { add(GitFileChange(it, "M", true, "Modification indexée")) }
                status.removed.forEach { add(GitFileChange(it, "D", true, "Suppression indexée")) }
                status.modified.forEach { add(GitFileChange(it, "M", false, "Fichier modifié")) }
                status.missing.forEach { add(GitFileChange(it, "D", false, "Fichier supprimé")) }
                status.untracked.forEach { add(GitFileChange(it, "?", false, "Nouveau fichier non suivi")) }
                status.conflicting.forEach { add(GitFileChange(it, "!", false, "Conflit à résoudre")) }
            }.distinctBy { it.path to it.staged }
                .sortedWith(compareBy<GitFileChange> { it.path.lowercase(Locale.ROOT) }.thenBy { !it.staged })

            val branches = git.branchList().call().map { Repository.shortenRefName(it.name) }.distinct().sorted()
            val repository = git.repository
            val branch = repository.branch.orEmpty()
            val remoteName = repository.config.getString("branch", branch, "remote")
            val mergeRef = repository.config.getString("branch", branch, "merge")
            val tracking = if (!remoteName.isNullOrBlank() && !mergeRef.isNullOrBlank()) {
                "refs/remotes/" + remoteName + "/" + Repository.shortenRefName(mergeRef)
            } else null
            val aheadBehind = if (!tracking.isNullOrBlank() && repository.resolve(tracking) != null) {
                runCatching { countAheadBehind(repository, tracking) }.getOrDefault(0 to 0)
            } else 0 to 0

            return GitRepositoryState(
                repository = describe(root),
                changes = changes,
                branches = branches,
                ahead = aheadBehind.first,
                behind = aheadBehind.second,
                lastCommit = runCatching { repository.resolve(Constants.HEAD)?.name?.take(8) }.getOrNull()
            )
        }
    }

    fun diff(context: Context, directory: String, path: String? = null): String {
        val root = managedRepository(context, directory)
        openRepository(root).use { git ->
            val output = ByteArrayOutputStream()
            DiffFormatter(output).use { formatter ->
                formatter.setRepository(git.repository)
                formatter.setDiffComparator(RawTextComparator.DEFAULT)
                formatter.setDetectRenames(true)
                val staged = git.diff().setCached(true)
                val unstaged = git.diff().setCached(false)
                if (!path.isNullOrBlank()) {
                    staged.setPathFilter(PathFilter.create(path))
                    unstaged.setPathFilter(PathFilter.create(path))
                }
                staged.call().forEach { entry ->
                    output.write("[INDEX]\n".toByteArray(Charsets.UTF_8))
                    formatter.format(entry)
                }
                unstaged.call().forEach { entry ->
                    output.write("[WORKTREE]\n".toByteArray(Charsets.UTF_8))
                    formatter.format(entry)
                }
            }
            val text = output.toString(Charsets.UTF_8.name())
            if (text.isBlank()) return "Aucun diff textuel disponible. Ouvre les fichiers non suivis dans Workspace pour les consulter."
            return if (text.length > MAX_DIFF_CHARS) {
                text.take(MAX_DIFF_CHARS) + "\n\n[Diff tronqué après $MAX_DIFF_CHARS caractères.]"
            } else text
        }
    }

    fun createOrCheckoutBranch(context: Context, directory: String, requestedName: String) {
        val name = requestedName.trim()
        require(name.isNotBlank() && Repository.isValidRefName("refs/heads/$name")) { "Nom de branche Git invalide." }
        val root = managedRepository(context, directory)
        openRepository(root).use { git ->
            val exists = git.branchList().call().any { Repository.shortenRefName(it.name) == name }
            if (exists) git.checkout().setName(name).call()
            else git.checkout().setCreateBranch(true).setName(name).call()
        }
    }

    fun commitAll(context: Context, directory: String, message: String, authorName: String, authorEmail: String) {
        require(message.isNotBlank()) { "Saisis un message de commit." }
        require(authorName.isNotBlank()) { "Saisis le nom de l'auteur du commit." }
        require(authorEmail.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) {
            "Saisis une adresse e-mail valide pour les commits."
        }
        val root = managedRepository(context, directory)
        openRepository(root).use { git ->
            git.add().addFilepattern(".").call()
            git.add().addFilepattern(".").setUpdate(true).call()
            if (git.status().call().isClean) throw IOException("Aucune modification à committer.")
            git.commit()
                .setMessage(message.trim())
                .setAuthor(authorName.trim(), authorEmail.trim())
                .setCommitter(authorName.trim(), authorEmail.trim())
                .call()
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(COMMIT_NAME, authorName.trim())
            .putString(COMMIT_EMAIL, authorEmail.trim())
            .apply()
    }

    fun fetch(context: Context, directory: String, token: String?) {
        val root = managedRepository(context, directory)
        openRepository(root).use { git ->
            val command = git.fetch().setRemoveDeletedRefs(true)
            credentialsProvider(token)?.let { command.setCredentialsProvider(it) }
            try {
                command.call()
            } catch (error: Exception) {
                throw IOException(gitError(error), error)
            }
        }
    }

    fun pull(context: Context, directory: String, token: String?) {
        val root = managedRepository(context, directory)
        openRepository(root).use { git ->
            val command = git.pull()
            credentialsProvider(token)?.let { command.setCredentialsProvider(it) }
            val result = try {
                command.call()
            } catch (error: Exception) {
                throw IOException(gitError(error), error)
            }
            if (!result.isSuccessful) {
                val status = result.mergeResult?.mergeStatus?.name ?: result.rebaseResult?.status?.name ?: "conflit ou branche sans suivi distant"
                throw IOException("Pull non terminé : $status.")
            }
        }
    }

    fun push(context: Context, directory: String, token: String?) {
        if (token.isNullOrBlank()) {
            throw IOException("Authentification GitHub manquante. Saisis puis enregistre un jeton avec Contents: Read and write pour pousser.")
        }
        val root = managedRepository(context, directory)
        openRepository(root).use { git ->
            val branch = git.repository.branch ?: throw IOException("Impossible de pousser depuis un HEAD détaché.")
            val command = git.push().setRefSpecs(RefSpec("HEAD:refs/heads/$branch"))
            credentialsProvider(token)?.let { command.setCredentialsProvider(it) }
            val updates = try {
                command.call().flatMap { it.remoteUpdates }
            } catch (error: Exception) {
                throw IOException(gitError(error), error)
            }
            val failures = updates.filter {
                it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE
            }
            if (failures.isNotEmpty()) {
                if (failures.any { it.status.name.contains("NONFASTFORWARD") }) {
                    throw IOException("Push refusé : la branche distante contient des commits absents localement. Fais Fetch/Pull, résous les conflits éventuels, puis réessaie.")
                }
                val details = failures.joinToString { it.remoteName + ": " + it.status }
                throw IOException("Push refusé : " + details + ". Vérifie que le jeton a Contents: Read and write et accès à ce dépôt.")
            }
        }
    }

    fun managedDirectoryUri(context: Context, directory: String): String =
        Uri.fromFile(managedRepository(context, directory)).toString()

    private fun countAheadBehind(repository: Repository, tracking: String): Pair<Int, Int> {
        val local = repository.resolve(Constants.HEAD) ?: return 0 to 0
        val remote = repository.resolve(tracking) ?: return 0 to 0
        val localIds = ancestorIds(repository, local)
        val remoteIds = ancestorIds(repository, remote)
        return (localIds - remoteIds).size to (remoteIds - localIds).size
    }

    private fun ancestorIds(repository: Repository, start: org.eclipse.jgit.lib.ObjectId): Set<String> {
        val ids = mutableSetOf<String>()
        RevWalk(repository).use { walk ->
            walk.markStart(walk.parseCommit(start))
            for (commit in walk) ids.add(commit.id.name)
        }
        return ids
    }

    private fun projectsDirectory(context: Context): File = File(context.filesDir, PROJECTS_FOLDER).canonicalFile

    private fun managedRepository(context: Context, directory: String): File {
        val requested = File(directory).canonicalFile
        val root = projectsDirectory(context)
        require(requested.path.startsWith(root.path + File.separator)) {
            "Git n'autorise que les dépôts gérés dans l'espace privé de NyVibeDroid."
        }
        if (!File(requested, ".git").isDirectory) throw IOException("Ce dossier n'est pas un dépôt Git géré par NyVibeDroid.")
        return requested
    }

    private fun openRepository(directory: File): Git =
        try { Git.open(directory) } catch (error: Exception) { throw IOException(gitError(error), error) }

    private fun describe(directory: File): LocalGitRepository {
        openRepository(directory).use { git ->
            return LocalGitRepository(
                directory = directory.canonicalPath,
                name = directory.name,
                remoteUrl = git.repository.config.getString("remote", "origin", "url").orEmpty(),
                currentBranch = git.repository.branch ?: "HEAD détachée"
            )
        }
    }

    private fun credentialsProvider(token: String?): CredentialsProvider? =
        token?.trim()?.takeIf { it.isNotEmpty() }?.let { UsernamePasswordCredentialsProvider("x-access-token", it) }

    private fun gitError(error: Throwable): String {
        val detail = error.message.orEmpty()
        return when {
            detail.contains("Authentication", true) || detail.contains("not authorized", true) ||
                detail.contains("401") || detail.contains("403") ->
                "GitHub a refusé l'accès. Vérifie le jeton et les permissions Contents du dépôt."
            detail.contains("not found", true) || detail.contains("Repository not found", true) ->
                "Dépôt introuvable ou privé sans permission de lecture."
            detail.isBlank() -> "Opération Git échouée (${error.javaClass.simpleName})."
            else -> detail.take(400)
        }
    }
}
