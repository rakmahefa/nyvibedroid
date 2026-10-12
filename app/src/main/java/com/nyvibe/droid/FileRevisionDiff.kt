package com.nyvibe.droid

import org.json.JSONObject
import java.io.IOException

internal enum class DiffKind {
    CONTEXT,
    REMOVED,
    ADDED,
    OMITTED
}

internal data class DiffLine(
    val kind: DiffKind,
    val text: String
)

internal object FileRevisionDiff {
    fun parseRevisionResponse(response: String): AiRevision {
        val jsonText = response.trim()
        val json = try {
            JSONObject(jsonText)
        } catch (error: Exception) {
            throw IOException("La réponse IA n'est pas au format JSON attendu. Aucune modification n'a été appliquée.", error)
        }
        if (!json.has("content")) {
            throw IOException("La réponse IA ne contient pas le fichier proposé. Aucune modification n'a été appliquée.")
        }
        val content = json.getString("content")
        if (content.toByteArray(Charsets.UTF_8).size > MAX_EDITABLE_BYTES) {
            throw IOException("Le fichier proposé dépasse la limite de sauvegarde de 1 Mio.")
        }
        return AiRevision(
            content = content,
            summary = json.optString("summary").trim().take(MAX_SUMMARY_CHARS)
                .ifBlank { "Proposition de modification prête à examiner." }
        )
    }

    /**
     * A bounded single-hunk preview. It deliberately avoids quadratic diff algorithms on a phone.
     * Every changed line is shown; unchanged content outside the hunk is represented by two context
     * lines and an omission marker.
     */
    fun preview(before: String, after: String, contextLines: Int = 2): List<DiffLine> {
        if (before == after) return emptyList()
        val oldLines = before.lines()
        val newLines = after.lines()
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) {
            prefix++
        }
        var suffix = 0
        while (
            suffix < oldLines.size - prefix &&
            suffix < newLines.size - prefix &&
            oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]
        ) {
            suffix++
        }

        val result = mutableListOf<DiffLine>()
        val leftContextStart = (prefix - contextLines).coerceAtLeast(0)
        if (leftContextStart > 0) result += DiffLine(DiffKind.OMITTED, "… lignes inchangées omises …")
        for (index in leftContextStart until prefix) {
            result += DiffLine(DiffKind.CONTEXT, oldLines[index])
        }
        for (index in prefix until oldLines.size - suffix) {
            result += DiffLine(DiffKind.REMOVED, oldLines[index])
        }
        for (index in prefix until newLines.size - suffix) {
            result += DiffLine(DiffKind.ADDED, newLines[index])
        }
        val rightContextEnd = (suffix - contextLines).coerceAtLeast(0)
        for (offset in suffix - 1 downTo rightContextEnd) {
            result += DiffLine(DiffKind.CONTEXT, oldLines[oldLines.size - 1 - offset])
        }
        if (rightContextEnd > 0) result += DiffLine(DiffKind.OMITTED, "… lignes inchangées omises …")
        return result
    }

    const val MAX_EDITABLE_BYTES = 1024 * 1024
    const val MAX_REQUEST_BYTES = 64 * 1024
    const val MAX_REQUEST_LINES = 1800
    private const val MAX_SUMMARY_CHARS = 500
}
