package com.nyvibe.droid

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private val Canvas = Color(0xFF0B1018)
private val Panel = Color(0xFF151D2A)
private val PanelRaised = Color(0xFF1B2636)
private val Accent = Color(0xFFB7F56A)
private val Foreground = Color(0xFFF2F5F9)
private val Muted = Color(0xFF9CAABD)
private val Border = Color(0xFF2B384B)
private val Danger = Color(0xFFFF8989)

@Composable
internal fun RemoteBuildScreen() {
    val context = LocalContext.current
    val tokenStore = remember { GitHubTokenStore(context) }
    val scope = rememberCoroutineScope()

    var token by remember { mutableStateOf(tokenStore.loadToken()) }
    var tokenDraft by rememberSaveable { mutableStateOf("") }
    var connected by remember { mutableStateOf(token != null) }
    var repository by rememberSaveable { mutableStateOf("rakmahefa/nyvibedroid") }
    var targetRef by rememberSaveable { mutableStateOf("main") }
    var buildSourceMode by rememberSaveable { mutableStateOf("workspace_zip") }
    var workspaceUpload by remember { mutableStateOf(tokenStore.loadWorkspaceUpload()) }
    var workspaceTarget by remember { mutableStateOf<WorkspaceBuildTarget?>(null) }
    var currentRunId by remember { mutableStateOf(tokenStore.loadRunId()) }
    var currentRun by remember { mutableStateOf<BuildRun?>(null) }
    var artifactId by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf<String?>(null) }
    var showLogs by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        workspaceTarget = withContext(Dispatchers.IO) {
            GitRepositoryManager.currentWorkspaceBuildTarget(context)
        }
    }

    LaunchedEffect(token, currentRunId) {
        val savedToken = token
        val savedRunId = currentRunId
        if (savedToken != null && savedRunId != null) {
            runCatching {
                withContext(Dispatchers.IO) { GitHubBuildClient.getRun(savedToken, savedRunId) }
            }.onSuccess { restored ->
                currentRun = restored
                if (restored.status == "completed") {
                    artifactId = runCatching {
                        withContext(Dispatchers.IO) {
                            GitHubBuildClient.getArtifactId(savedToken, restored.id)
                        }
                    }.getOrNull()
                    cleanupWorkspaceUpload(savedToken, restored.id)
                }
            }.onFailure {
                message = "Impossible de restaurer le dernier build : " + readableError(it)
                messageIsError = true
            }
        }
    }

    // Poll repeatedly while the screen remains open, without restarting the loop on every status refresh.
    LaunchedEffect(currentRun?.id, token) {
        val activeRunId = currentRun?.id
        val activeToken = token
        if (activeRunId != null && activeToken != null) {
            while (currentRun?.id == activeRunId && currentRun?.status != "completed") {
                delay(8_000)
                try {
                    val updated = withContext(Dispatchers.IO) {
                        GitHubBuildClient.getRun(activeToken, activeRunId)
                    }
                    currentRun = updated
                    if (updated.status == "completed") {
                        artifactId = runCatching {
                            withContext(Dispatchers.IO) {
                                GitHubBuildClient.getArtifactId(activeToken, updated.id)
                            }
                        }.getOrNull()
                        message = if (updated.conclusion == "success") {
                            "Compilation terminée. L'APK et les logs sont prêts s'ils sont présents dans l'artefact."
                        } else {
                            "La compilation s'est terminée avec le statut « " +
                                (updated.conclusion ?: "inconnu") + " ». Consulte les logs."
                        }
                        messageIsError = updated.conclusion != "success"
                        cleanupWorkspaceUpload(activeToken, updated.id)
                    }
                } catch (_: Exception) {
                    // Retain the last known state and try again on the next interval.
                }
            }
        }
    }

    fun showError(error: Throwable) {
        message = readableError(error)
        messageIsError = true
    }

    suspend fun cleanupWorkspaceUpload(activeToken: String, completedRunId: Long) {
        val upload = workspaceUpload?.takeIf { it.runId == completedRunId } ?: return
        withContext(Dispatchers.IO) {
            GitHubBuildClient.deleteTemporaryWorkspaceUpload(activeToken, upload)
        }
        tokenStore.clearWorkspaceUpload()
        workspaceUpload = null
    }

    fun refreshRun() {
        val activeToken = token
        val activeRun = currentRun
        if (activeToken == null || activeRun == null) {
            message = "Lance une compilation pour suivre une exécution ici."
            messageIsError = true
            return
        }
        scope.launch {
            busy = true
            busyLabel = "Actualisation…"
            try {
                val updated = withContext(Dispatchers.IO) {
                    GitHubBuildClient.getRun(activeToken, activeRun.id)
                }
                currentRun = updated
                if (updated.status == "completed") {
                    artifactId = withContext(Dispatchers.IO) {
                        GitHubBuildClient.getArtifactId(activeToken, updated.id)
                    }
                }
                message = when {
                    updated.status != "completed" -> "Statut synchronisé depuis GitHub."
                    updated.conclusion == "success" -> "Compilation terminée avec succès."
                    else -> "La compilation est terminée ; consulte les logs pour connaître la cause."
                }
                messageIsError = updated.status == "completed" && updated.conclusion != "success"
                if (updated.status == "completed") cleanupWorkspaceUpload(activeToken, updated.id)
            } catch (error: Exception) {
                showError(error)
            } finally {
                busy = false
                busyLabel = ""
            }
        }
    }

    fun connectGitHub() {
        val candidate = tokenDraft.trim().ifBlank { token.orEmpty() }
        if (candidate.isBlank()) {
            message = "Saisis un jeton d'accès personnel GitHub."
            messageIsError = true
            return
        }
        scope.launch {
            busy = true
            busyLabel = "Vérification du jeton…"
            try {
                withContext(Dispatchers.IO) { GitHubBuildClient.validateToken(candidate) }
                tokenStore.saveToken(candidate)
                token = candidate
                tokenDraft = ""
                connected = true
                message = "GitHub est connecté. Le jeton est chiffré avec Android Keystore sur cet appareil."
                messageIsError = false
            } catch (error: Exception) {
                connected = false
                showError(error)
            } finally {
                busy = false
                busyLabel = ""
            }
        }
    }

    fun startBuild() {
        val candidate = tokenDraft.trim().ifBlank { token.orEmpty() }
        if (candidate.isBlank()) {
            message = "Connecte GitHub avant de lancer une compilation."
            messageIsError = true
            return
        }
        if (buildSourceMode == "github_ref") {
            if (!repository.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"))) {
                message = "Le dépôt doit être au format propriétaire/dépôt."
                messageIsError = true
                return
            }
            if (targetRef.isBlank()) {
                message = "La branche, le tag ou le commit est obligatoire."
                messageIsError = true
                return
            }
        } else if (WorkspaceRepository.loadRootUri(context).isNullOrBlank()) {
            message = "Choisis d'abord un dossier dans l'onglet Workspace."
            messageIsError = true
            return
        }

        scope.launch {
            busy = true
            busyLabel = "Vérification du jeton…"
            message = null
            try {
                withContext(Dispatchers.IO) { GitHubBuildClient.validateToken(candidate) }
                tokenStore.saveToken(candidate)
                token = candidate
                connected = true
                tokenDraft = ""

                val created = if (buildSourceMode == "workspace_zip") {
                    busyLabel = "Compression du workspace…"
                    val archive = withContext(Dispatchers.IO) {
                        WorkspaceRepository.createWorkspaceArchive(
                            context,
                            File(context.cacheDir, "nyvibedroid-workspace-" + System.currentTimeMillis() + ".zip")
                        )
                    }
                    val upload = try {
                        busyLabel = "Téléversement du ZIP privé…"
                        withContext(Dispatchers.IO) {
                            GitHubBuildClient.uploadWorkspaceArchive(candidate, archive)
                        }
                    } finally {
                        archive.delete()
                    }
                    tokenStore.saveWorkspaceUpload(upload)
                    workspaceUpload = upload
                    busyLabel = "Démarrage du build distant…"
                    withContext(Dispatchers.IO) {
                        GitHubBuildClient.dispatchWorkspaceArchiveBuild(candidate, upload.id)
                    }
                } else {
                    busyLabel = "Démarrage du workflow GitHub…"
                    withContext(Dispatchers.IO) {
                        GitHubBuildClient.dispatchBuild(candidate, repository.trim(), targetRef.trim())
                    }
                }

                if (buildSourceMode == "workspace_zip") {
                    workspaceUpload?.let { upload ->
                        val linkedUpload = upload.copy(runId = created.id)
                        tokenStore.saveWorkspaceUpload(linkedUpload)
                        workspaceUpload = linkedUpload
                    }
                }
                tokenStore.saveRunId(created.id)
                currentRunId = created.id
                currentRun = created
                artifactId = null
                message = if (buildSourceMode == "workspace_zip") {
                    "Workspace envoyé en ZIP privé. Compilation demandée sans push de branche. Les APK et logs seront disponibles à la fin."
                } else {
                    "Demande envoyée à Build Engine pour " + repository.trim() + " · " + targetRef.trim() + "."
                }
                messageIsError = false
            } catch (error: Exception) {
                showError(error)
            } finally {
                busy = false
                busyLabel = ""
            }
        }
    }

    fun loadLogs() {
        val activeToken = token
        val activeRun = currentRun
        if (activeToken == null || activeRun == null) return
        scope.launch {
            busy = true
            busyLabel = "Téléchargement des logs…"
            try {
                val remoteArtifactId = artifactId ?: withContext(Dispatchers.IO) {
                    GitHubBuildClient.getArtifactId(activeToken, activeRun.id)
                } ?: throw IOException("L'artefact build-android n'est pas encore disponible.")
                artifactId = remoteArtifactId
                val archive = withContext(Dispatchers.IO) {
                    GitHubBuildClient.getCachedArtifactZip(context, activeToken, remoteArtifactId)
                }
                logText = withContext(Dispatchers.IO) { GitHubBuildClient.readArtifactLogs(archive) }
                showLogs = true
            } catch (error: Exception) {
                showError(error)
            } finally {
                busy = false
                busyLabel = ""
            }
        }
    }

    fun downloadApk() {
        val activeToken = token
        val activeRun = currentRun
        if (activeToken == null || activeRun == null) return
        scope.launch {
            busy = true
            busyLabel = "Récupération de l'APK…"
            try {
                val remoteArtifactId = artifactId ?: withContext(Dispatchers.IO) {
                    GitHubBuildClient.getArtifactId(activeToken, activeRun.id)
                } ?: throw IOException("L'artefact build-android n'est pas encore disponible.")
                artifactId = remoteArtifactId
                val archive = withContext(Dispatchers.IO) {
                    GitHubBuildClient.getCachedArtifactZip(context, activeToken, remoteArtifactId)
                }
                val extracted = withContext(Dispatchers.IO) {
                    GitHubBuildClient.extractDebugApk(
                        archive,
                        File(context.cacheDir, "nyvibedroid-build-" + activeRun.number + "-debug.apk")
                    )
                }
                val savedPath = withContext(Dispatchers.IO) { copyApkToDownloads(context, extracted) }
                message = "APK debug enregistré : " + savedPath
                messageIsError = false
            } catch (error: Exception) {
                showError(error)
            } finally {
                busy = false
                busyLabel = ""
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "NYVIBE / BUILD",
                    color = Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(7.dp))
                Text("Remote Builder", color = Foreground, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                Text("Compile tes projets Android depuis ton téléphone.", color = Muted, fontSize = 14.sp)
            }
            Surface(shape = RoundedCornerShape(14.dp), color = PanelRaised) {
                Column(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("APK", color = Accent, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text("CLOUD", color = Muted, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionTitle(number = "01", title = "Connexion GitHub", trailing = if (connected) "CONNECTÉ" else "REQUIS")
                Text(
                    "Jeton personnel avec la permission Actions : Read and write sur le dépôt rakmahefa/build.",
                    color = Muted,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = tokenDraft,
                    onValueChange = { tokenDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("GitHub personal access token") },
                    placeholder = { Text(if (connected) "Jeton enregistré sur cet appareil" else "github_pat_…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !busy
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { connectGitHub() },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Canvas)
                    ) {
                        Text(if (connected) "Vérifier" else "Connecter", fontWeight = FontWeight.Bold)
                    }
                    if (connected) {
                        OutlinedButton(
                            onClick = {
                                tokenStore.clearToken()
                                token = null
                                connected = false
                                tokenDraft = ""
                                message = "Jeton supprimé de cet appareil."
                                messageIsError = false
                            },
                            enabled = !busy,
                            shape = RoundedCornerShape(13.dp)
                        ) {
                            Text("Déconnecter", color = Foreground)
                        }
                    }
                }
                Text(
                    "Le jeton est chiffré avec Android Keystore. Il n'est jamais affiché ni écrit dans les logs.",
                    color = Muted,
                    fontSize = 12.sp
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionTitle(number = "02", title = "Projet cible", trailing = "ANDROID")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { buildSourceMode = "workspace_zip" },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (buildSourceMode == "workspace_zip") Accent else PanelRaised,
                            contentColor = if (buildSourceMode == "workspace_zip") Canvas else Foreground
                        )
                    ) {
                        Text("Workspace ZIP", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { buildSourceMode = "github_ref" },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(11.dp)
                    ) {
                        Text("Branche GitHub", color = if (buildSourceMode == "github_ref") Accent else Foreground)
                    }
                }

                if (buildSourceMode == "workspace_zip") {
                    val activeWorkspaceUri = WorkspaceRepository.loadRootUri(context)
                    Surface(
                        color = PanelRaised,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Text(
                                if (activeWorkspaceUri.isNullOrBlank()) "Aucun workspace sélectionné" else "Workspace prêt à envoyer",
                                color = if (activeWorkspaceUri.isNullOrBlank()) Danger else Accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                if (activeWorkspaceUri.isNullOrBlank()) {
                                    "Ouvre l'onglet Workspace et choisis le dossier du projet. Reviens ici ensuite."
                                } else {
                                    "Le dossier actif sera compressé en ZIP puis envoyé à Build Engine. Pas besoin de créer une branche ni de pousser tes changements."
                                },
                                color = Muted,
                                fontSize = 12.sp
                            )
                            Text(
                                "Les métadonnées .git, caches de build, local.properties, fichiers .env locaux et clés privées sont exclus. Limites : 512 Mio de sources, 200 Mio d'archive.",
                                color = Muted,
                                fontSize = 11.sp
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = repository,
                        onValueChange = { repository = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Dépôt GitHub") },
                        placeholder = { Text("propriétaire/nom-du-dépôt") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        enabled = !busy
                    )
                    OutlinedTextField(
                        value = targetRef,
                        onValueChange = { targetRef = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Branche, tag ou commit") },
                        placeholder = { Text("main") },
                        singleLine = true,
                        enabled = !busy
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                busyLabel = "Lecture du dépôt Git…"
                                try {
                                    val target = withContext(Dispatchers.IO) {
                                        GitRepositoryManager.currentWorkspaceBuildTarget(context)
                                    }
                                    workspaceTarget = target
                                    if (target == null) {
                                        message = "Aucun workspace ou dépôt Git actif disponible. Dans l'onglet Git, clone ou sélectionne un dépôt, puis choisis « Définir comme Workspace actif et ouvrir »."
                                        messageIsError = true
                                    } else {
                                        repository = target.repository
                                        targetRef = target.ref
                                        message = "Cible sélectionnée : " + target.repository + " · " + target.ref + "."
                                        messageIsError = false
                                    }
                                } catch (error: Exception) {
                                    showError(error)
                                } finally {
                                    busy = false
                                    busyLabel = ""
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Utiliser le dépôt Git actif", color = Foreground)
                    }
                    val selectedWorkspaceTarget = workspaceTarget?.takeIf {
                        repository.equals(it.repository, ignoreCase = true) && targetRef == it.ref
                    }
                    if (selectedWorkspaceTarget != null) {
                        Text(
                            "Mode branche : Build Engine récupère le code depuis GitHub. Les fichiers locaux non poussés ne sont pas envoyés.",
                            color = if (selectedWorkspaceTarget.hasLocalChanges || selectedWorkspaceTarget.commitsAhead > 0) Danger else Muted,
                            fontSize = 12.sp
                        )
                    }
                }
                Surface(
                    color = PanelRaised,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("●", color = Accent, fontSize = 12.sp)
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text("Android Gradle", color = Foreground, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("JDK 17 · Gradle 8.9 · APK debug + release", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
                Button(
                    onClick = { startBuild() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Canvas)
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Canvas,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(busyLabel.ifBlank { "Traitement…" }, fontWeight = FontWeight.Bold)
                    } else {
                        Text("Lancer la compilation  ↗", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    }
                }
                Text(
                    "Le dépôt cible doit être une application Android Gradle. Pour un dépôt privé, ajoute un secret BUILD_TARGET_TOKEN en lecture seule dans rakmahefa/build.",
                    color = Muted,
                    fontSize = 12.sp
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("03  /  ÉTAT DU BUILD", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("Dernière exécution", color = Foreground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { refreshRun() },
                        enabled = !busy && currentRun != null && token != null,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Actualiser", color = Foreground)
                    }
                }

                if (currentRun == null) {
                    Text(
                        "Aucune compilation suivie pour le moment. Lance un build : son statut, ses logs et son APK apparaîtront ici.",
                        color = Muted,
                        fontSize = 14.sp
                    )
                } else {
                    val statusText = statusLabel(currentRun!!)
                    val statusColor = when {
                        currentRun!!.status != "completed" -> Color(0xFF84BBFF)
                        currentRun!!.conclusion == "success" -> Accent
                        else -> Danger
                    }
                    Surface(
                        color = PanelRaised,
                        shape = RoundedCornerShape(15.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("●", color = statusColor, fontSize = 13.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(statusText, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(Modifier.weight(1f))
                                Text("#" + currentRun!!.number, color = Muted, fontSize = 12.sp)
                            }
                            Text(currentRun!!.name, color = Foreground, fontWeight = FontWeight.SemiBold)
                            Text("Run ID : " + currentRun!!.id, color = Muted, fontSize = 12.sp)
                            Text(
                                "Créé le : " + currentRun!!.createdAt.replace("T", " ").removeSuffix("Z"),
                                color = Muted,
                                fontSize = 12.sp
                            )
                            Divider(color = Border)
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentRun!!.htmlUrl)))
                                        }.onFailure { showError(it) }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Ouvrir GitHub", color = Foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (currentRun!!.status == "completed" && currentRun!!.conclusion == "success") {
                                    Button(
                                        onClick = { downloadApk() },
                                        enabled = !busy,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Canvas)
                                    ) {
                                        Text("Télécharger APK", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (currentRun!!.status == "completed") {
                                OutlinedButton(
                                    onClick = { loadLogs() },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Consulter les logs de compilation", color = Foreground)
                                }
                                if (artifactId == null) {
                                    Text("Artefact en cours de vérification ; utilise Actualiser si les fichiers tardent.", color = Muted, fontSize = 12.sp)
                                }
                            } else {
                                Text("Actualisation automatique toutes les 8 secondes pendant que cet écran est ouvert.", color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        if (message != null) {
            Surface(
                color = if (messageIsError) Color(0xFF351F29) else Color(0xFF1D3025),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    message.orEmpty(),
                    modifier = Modifier.padding(14.dp),
                    color = if (messageIsError) Danger else Accent,
                    fontSize = 13.sp
                )
            }
        }

        Text(
            "Build Engine · rakmahefa/build  •  Seul Android est actuellement pris en charge.",
            color = Muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }

    if (showLogs && logText != null) {
        AlertDialog(
            onDismissRequest = { showLogs = false },
            title = { Text("Logs de compilation", color = Foreground, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        logText.orEmpty(),
                        color = Color(0xFFD8E0EB),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogs = false }) {
                    Text("Fermer", color = Accent)
                }
            },
            containerColor = Panel
        )
    }
}

@Composable
private fun SectionTitle(number: String, title: String, trailing: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(number, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.width(10.dp))
        Text(title, color = Foreground, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Surface(color = PanelRaised, shape = RoundedCornerShape(8.dp)) {
            Text(
                trailing,
                color = Muted,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.7.sp,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
            )
        }
    }
}

private fun statusLabel(run: BuildRun): String = when (run.status) {
    "queued", "requested", "waiting", "pending" -> "En attente"
    "in_progress" -> "Compilation en cours"
    "completed" -> when (run.conclusion) {
        "success" -> "Compilation réussie"
        "failure" -> "Compilation échouée"
        "cancelled" -> "Compilation annulée"
        "timed_out" -> "Délai dépassé"
        "skipped" -> "Compilation ignorée"
        else -> "Terminé · " + (run.conclusion ?: "inconnu")
    }
    else -> "Statut : " + run.status
}

private fun readableError(error: Throwable): String {
    val message = error.message?.trim().orEmpty()
    return message.ifBlank { "Une erreur inattendue s'est produite." }
}

private fun copyApkToDownloads(context: android.content.Context, apk: File): String {
    val filename = "NyVibeDroid-" + apk.nameWithoutExtension + ".apk"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NyVibeDroid")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Android n'a pas pu créer le fichier dans Téléchargements.")
        try {
            val output = resolver.openOutputStream(uri)
                ?: throw IOException("Impossible d'ouvrir le fichier téléchargé.")
            output.use { destination -> apk.inputStream().use { it.copyTo(destination) } }
            val ready = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, ready, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        return "Téléchargements/NyVibeDroid/" + filename
    }

    val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: context.filesDir
    val destination = File(directory, filename)
    apk.copyTo(destination, overwrite = true)
    return destination.absolutePath
}
