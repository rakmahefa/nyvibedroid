package com.nyvibe.droid

import android.content.Context

internal data class AiModel(
    val id: String,
    val displayName: String
)

internal data class AiRevision(
    val content: String,
    val summary: String
)

/**
 * Provider boundary for the Workspace assistant. Implementations return a proposed replacement;
 * they must not write to the active workspace or perform Git/build actions.
 */
internal interface AiProvider {
    val id: String
    val displayName: String

    suspend fun listModels(context: Context): List<AiModel>

    suspend fun disconnect(context: Context): Boolean

    suspend fun proposeFileRevision(
        context: Context,
        fileName: String,
        currentContent: String,
        instruction: String,
        modelId: String
    ): AiRevision
}

internal object ChatGptCloudProvider : AiProvider {
    override val id: String = "chatgpt-cloud"
    override val displayName: String = "ChatGPT Cloud"

    override suspend fun listModels(context: Context): List<AiModel> =
        ChatGptPlanClient.listModels(context)

    override suspend fun disconnect(context: Context): Boolean =
        ChatGptPlanClient.disconnect(context)

    override suspend fun proposeFileRevision(
        context: Context,
        fileName: String,
        currentContent: String,
        instruction: String,
        modelId: String
    ): AiRevision = ChatGptPlanClient.proposeFileRevision(
        context = context,
        fileName = fileName,
        currentContent = currentContent,
        instruction = instruction,
        modelId = modelId
    )
}
