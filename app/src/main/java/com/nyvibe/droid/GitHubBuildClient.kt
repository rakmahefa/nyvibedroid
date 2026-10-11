package com.nyvibe.droid

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.util.UUID
import java.util.zip.ZipFile

internal data class BuildRun(
    val id: Long,
    val number: Int,
    val name: String,
    val htmlUrl: String,
    val status: String,
    val conclusion: String?,
    val createdAt: String
)

internal data class WorkspaceUploadRelease(
    val id: Long,
    val tagName: String
)

internal object GitHubBuildClient {
    private const val API = "https://api.github.com"
    private const val ENGINE_REPOSITORY = "rakmahefa/build"
    private const val WORKFLOW = "remote-build.yml"

    fun validateToken(token: String) {
        require(token.isNotBlank()) { "Saisis un jeton GitHub." }
        val repository = requestJson(token, "/repos/$ENGINE_REPOSITORY", "GET").optString("full_name")
        if (!repository.equals(ENGINE_REPOSITORY, ignoreCase = true)) {
            throw IOException("Le dépôt Build Engine est inaccessible avec ce jeton.")
        }
    }

    /**
     * Uploads the active Workspace ZIP as a temporary draft release asset in the Build Engine repo.
     * The token needs Contents: write and Actions: write on rakmahefa/build.
     */
    fun uploadWorkspaceArchive(token: String, archive: File): WorkspaceUploadRelease {
        if (!archive.isFile || archive.length() <= 0L) throw IOException("L'archive du workspace est vide.")
        if (archive.length() > 200L * 1024L * 1024L) {
            throw IOException("L'archive dépasse la limite de 200 Mio pour l'envoi.")
        }
        val tag = "nyvibe-workspace-" + UUID.randomUUID().toString().replace("-", "")
        val payload = JSONObject()
            .put("tag_name", tag)
            .put("name", "NyVibeDroid temporary workspace upload")
            .put("body", "Temporary private workspace archive. Managed by NyVibeDroid; safe to delete after the remote build.")
            .put("draft", true)
            .put("prerelease", true)
        val response = requestJson(token, "/repos/" + ENGINE_REPOSITORY + "/releases", "POST", payload.toString())
        val releaseId = response.optLong("id")
        val uploadUrl = response.optString("upload_url").substringBefore("{")
        if (releaseId <= 0L || !uploadUrl.startsWith("https://uploads.github.com/")) {
            throw IOException("GitHub a créé une version temporaire, mais n'a pas retourné une URL d'envoi valide.")
        }
        val release = WorkspaceUploadRelease(releaseId, tag)
        try {
            uploadReleaseAsset(token, uploadUrl, archive)
            return release
        } catch (error: Exception) {
            runCatching { deleteTemporaryWorkspaceUpload(token, release) }
            throw error
        }
    }

    fun dispatchWorkspaceArchiveBuild(token: String, releaseId: Long): BuildRun {
        require(releaseId > 0L) { "Identifiant d'archive temporaire invalide." }
        val existingRunIds = listRuns(token).mapTo(mutableSetOf()) { it.id }
        val dispatchedAt = System.currentTimeMillis()
        val body = JSONObject()
            .put("ref", "main")
            .put(
                "inputs",
                JSONObject()
                    .put("source_mode", "workspace_archive")
                    .put("archive_release_id", releaseId.toString())
                    .put("platform", "android")
            )
        request(token, "/repos/" + ENGINE_REPOSITORY + "/actions/workflows/" + WORKFLOW + "/dispatches", "POST", body.toString())

        repeat(18) {
            Thread.sleep(1_500)
            val run = listRuns(token).firstOrNull {
                it.id !in existingRunIds && parseTimestamp(it.createdAt) >= dispatchedAt - 5_000L
            }
            if (run != null) return run
        }
        throw IOException(
            "GitHub a accepté la compilation du workspace, mais l'exécution n'est pas encore visible. " +
                "Actualise dans quelques secondes ; ne renvoie pas immédiatement l'archive."
        )
    }

    fun deleteTemporaryWorkspaceUpload(token: String, upload: WorkspaceUploadRelease) {
        runCatching { request(token, "/repos/" + ENGINE_REPOSITORY + "/releases/" + upload.id, "DELETE") }
        runCatching { request(token, "/repos/" + ENGINE_REPOSITORY + "/git/refs/tags/" + upload.tagName, "DELETE") }
    }

    private fun uploadReleaseAsset(token: String, baseUploadUrl: String, archive: File) {
        val encodedName = URLEncoder.encode("workspace.zip", "UTF-8")
        val endpoint = baseUploadUrl + "?name=" + encodedName
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = 30_000
            readTimeout = 300_000
            setFixedLengthStreamingMode(archive.length())
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "NyVibeDroid")
            setRequestProperty("Content-Type", "application/zip")
        }
        try {
            archive.inputStream().buffered().use { input ->
                connection.outputStream.buffered().use { output -> input.copyTo(output) }
            }
            val status = connection.responseCode
            val detail = runCatching {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            if (status !in 200..299) {
                val explanation = apiMessage(detail).ifBlank {
                    if (status == 403 || status == 404) {
                        "Vérifie les droits Contents: Read and write sur rakmahefa/build."
                    } else "GitHub a refusé l'envoi de l'archive."
                }
                throw IOException("Envoi de l'archive refusé (HTTP " + status + ") : " + explanation)
            }
        } finally {
            connection.disconnect()
        }
    }

    fun dispatchBuild(token: String, targetRepository: String, targetRef: String): BuildRun {
        require(targetRepository.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"))) {
            "Le dépôt doit être au format propriétaire/dépôt, par exemple rakmahefa/nyvibedroid."
        }
        require(targetRef.isNotBlank()) { "La branche, le tag ou le commit est obligatoire." }

        // Snapshot existing runs first because workflow_dispatch returns HTTP 204, not a run ID.
        val existingRunIds = listRuns(token).mapTo(mutableSetOf()) { it.id }
        val dispatchedAt = System.currentTimeMillis()
        val body = JSONObject()
            .put("ref", "main")
            .put(
                "inputs",
                JSONObject()
                    .put("source_mode", "repository")
                    .put("repository", targetRepository)
                    .put("branch", targetRef.trim())
                    .put("platform", "android")
            )
        request(token, "/repos/$ENGINE_REPOSITORY/actions/workflows/$WORKFLOW/dispatches", "POST", body.toString())

        repeat(18) {
            Thread.sleep(1_500)
            val run = listRuns(token).firstOrNull {
                it.id !in existingRunIds && parseTimestamp(it.createdAt) >= dispatchedAt - 5_000L
            }
            if (run != null) return run
        }

        throw IOException(
            "GitHub a accepté la demande, mais l'exécution n'est pas encore visible. " +
                "Actualise dans quelques secondes ; ne relance pas immédiatement la compilation."
        )
    }

    fun getRun(token: String, runId: Long): BuildRun =
        parseRun(requestJson(token, "/repos/$ENGINE_REPOSITORY/actions/runs/$runId", "GET"))

    fun getArtifactId(token: String, runId: Long): Long? {
        val json = requestJson(
            token,
            "/repos/$ENGINE_REPOSITORY/actions/runs/$runId/artifacts?per_page=100",
            "GET"
        )
        val artifacts = json.optJSONArray("artifacts") ?: return null
        for (index in 0 until artifacts.length()) {
            val artifact = artifacts.optJSONObject(index) ?: continue
            if (artifact.optString("name") == "build-android" && !artifact.optBoolean("expired", false)) {
                return artifact.optLong("id").takeIf { it > 0L }
            }
        }
        return null
    }

    fun getCachedArtifactZip(context: Context, token: String, artifactId: Long): File {
        val target = File(context.cacheDir, "build-android-$artifactId.zip")
        if (target.isFile && target.length() > 0L) return target

        val partial = File(context.cacheDir, "build-android-$artifactId.zip.part")
        partial.delete()
        val endpoint = "$API/repos/$ENGINE_REPOSITORY/actions/artifacts/$artifactId/zip"
        val apiConnection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "NyVibeDroid")
        }

        val response = apiConnection.responseCode
        val downloadConnection: HttpURLConnection
        if (response in 300..399) {
            val location = apiConnection.getHeaderField("Location")
                ?: run {
                    apiConnection.disconnect()
                    throw IOException("GitHub n'a pas fourni le lien de téléchargement de l'artefact.")
                }
            apiConnection.disconnect()
            // The redirect URL is signed; never forward the token to the storage host.
            downloadConnection = (URL(location).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 60_000
            }
        } else {
            downloadConnection = apiConnection
        }

        try {
            val finalStatus = downloadConnection.responseCode
            if (finalStatus !in 200..299) {
                val detail = runCatching {
                    (downloadConnection.errorStream ?: downloadConnection.inputStream)
                        .bufferedReader().use { it.readText() }
                }.getOrDefault("")
                throw IOException(
                    "Impossible de télécharger l'artefact (HTTP $finalStatus): " + apiMessage(detail)
                )
            }
            downloadConnection.inputStream.use { input ->
                partial.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        } catch (error: Exception) {
            partial.delete()
            throw error
        } finally {
            downloadConnection.disconnect()
        }

        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        if (!target.isFile || target.length() == 0L) {
            target.delete()
            throw IOException("L'archive téléchargée est vide.")
        }
        return target
    }

    fun readArtifactLogs(archive: File): String {
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            val selectedNames = listOf("build-metadata.txt", "engine-run.log", "build.log")
            val blocks = selectedNames.mapNotNull { desired ->
                val entry = entries.firstOrNull { it.name == desired || it.name.endsWith("/$desired") }
                    ?: return@mapNotNull null
                val content = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                val shortened = content.takeLast(18_000)
                val prefix = if (content.length > shortened.length) "[Fin du fichier affichée]\n" else ""
                "===== $desired =====\n$prefix$shortened"
            }
            return blocks.joinToString("\n\n").ifBlank {
                "Aucun fichier de log connu n'a été trouvé dans l'artefact."
            }
        }
    }

    fun extractDebugApk(archive: File, output: File): File {
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().filter {
                !it.isDirectory && it.name.endsWith(".apk", true)
            }.toList()
            val selected = entries.firstOrNull {
                it.name.endsWith("/app-debug.apk", ignoreCase = true) ||
                    it.name.equals("app-debug.apk", ignoreCase = true)
            } ?: entries.firstOrNull {
                it.name.contains("/debug/", ignoreCase = true) && !it.name.contains("androidTest")
            } ?: entries.firstOrNull()
                ?: throw IOException("Aucun APK n'a été trouvé dans l'artefact build-android.")
            zip.getInputStream(selected).use { input ->
                output.outputStream().buffered().use { destination -> input.copyTo(destination) }
            }
        }
        if (!output.isFile || output.length() == 0L) {
            output.delete()
            throw IOException("L'APK extrait est vide.")
        }
        return output
    }

    private fun listRuns(token: String): List<BuildRun> {
        val json = requestJson(
            token,
            "/repos/$ENGINE_REPOSITORY/actions/workflows/$WORKFLOW/runs?per_page=20&event=workflow_dispatch",
            "GET"
        )
        val runs = json.optJSONArray("workflow_runs") ?: return emptyList()
        return (0 until runs.length()).mapNotNull { index ->
            runs.optJSONObject(index)?.let(::parseRun)
        }
    }

    private fun requestJson(token: String, path: String, method: String): JSONObject {
        val response = request(token, path, method)
        return if (response.isBlank()) JSONObject() else JSONObject(response)
    }

    private fun request(
        token: String,
        path: String,
        method: String,
        body: String? = null
    ): String {
        val connection = (URL("$API$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "NyVibeDroid")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val responseBody = runCatching {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            if (status !in 200..299) {
                val explanation = apiMessage(responseBody).ifBlank {
                    when (status) {
                        401 -> "Jeton GitHub invalide ou expiré."
                        403 -> "Accès refusé. Vérifie les permissions Actions: Read and write et, pour le build ZIP, Contents: Read and write sur rakmahefa/build."
                        404 -> "Workflow ou dépôt introuvable, ou jeton sans accès."
                        else -> "La requête GitHub a échoué."
                    }
                }
                throw IOException("GitHub API (HTTP $status) : $explanation")
            }
            return responseBody
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRun(json: JSONObject): BuildRun = BuildRun(
        id = json.optLong("id"),
        number = json.optInt("run_number"),
        name = json.optString("name", "Remote Build Engine"),
        htmlUrl = json.optString("html_url", "https://github.com/$ENGINE_REPOSITORY/actions"),
        status = json.optString("status", "unknown"),
        conclusion = json.optString("conclusion").takeUnless { it.isBlank() || it == "null" },
        createdAt = json.optString("created_at", "")
    )

    private fun parseTimestamp(value: String): Long = runCatching {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    private fun apiMessage(body: String): String = runCatching {
        JSONObject(body).optString("message").takeIf { it.isNotBlank() } ?: body.take(240)
    }.getOrDefault(body.take(240))
}
