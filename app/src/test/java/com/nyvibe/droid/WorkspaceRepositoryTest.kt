package com.nyvibe.droid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRepositoryTest {
    @Test
    fun recognizesCommonSourceAndProjectFiles() {
        assertTrue(WorkspaceRepository.isTextFile("MainActivity.kt", null))
        assertTrue(WorkspaceRepository.isTextFile("build.gradle.kts", null))
        assertTrue(WorkspaceRepository.isTextFile("README.md", null))
        assertTrue(WorkspaceRepository.isTextFile(".gitignore", null))
        assertTrue(WorkspaceRepository.isTextFile("manifest", "text/plain"))
    }

    @Test
    fun rejectsObviousBinaryFileTypes() {
        assertFalse(WorkspaceRepository.isTextFile("icon.png", "image/png"))
        assertFalse(WorkspaceRepository.isTextFile("app.apk", "application/vnd.android.package-archive"))
        assertFalse(WorkspaceRepository.isTextFile("archive.zip", "application/zip"))
    }
}
