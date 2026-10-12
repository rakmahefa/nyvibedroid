package com.nyvibe.droid

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val AiCanvas = Color(0xFF0B1018)
private val AiPanel = Color(0xFF151D2A)
private val AiRaised = Color(0xFF1B2636)
private val AiAccent = Color(0xFFB7F56A)
private val AiText = Color(0xFFF2F5F9)
private val AiMuted = Color(0xFF9CAABD)
private val AiBorder = Color(0xFF2B384B)
private val AiError = Color(0xFFFF8989)
private val AiAdded = Color(0xFF17382A)
private val AiRemoved = Color(0xFF3A202A)

@Composable
internal fun AiAssistantScreen(
    fileName: String,
    currentContent: String,
    onApplyProposal: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val provider: AiProvider = ChatGptCloudProvider
    var account by remember { mutableStateOf(ChatGptPlanClient.savedAccount(context)) }
    var models by remember { mutableStateOf<List<AiModel>>(emptyList()) }
    var selectedModelId by rememberSaveable {
        mutableStateOf(
            context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                .getString("chatgpt_model_id", "").orEmpty()
        )
    }
    var modelPickerOpen by remember { mutableStateOf(false) }
    var instruction by rememberSaveable { mutableStateOf("") }
    var proposal by remember { mutableStateOf<AiRevision?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(account?.subject) {
        models = emptyList()
        if (account != null) {
            isLoadingModels = true
            runCatching { provider.listModels(context) }
                .onSuccess { available ->
                    models = available
                    val chosen = available.firstOrNull { it.id == selectedModelId } ?: available.firstOrNull()
                    if (chosen != null) {
                        selectedModelId = chosen.id
                        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                            .edit().putString("chatgpt_model_id", chosen.id).apply()
                    }
                    errorMessage = null
                }
                .onFailure { error ->
                    errorMessage = error.message ?: "Impossible de charger les modèles ChatGPT."
                }
            isLoadingModels = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AiCanvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onClose, shape = RoundedCornerShape(11.dp)) {
                Text("← Éditeur", color = AiText)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("ASSISTANT IA", color = AiAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text(fileName, color = AiText, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Surface(color = AiPanel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(provider.displayName, color = AiText, fontWeight = FontWeight.Bold)
                        Text("Connexion OAuth · sans clé API", color = AiMuted, fontSize = 12.sp)
                    }
                    if (account == null) {
                        Button(
                            onClick = {
                                val currentActivity = activity
                                if (currentActivity == null) {
                                    errorMessage = "Impossible d'ouvrir le navigateur depuis ce contexte Android."
                                } else {
                                    scope.launch {
                                        isConnecting = true
                                        errorMessage = null
                                        infoMessage = null
                                        try {
                                            account = ChatGptPlanClient.connect(currentActivity)
                                            infoMessage = "Compte ChatGPT connecté."
                                        } catch (error: Exception) {
                                            errorMessage = error.message ?: "La connexion ChatGPT a échoué."
                                        } finally {
                                            isConnecting = false
                                        }
                                    }
                                }
                            },
                            enabled = !isConnecting,
                            shape = RoundedCornerShape(11.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AiAccent, contentColor = AiCanvas)
                        ) {
                            if (isConnecting) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AiCanvas)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(if (isConnecting) "Connexion…" else "Continuer avec ChatGPT", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val revoked = provider.disconnect(context)
                                    account = null
                                    models = emptyList()
                                    proposal = null
                                    infoMessage = if (revoked) {
                                        "Compte déconnecté et session distante révoquée."
                                    } else {
                                        "Déconnexion locale effectuée. Vérifie la révocation dans les paramètres ChatGPT si nécessaire."
                                    }
                                }
                            }
                        ) { Text("Déconnecter", color = AiMuted) }
                    }
                }
                if (account != null) {
                    Text(
                        account?.email?.takeIf { it.isNotBlank() } ?: "Compte ChatGPT connecté",
                        color = AiAccent,
                        fontSize = 12.sp
                    )
                    Divider(color = AiBorder)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Modèle", color = AiMuted, fontSize = 11.sp)
                            Text(
                                models.firstOrNull { it.id == selectedModelId }?.displayName
                                    ?: selectedModelId.ifBlank { "Chargement…" },
                                color = AiText,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        OutlinedButton(
                            onClick = { modelPickerOpen = true },
                            enabled = models.isNotEmpty() && !isLoadingModels,
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("Choisir", color = AiText) }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isLoadingModels = true
                                    runCatching { provider.listModels(context) }
                                        .onSuccess { models = it; errorMessage = null }
                                        .onFailure { errorMessage = it.message ?: "Impossible d'actualiser les modèles." }
                                    isLoadingModels = false
                                }
                            },
                            enabled = !isLoadingModels,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isLoadingModels) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            else Text("↻", color = AiText)
                        }
                    }
                } else {
                    Text(
                        "Autorise séparément l'utilisation de ton forfait ChatGPT pendant la connexion. L'application n'accède pas à tes conversations ChatGPT.",
                        color = AiMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        Surface(color = AiPanel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("QUE VEUX-TU MODIFIER ?", color = AiAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    enabled = !isGenerating,
                    label = { Text("Décris le changement demandé") },
                    placeholder = { Text("Ex. : ajoute une validation et explique les erreurs à l'utilisateur") }
                )
                Text(
                    "Seul le fichier ouvert et ta consigne seront envoyés à ChatGPT pour cette demande. Limite actuelle : 64 Kio / 1 800 lignes.",
                    color = AiMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
                Button(
                    onClick = {
                        proposal = null
                        scope.launch {
                            isGenerating = true
                            errorMessage = null
                            infoMessage = null
                            try {
                                proposal = provider.proposeFileRevision(
                                    context = context,
                                    fileName = fileName,
                                    currentContent = currentContent,
                                    instruction = instruction,
                                    modelId = selectedModelId
                                )
                            } catch (error: Exception) {
                                errorMessage = error.message ?: "La génération de la proposition a échoué."
                            } finally {
                                isGenerating = false
                            }
                        }
                    },
                    enabled = account != null && models.isNotEmpty() && instruction.isNotBlank() &&
                        !isGenerating && !isLoadingModels,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AiAccent, contentColor = AiCanvas)
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp, color = AiCanvas)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isGenerating) "Génération de la proposition…" else "Proposer une modification", fontWeight = FontWeight.Bold)
                }
            }
        }

        proposal?.let { revision ->
            Surface(color = AiPanel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("APERÇU DES CHANGEMENTS", color = AiAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                    Text(revision.summary, color = AiText, fontSize = 13.sp, lineHeight = 18.sp)
                    val diff = remember(currentContent, revision.content) {
                        FileRevisionDiff.preview(currentContent, revision.content)
                    }
                    if (diff.isEmpty()) {
                        Text("Aucune différence détectée. Le fichier n'a pas été modifié.", color = AiMuted, fontSize = 12.sp)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("+ lignes ajoutées", color = AiAccent, fontSize = 11.sp)
                            Text("− lignes supprimées", color = AiError, fontSize = 11.sp)
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            diff.forEach { line ->
                                val (prefix, color, background) = when (line.kind) {
                                    DiffKind.ADDED -> Triple("+ ", AiAccent, AiAdded)
                                    DiffKind.REMOVED -> Triple("− ", AiError, AiRemoved)
                                    DiffKind.OMITTED -> Triple("  ", AiMuted, AiRaised)
                                    DiffKind.CONTEXT -> Triple("  ", AiMuted, Color.Transparent)
                                }
                                Text(
                                    prefix + line.text.ifEmpty { " " },
                                    color = color,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(background, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                    Divider(color = AiBorder)
                    Text(
                        "Accepter place le contenu proposé dans l'éditeur, sans écrire sur disque. Tu devras ensuite appuyer sur Enregistrer. Refuser ne change rien.",
                        color = AiMuted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { proposal = null },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(11.dp)
                        ) { Text("Refuser", color = AiText) }
                        Button(
                            onClick = { onApplyProposal(revision.content) },
                            enabled = revision.content != currentContent,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(11.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AiAccent, contentColor = AiCanvas)
                        ) { Text("Accepter", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        if (errorMessage != null) {
            Surface(color = Color(0xFF351F29), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(errorMessage.orEmpty(), color = AiError, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            }
        } else if (infoMessage != null) {
            Surface(color = Color(0xFF1D3025), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(infoMessage.orEmpty(), color = AiAccent, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
    }

    if (modelPickerOpen) {
        AlertDialog(
            onDismissRequest = { modelPickerOpen = false },
            title = { Text("Choisir un modèle ChatGPT", color = AiText) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    models.forEach { model ->
                        TextButton(
                            onClick = {
                                selectedModelId = model.id
                                context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                                    .edit().putString("chatgpt_model_id", model.id).apply()
                                modelPickerOpen = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    model.displayName,
                                    color = if (model.id == selectedModelId) AiAccent else AiText,
                                    fontWeight = if (model.id == selectedModelId) FontWeight.Bold else FontWeight.Medium
                                )
                                if (model.displayName != model.id) Text(model.id, color = AiMuted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { modelPickerOpen = false }) { Text("Fermer", color = AiAccent) }
            },
            containerColor = AiPanel
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
