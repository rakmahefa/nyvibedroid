package com.nyvibe.droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRevisionDiffTest {
    @Test
    fun parsesJsonRevisionAndSummary() {
        val revision = FileRevisionDiff.parseRevisionResponse(
            """{"content":"fun main() = Unit\n","summary":"Add a main function"}"""
        )

        assertEquals("fun main() = Unit\n", revision.content)
        assertEquals("Add a main function", revision.summary)
    }

    @Test
    fun rejectsMalformedOrMissingRevisionContent() {
        assertThrows(java.io.IOException::class.java) {
            FileRevisionDiff.parseRevisionResponse("not json")
        }
        assertThrows(java.io.IOException::class.java) {
            FileRevisionDiff.parseRevisionResponse("""{"summary":"no content"}""")
        }
    }

    @Test
    fun previewShowsRemovedAndAddedLinesAndBoundsContext() {
        val diff = FileRevisionDiff.preview(
            "one\ntwo\nold value\nfour\nfive\nsix",
            "one\ntwo\nnew value\nfour\nfive\nsix"
        )

        assertTrue(diff.any { it.kind == DiffKind.REMOVED && it.text == "old value" })
        assertTrue(diff.any { it.kind == DiffKind.ADDED && it.text == "new value" })
        assertFalse(diff.any { it.kind == DiffKind.REMOVED && it.text == "one" })
    }

    @Test
    fun identicalContentHasNoDiff() {
        assertTrue(FileRevisionDiff.preview("same\ncontent", "same\ncontent").isEmpty())
    }
}
