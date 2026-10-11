package com.nyvibe.droid

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private val WorkspaceCanvas = Color(0xFF0B1018)
private val WorkspacePanel = Color(0xFF151D2A)
private val WorkspaceRaised = Color(0xFF1B2636)
private val WorkspaceAccent = Color(0xFFB7F56A)
private val WorkspaceText = Color(0xFFF2F5F9)
private val WorkspaceMuted = Color(0xFF9CAABD)
private val WorkspaceBorder = Color(0xFF2B384B)
private val WorkspaceError = Color(0xFFFF8989)

private data class WorkspaceCrumb(val uri: String, val name: String)

@Composable
internal fun WorkspaceScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var rootUri by remember { mutableStateOf(WorkspaceRepository.loadRootUri(context)) }
    var breadcrumbs by remember { mutableStateOf<List<WorkspaceCrumb>>(emptyList()) }
    var items by remember { mutableStateOf<List<WorkspaceItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var refreshCounter by remember { mutableStateOf(0) }
    var activeFile by remember { mutableStateOf<WorkspaceItem?>(null) }
    var editorText by remember { mutableStateOf("") }
    var originalText by remember { mutableStateOf("") }
    var createDialogOpen by remember { mutableStateOf(false) }
    var createErrorMessage by remember { mutableStateOf<String?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var newFileName by rememberSaveable { mutableStateOf("NewFile.kt") }
    var isSaving by remember { mutableStateOf(false) }
    var isConverting by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { pickedUri ->
        if (pickedUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    pickedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                WorkspaceRepository.saveRootUri(context, pickedUri)
                rootUri = pickedUri.toString()
                breadcrumbs = listOf(
                    WorkspaceCrumb(
                        uri = pickedUri.toString(),
                        name = WorkspaceRepository.rootName(context, pickedUri.toString())
                    )
                )
                items = emptyList()
                activeFile = null
                infoMessage = "Workspace prêt. Les fichiers restent dans le dossier choisi."
                errorMessage = null
            } catch (error: Exception) {
                errorMessage = readableWorkspaceError(error)
            }
        }
    }

    LaunchedEffect(rootUri) {
        val savedUri = rootUri ?: return@LaunchedEffect
        if (breadcrumbs.isEmpty() || breadcrumbs.firstOrNull()?.uri != savedUri) {
            runCatching {
                withContext(Dispatchers.IO) { WorkspaceRepository.rootName(context, savedUri) }
            }.onSuccess { name ->
                breadcrumbs = listOf(WorkspaceCrumb(savedUri, name))
            }.onFailure { error ->
                errorMessage = "Le workspace enregistré n'est plus accessible. Choisis de nouveau un dossier. " +
                    readableWorkspaceError(error)
                breadcrumbs = emptyList()
            }
        }
    }

    val currentFolderUri = breadcrumbs.lastOrNull()?.uri ?: rootUri
    LaunchedEffect(rootUri, currentFolderUri, refreshCounter) {
        if (currentFolderUri == null) {
            items = emptyList()
            return@LaunchedEffect
        }
        isLoading = true
        runCatching {
            withContext(Dispatchers.IO) { WorkspaceRepository.list(context, currentFolderUri) }
        }.onSuccess { loaded ->
            items = loaded
            errorMessage = null
        }.onFailure { error ->
            items = emptyList()
            errorMessage = readableWorkspaceError(error)
        }
        isLoading = false
    }

    fun openItem(item: WorkspaceItem) {
        if (item.isDirectory) {
            breadcrumbs = breadcrumbs + WorkspaceCrumb(item.uri, item.name)
            activeFile = null
            return
        }
        scope.launch {
            errorMessage = null
            infoMessage = "Ouverture de ${item.name}…"
            try {
                val content = withContext(Dispatchers.IO) {
                    WorkspaceRepository.readText(context, item)
                }
                activeFile = item
                editorText = content
                originalText = content
                infoMessage = null
            } catch (error: Exception) {
                infoMessage = null
                errorMessage = readableWorkspaceError(error)
            }
        }
    }

    fun saveCurrentFile() {
        val file = activeFile ?: return
        scope.launch {
            isSaving = true
            try {
                withContext(Dispatchers.IO) {
                    WorkspaceRepository.saveText(context, file, editorText)
                }
                originalText = editorText
                infoMessage = "Fichier enregistré : ${file.name}"
                errorMessage = null
                refreshCounter++
            } catch (error: Exception) {
                errorMessage = readableWorkspaceError(error)
            } finally {
                isSaving = false
            }
        }
    }

    fun createCurrentFile() {
        val parent = currentFolderUri ?: return
        scope.launch {
            try {
                val created = withContext(Dispatchers.IO) {
                    WorkspaceRepository.createTextFile(context, parent, newFileName)
                }
                createDialogOpen = false
                editorText = ""
                originalText = ""
                activeFile = created
                infoMessage = "Nouveau fichier : ${created.name}"
                createErrorMessage = null
                errorMessage = null
                refreshCounter++
            } catch (error: Exception) {
                createErrorMessage = readableWorkspaceError(error)
            }
        }
    }

    fun convertWorkspaceToManagedRepository() {
        if (isConverting || rootUri == null) return
        val sourceUri = rootUri.orEmpty()
        scope.launch {
            isConverting = true
            errorMessage = null
            infoMessage = "Préparation du dépôt local…"
            try {
                val repository = withContext(Dispatchers.IO) {
                    GitRepositoryManager.makeWorkspaceManaged(context)
                }
                val managedUri = Uri.fromFile(java.io.File(repository.directory)).toString()
                rootUri = managedUri
                breadcrumbs = listOf(WorkspaceCrumb(managedUri, repository.name))
                activeFile = null
                items = emptyList()
                refreshCounter++
                infoMessage = if (Uri.parse(sourceUri).scheme == "content") {
                    "Workspace importé dans le stockage privé et initialisé comme dépôt Git local. Le dossier d'origine n'a pas été modifié."
                } else {
                    "Workspace initialisé comme dépôt Git local : " + repository.name
                }
            } catch (error: Exception) {
                errorMessage = readableWorkspaceError(error)
                infoMessage = null
            } finally {
                isConverting = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(WorkspaceCanvas).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("NYVIBE / WORKSPACE", color = WorkspaceAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
            Text(
                if (activeFile == null) "Fichiers & projets" else activeFile!!.name,
                color = WorkspaceText, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                if (activeFile == null) "Un workspace local, sous ton contrôle." else "Éditeur texte · UTF-8",
                color = WorkspaceMuted, fontSize = 13.sp
            )
        }

        if (activeFile == null) {
            Surface(color = WorkspacePanel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("WORKSPACE ACTIF", color = WorkspaceMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                breadcrumbs.firstOrNull()?.name ?: "Aucun dossier choisi",
                                color = WorkspaceText, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Button(
                            onClick = { folderPicker.launch(rootUri?.let { Uri.parse(it) }?.takeIf { it.scheme == "content" }) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = WorkspaceAccent, contentColor = WorkspaceCanvas)
                        ) {
                            Text(if (rootUri == null) "Ouvrir dossier" else "Changer", fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        "L'accès est accordé par le sélecteur Android. L'application ne parcourt pas les autres dossiers.",
                        color = WorkspaceMuted, fontSize = 12.sp
                    )
                    if (rootUri != null) {
                        Button(
                            onClick = { convertWorkspaceToManagedRepository() },
                            enabled = !isConverting && !isLoading,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WorkspaceRaised,
                                contentColor = WorkspaceAccent
                            )
                        ) {
                            if (isConverting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = WorkspaceAccent
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                if (isConverting) "Import du workspace…" else "Transformer le workspace actif en dépôt gérable",
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            "Un dossier choisi via Android sera copié dans l'espace privé de l'application, puis initialisé avec Git. L'original reste intact.",
                            color = WorkspaceMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            if (rootUri != null && breadcrumbs.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { if (breadcrumbs.size > 1) breadcrumbs = breadcrumbs.dropLast(1) },
                        enabled = breadcrumbs.size > 1, shape = RoundedCornerShape(10.dp)
                    ) { Text("← Retour", color = WorkspaceText) }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            breadcrumbs.joinToString("  /  ") { it.name }, color = WorkspaceMuted,
                            fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Text(if (isLoading) "Lecture du dossier…" else "${items.size} élément(s)", color = WorkspaceAccent, fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { refreshCounter++ }, enabled = !isLoading, shape = RoundedCornerShape(10.dp)) {
                        Text("↻", color = WorkspaceText, fontSize = 18.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CONTENU", color = WorkspaceText, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { newFileName = "NewFile.kt"; createErrorMessage = null; createDialogOpen = true }, enabled = !isLoading) {
                        Text("+ Nouveau fichier", color = WorkspaceAccent, fontWeight = FontWeight.Bold)
                    }
                }
                Surface(color = WorkspacePanel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        isLoading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = WorkspaceAccent)
                        }
                        items.isEmpty() -> EmptyFolderMessage(onCreate = { newFileName = "NewFile.kt"; createDialogOpen = true })
                        else -> Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            items.forEachIndexed { index, item ->
                                WorkspaceFileRow(item = item, onClick = { openItem(item) })
                                if (index < items.lastIndex) Divider(color = WorkspaceBorder, thickness = 0.7.dp)
                            }
                        }
                    }
                }
            } else {
                Surface(color = WorkspacePanel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                    EmptyWorkspaceMessage()
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (editorText != originalText) showDiscardDialog = true
                        else { activeFile = null; infoMessage = null; errorMessage = null }
                    },
                    shape = RoundedCornerShape(11.dp)
                ) { Text("← Fichiers", color = WorkspaceText) }
                Text(
                    if (editorText != originalText) "Modifications non enregistrées" else "Modifications enregistrées",
                    color = if (editorText != originalText) WorkspaceAccent else WorkspaceMuted,
                    fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 2
                )
            }
            Surface(color = WorkspacePanel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(activeFile!!.name, color = WorkspaceText, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${editorText.toByteArray(Charsets.UTF_8).size / 1024} KiB", color = WorkspaceMuted, fontSize = 11.sp)
                    }
                    Divider(color = WorkspaceBorder, modifier = Modifier.padding(vertical = 9.dp))
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        BasicTextField(
                            value = editorText,
                            onValueChange = { editorText = it },
                            modifier = Modifier.fillMaxSize().padding(4.dp),
                            textStyle = TextStyle(color = WorkspaceText, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrect = false,
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Default
                            ),
                            decorationBox = { innerTextField ->
                                Box(Modifier.fillMaxSize()) {
                                    if (editorText.isEmpty()) Text("Commence à écrire…", color = WorkspaceMuted, fontSize = 13.sp)
                                    innerTextField()
                                }
                            }
                        )
                    }
                    Divider(color = WorkspaceBorder, modifier = Modifier.padding(vertical = 9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { editorText = originalText; infoMessage = "Modifications annulées." },
                            enabled = editorText != originalText && !isSaving,
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)
                        ) { Text("Annuler", color = WorkspaceText) }
                        Button(
                            onClick = { saveCurrentFile() },
                            enabled = editorText != originalText && !isSaving,
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = WorkspaceAccent, contentColor = WorkspaceCanvas)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = WorkspaceCanvas)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (isSaving) "Enregistrement…" else "Enregistrer", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (errorMessage != null) {
            Surface(color = Color(0xFF351F29), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(errorMessage.orEmpty(), color = WorkspaceError, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            }
        } else if (infoMessage != null) {
            Surface(color = Color(0xFF1D3025), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(infoMessage.orEmpty(), color = WorkspaceAccent, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            }
        }

        Text("WORKSPACE LOCAL  •  fichiers texte jusqu'à 1 Mio  •  stockage Android", color = WorkspaceMuted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 3.dp))
    }

    if (createDialogOpen) {
        AlertDialog(
            onDismissRequest = { createDialogOpen = false; createErrorMessage = null },
            title = { Text("Créer un fichier", color = WorkspaceText) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Le fichier sera créé dans le dossier affiché.", color = WorkspaceMuted, fontSize = 13.sp)
                    if (createErrorMessage != null) Text(createErrorMessage.orEmpty(), color = WorkspaceError, fontSize = 12.sp)
                    OutlinedTextField(
                        value = newFileName, onValueChange = { newFileName = it },
                        singleLine = true, label = { Text("Nom du fichier") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { createCurrentFile() }) { Text("Créer", color = WorkspaceAccent, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { createDialogOpen = false; createErrorMessage = null }) { Text("Annuler", color = WorkspaceMuted) }
            },
            containerColor = WorkspacePanel
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Abandonner les modifications ?", color = WorkspaceText) },
            text = { Text("Les changements non enregistrés seront perdus.", color = WorkspaceMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    editorText = originalText
                    activeFile = null
                    infoMessage = null
                    errorMessage = null
                }) { Text("Abandonner", color = WorkspaceError, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Continuer l\u0027édition", color = WorkspaceAccent) }
            },
            containerColor = WorkspacePanel
        )
    }
}

@Composable
private fun WorkspaceFileRow(item: WorkspaceItem, onClick: () -> Unit) {
    val editable = item.isDirectory || WorkspaceRepository.isTextFile(item.name, item.mimeType)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = editable, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(color = if (item.isDirectory) Color(0xFF263923) else WorkspaceRaised, shape = RoundedCornerShape(10.dp), modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(if (item.isDirectory) "▰" else fileGlyph(item.name), color = if (item.isDirectory) WorkspaceAccent else WorkspaceMuted, fontSize = if (item.isDirectory) 19.sp else 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.name, color = if (editable) WorkspaceText else WorkspaceMuted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    item.isDirectory -> "Dossier"
                    item.size > 0 -> prettyBytes(item.size) + if (editable) " · Texte éditable" else " · Type non pris en charge"
                    else -> if (editable) "Fichier texte" else "Type non pris en charge"
                },
                color = WorkspaceMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text(if (item.isDirectory) "›" else if (editable) "↗" else "—", color = if (editable) WorkspaceAccent else WorkspaceMuted, fontSize = 20.sp)
    }
}

@Composable
private fun EmptyWorkspaceMessage() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("⌂", color = WorkspaceAccent, fontSize = 38.sp)
            Text("Choisis ton premier workspace", color = WorkspaceText, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("Ouvre un dossier de projet sur ton appareil pour explorer et modifier ses fichiers.", color = WorkspaceMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun EmptyFolderMessage(onCreate: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Dossier vide", color = WorkspaceText, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("Ajoute ton premier fichier texte ou ouvre un autre dossier.", color = WorkspaceMuted, fontSize = 13.sp)
            Button(onClick = onCreate, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = WorkspaceAccent, contentColor = WorkspaceCanvas)) {
                Text("+ Créer un fichier", fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun fileGlyph(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> "KT"
    "java" -> "JV"
    "json" -> "{}"
    "md" -> "MD"
    "xml", "html" -> "<>"
    "gradle" -> "GR"
    "yml", "yaml" -> "Y"
    "py" -> "PY"
    "rs" -> "RS"
    "js", "ts", "tsx", "jsx" -> "JS"
    "sh" -> "SH"
    "txt" -> "TXT"
    else -> "TXT"
}

private fun prettyBytes(bytes: Long): String = when {
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    else -> "${bytes / (1024 * 1024)} MiB"
}

private fun readableWorkspaceError(error: Throwable): String {
    val message = error.message?.trim().orEmpty()
    return message.ifBlank { "Une erreur inattendue s'est produite." }
}
