package com.nyvibe.droid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private val GitCanvas = Color(0xFF0B1018)
private val GitPanel = Color(0xFF151D2A)
private val GitRaised = Color(0xFF1B2636)
private val GitAccent = Color(0xFFB7F56A)
private val GitText = Color(0xFFF2F5F9)
private val GitMuted = Color(0xFF9CAABD)
private val GitBorder = Color(0xFF2B384B)
private val GitError = Color(0xFFFF8989)

@Composable
internal fun GitRepositoryScreen(onOpenInWorkspace: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var remoteInput by rememberSaveable { mutableStateOf("rakmahefa/nyvibedroid") }
    var tokenDraft by rememberSaveable { mutableStateOf("") }
    var tokenSaved by remember { mutableStateOf(GitRepositoryManager.loadCredential(context) != null) }
    var repositories by remember { mutableStateOf(emptyList<LocalGitRepository>()) }
    var selectedPath by remember { mutableStateOf(GitRepositoryManager.selectedRepository(context)) }
    var state by remember { mutableStateOf<GitRepositoryState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var branchDraft by rememberSaveable { mutableStateOf("") }
    var commitMessage by rememberSaveable { mutableStateOf("") }
    var showCommitDialog by remember { mutableStateOf(false) }
    var diffText by remember { mutableStateOf<String?>(null) }
    var diffTitle by remember { mutableStateOf("Diff") }

    fun fail(error: Throwable) {
        errorMessage = error.message?.takeIf { it.isNotBlank() } ?: "L'opération Git a échoué."
        infoMessage = null
    }

    suspend fun reload() {
        repositories = withContext(Dispatchers.IO) { GitRepositoryManager.listRepositories(context) }
        val chosen = selectedPath?.let { path -> repositories.firstOrNull { it.directory == path } }
            ?: repositories.firstOrNull()
        selectedPath = chosen?.directory
        if (chosen != null) {
            withContext(Dispatchers.IO) { GitRepositoryManager.selectRepository(context, chosen.directory) }
            state = withContext(Dispatchers.IO) { GitRepositoryManager.refresh(context, chosen.directory) }
            if (branchDraft.isBlank()) branchDraft = state?.repository?.currentBranch.orEmpty()
        } else state = null
    }

    LaunchedEffect(Unit) {
        runCatching { reload() }.onFailure { fail(it) }
    }

    fun action(label: String, block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            busyLabel = label
            errorMessage = null
            try {
                block()
                reload()
                infoMessage = label + " terminé."
            } catch (error: Exception) {
                fail(error)
            } finally {
                busy = false
                busyLabel = ""
            }
        }
    }

    fun cloneRepo() {
        val repoInput = remoteInput.trim()
        val token = tokenDraft.trim().ifBlank { GitRepositoryManager.loadCredential(context).orEmpty() }
        action("Clonage") {
            val clone = withContext(Dispatchers.IO) {
                GitRepositoryManager.cloneRepository(context, repoInput, token.ifBlank { null })
            }
            selectedPath = clone.directory
            branchDraft = clone.currentBranch
            remoteInput = clone.remoteUrl
            if (tokenDraft.isNotBlank()) {
                withContext(Dispatchers.IO) { GitRepositoryManager.saveCredential(context, tokenDraft.trim()) }
                tokenSaved = true
                tokenDraft = ""
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(GitCanvas).verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("NYVIBE / VERSION CONTROL", color = GitAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
            Text("Git Repository", color = GitText, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("Cloner, inspecter, committer et synchroniser un projet.", color = GitMuted, fontSize = 13.sp)
        }

        GitCard {
            GitSectionTitle("01", "Cloner depuis GitHub", if (repositories.isEmpty()) "NOUVEAU" else repositories.size.toString() + " LOCAL")
            OutlinedTextField(
                value = remoteInput, onValueChange = { remoteInput = it }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("owner/repository ou URL HTTPS") },
                placeholder = { Text("rakmahefa/nyvibedroid") }, enabled = !busy
            )
            OutlinedTextField(
                value = tokenDraft, onValueChange = { tokenDraft = it }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("Jeton GitHub (optionnel pour dépôt public)") },
                placeholder = { Text(if (tokenSaved) "Jeton enregistré sur cet appareil" else "Jeton avec accès Contents") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !busy
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { cloneRepo() }, enabled = !busy && remoteInput.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = GitAccent, contentColor = GitCanvas),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (busy && busyLabel == "Clonage") {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = GitCanvas, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (busy && busyLabel == "Clonage") "Clonage…" else "Cloner le dépôt", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = {
                        action("Enregistrement du jeton") {
                            if (tokenDraft.isBlank()) throw IOException("Saisis un jeton Git.")
                            withContext(Dispatchers.IO) { GitRepositoryManager.saveCredential(context, tokenDraft.trim()) }
                            tokenSaved = true
                            tokenDraft = ""
                        }
                    },
                    enabled = !busy && tokenDraft.isNotBlank(), shape = RoundedCornerShape(12.dp)
                ) { Text("Enregistrer", color = GitText) }
            }
            Text(
                "Le jeton Git est séparé de celui de Remote Build et chiffré avec Android Keystore. Pour push/pull privé, configure Contents: Read and write sur le dépôt.",
                color = GitMuted, fontSize = 12.sp
            )
            if (tokenSaved) {
                TextButton(
                    onClick = {
                        action("Suppression du jeton") {
                            withContext(Dispatchers.IO) { GitRepositoryManager.clearCredential(context) }
                            tokenSaved = false
                        }
                    },
                    enabled = !busy
                ) { Text("Supprimer le jeton enregistré", color = GitMuted) }
            }
        }

        GitCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    GitSectionTitle("02", "Dépôts locaux", repositories.size.toString() + " dépôt(s)")
                    Text("Clonés dans l'espace privé de l'application.", color = GitMuted, fontSize = 12.sp)
                }
                OutlinedButton(onClick = { action("Actualisation") { } }, enabled = !busy, shape = RoundedCornerShape(11.dp)) {
                    Text("↻", color = GitText, fontSize = 18.sp)
                }
            }
            if (repositories.isEmpty()) {
                Text("Aucun dépôt cloné. Clone un dépôt GitHub pour démarrer.", color = GitMuted, fontSize = 13.sp)
            } else {
                repositories.forEach { repo ->
                    val selected = selectedPath == repo.directory
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(if (selected) GitRaised else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable(enabled = !busy) {
                                action("Ouverture du dépôt") {
                                    selectedPath = repo.directory
                                    withContext(Dispatchers.IO) { GitRepositoryManager.selectRepository(context, repo.directory) }
                                    branchDraft = repo.currentBranch
                                }
                            }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⑂", color = GitAccent, fontSize = 20.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(repo.name, color = GitText, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(repo.remoteUrl, color = GitMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (selected) Text("ACTIF", color = GitAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        GitCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    GitSectionTitle("03", "État du dépôt", state?.repository?.currentBranch ?: "NON SÉLECTIONNÉ")
                    if (state != null) Text(state!!.repository.remoteUrl, color = GitMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { action("Actualisation Git") { } }, enabled = !busy && state != null, shape = RoundedCornerShape(11.dp)) {
                    Text("Actualiser", color = GitText)
                }
            }
            if (state == null) {
                Text("Sélectionne ou clone un dépôt pour voir son état Git.", color = GitMuted, fontSize = 13.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    GitMetric("MODIFIÉS", state!!.changes.size.toString())
                    GitMetric("EN AVANCE", state!!.ahead.toString())
                    GitMetric("EN RETARD", state!!.behind.toString())
                }
                Text("Commit courant : " + (state!!.lastCommit ?: "aucun"), color = GitMuted, fontSize = 12.sp)
                Divider(color = GitBorder)
                Text("BRANCHES LOCALES", color = GitMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                OutlinedTextField(
                    value = branchDraft, onValueChange = { branchDraft = it }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, label = { Text("Branche à basculer ou créer") }, enabled = !busy
                )
                Button(
                    onClick = {
                        val path = selectedPath ?: return@Button
                        action("Changement de branche") {
                            withContext(Dispatchers.IO) { GitRepositoryManager.createOrCheckoutBranch(context, path, branchDraft) }
                        }
                    },
                    enabled = !busy && branchDraft.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = GitRaised, contentColor = GitAccent),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Basculer / créer la branche", fontWeight = FontWeight.Bold) }
                state!!.branches.forEach { branchName ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { branchDraft = branchName }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (branchName == state!!.repository.currentBranch) "●" else "○", color = GitAccent, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(branchName, color = GitText, fontSize = 12.sp)
                    }
                }
                Divider(color = GitBorder)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    GitActionButton("Fetch", !busy) {
                        action("Fetch") { withContext(Dispatchers.IO) { GitRepositoryManager.fetch(context, selectedPath!!, GitRepositoryManager.loadCredential(context)) } }
                    }
                    GitActionButton("Pull", !busy) {
                        action("Pull") { withContext(Dispatchers.IO) { GitRepositoryManager.pull(context, selectedPath!!, GitRepositoryManager.loadCredential(context)) } }
                    }
                    GitActionButton("Push", !busy) {
                        action("Push") { withContext(Dispatchers.IO) { GitRepositoryManager.push(context, selectedPath!!, GitRepositoryManager.loadCredential(context)) } }
                    }
                }
            }
        }

        GitCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    GitSectionTitle("04", "Modifications", (state?.changes?.size ?: 0).toString() + " fichier(s)")
                    Text("Touchez un fichier pour afficher son diff.", color = GitMuted, fontSize = 12.sp)
                }
                Button(
                    onClick = { showCommitDialog = true },
                    enabled = !busy && state?.changes?.isNotEmpty() == true, shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GitAccent, contentColor = GitCanvas)
                ) { Text("Commit", fontWeight = FontWeight.Bold) }
            }
            if (state == null || state!!.changes.isEmpty()) {
                Text("Aucune modification locale détectée.", color = GitMuted, fontSize = 13.sp)
            } else {
                state!!.changes.forEachIndexed { index, change ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            val path = selectedPath ?: return@clickable
                            action("Lecture du diff") {
                                diffTitle = change.path
                                diffText = withContext(Dispatchers.IO) { GitRepositoryManager.diff(context, path, change.path) }
                            }
                        }.padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(color = GitRaised, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(32.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(change.status, color = if (change.status == "!" || change.status == "D") GitError else GitAccent, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(change.path, color = GitText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(change.summary, color = GitMuted, fontSize = 11.sp)
                        }
                        Text(if (change.staged) "INDEX" else "LOCAL", color = GitMuted, fontSize = 9.sp)
                    }
                    if (index < state!!.changes.lastIndex) Divider(color = GitBorder, thickness = 0.6.dp)
                }
                OutlinedButton(
                    onClick = {
                        val path = selectedPath ?: return@OutlinedButton
                        action("Diff complet") {
                            diffTitle = "Diff complet"
                            diffText = withContext(Dispatchers.IO) { GitRepositoryManager.diff(context, path) }
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp)
                ) { Text("Afficher le diff complet", color = GitText) }
            }
            Text("Ouvre le dépôt dans Workspace pour modifier le code. Les modifications sont suivies par Git dès leur sauvegarde.", color = GitMuted, fontSize = 11.sp)
            OutlinedButton(
                onClick = {
                    val path = selectedPath ?: return@OutlinedButton
                    runCatching {
                        val uri = GitRepositoryManager.managedDirectoryUri(context, path)
                        WorkspaceRepository.saveRootUri(context, android.net.Uri.parse(uri))
                        onOpenInWorkspace(uri)
                    }.onFailure { fail(it) }
                }, enabled = !busy && selectedPath != null, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            ) { Text("Ouvrir ce dépôt dans Workspace", color = GitAccent) }
        }

        if (errorMessage != null) GitMessage(errorMessage.orEmpty(), true)
        else if (infoMessage != null) GitMessage(infoMessage.orEmpty(), false)
        Text("JGit · HTTPS GitHub · stockage local privé", color = GitMuted, fontSize = 10.sp)
    }

    if (showCommitDialog) {
        AlertDialog(
            onDismissRequest = { showCommitDialog = false },
            title = { Text("Commit des modifications", color = GitText, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Toutes les modifications locales seront ajoutées à l'index puis commitées.", color = GitMuted, fontSize = 13.sp)
                    OutlinedTextField(
                        value = commitMessage, onValueChange = { commitMessage = it },
                        label = { Text("Message du commit") }, minLines = 2,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val path = selectedPath
                    val message = commitMessage
                    showCommitDialog = false
                    if (path != null) action("Commit") {
                        withContext(Dispatchers.IO) { GitRepositoryManager.commitAll(context, path, message) }
                        commitMessage = ""
                    }
                }) { Text("Créer le commit", color = GitAccent, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showCommitDialog = false }) { Text("Annuler", color = GitMuted) } },
            containerColor = GitPanel
        )
    }

    if (diffText != null) {
        AlertDialog(
            onDismissRequest = { diffText = null },
            title = { Text(diffTitle, color = GitText, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().height(440.dp).verticalScroll(rememberScrollState())) {
                    Text(diffText.orEmpty(), color = GitText, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            },
            confirmButton = { TextButton(onClick = { diffText = null }) { Text("Fermer", color = GitAccent) } },
            containerColor = GitPanel
        )
    }
}

@Composable
private fun GitCard(content: @Composable () -> Unit) {
    Surface(color = GitPanel, shape = RoundedCornerShape(19.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) { content() }
    }
}

@Composable
private fun GitSectionTitle(number: String, title: String, trailing: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(number, color = GitAccent, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.width(9.dp))
        Text(title, color = GitText, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Surface(color = GitRaised, shape = RoundedCornerShape(7.dp)) {
            Text(trailing, color = GitMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RowScope.GitMetric(label: String, value: String) {
    Surface(color = GitRaised, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)) {
        Column(modifier = Modifier.padding(9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = GitAccent, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
            Text(label, color = GitMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun RowScope.GitActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
        Text(label, color = GitText)
    }
}

@Composable
private fun GitMessage(message: String, isError: Boolean) {
    Surface(color = if (isError) Color(0xFF351F29) else Color(0xFF1D3025), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(message, color = if (isError) GitError else GitAccent, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
    }
}
