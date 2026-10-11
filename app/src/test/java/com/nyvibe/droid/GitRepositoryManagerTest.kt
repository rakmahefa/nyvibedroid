package com.nyvibe.droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitRepositoryManagerTest {
    @Test
    fun normalizesOwnerAndRepositoryToHttpsCloneUrl() {
        assertEquals(
            "https://github.com/rakmahefa/nyvibedroid.git",
            GitRepositoryManager.normalizeRemoteUrl("rakmahefa/nyvibedroid")
        )
        assertEquals(
            "https://github.com/eclipse-jgit/jgit.git",
            GitRepositoryManager.normalizeRemoteUrl("https://github.com/eclipse-jgit/jgit.git")
        )
        assertEquals(
            "https://github.com/eclipse-jgit/jgit.git",
            GitRepositoryManager.normalizeRemoteUrl("https://github.com/eclipse-jgit/jgit")
        )
    }

    @Test
    fun rejectsNonGitHubHostsAndInsecureProtocols() {
        assertThrows(IllegalArgumentException::class.java) {
            GitRepositoryManager.normalizeRemoteUrl("https://example.com/owner/repo")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GitRepositoryManager.normalizeRemoteUrl("http://github.com/owner/repo")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GitRepositoryManager.normalizeRemoteUrl("git@github.com:owner/repo.git")
        }
    }

    @Test
    fun rejectsCredentialBearingAndMalformedUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            GitRepositoryManager.normalizeRemoteUrl("https://user:secret@github.com/owner/repo")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GitRepositoryManager.normalizeRemoteUrl("https://github.com/owner/repo?token=secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GitRepositoryManager.normalizeRemoteUrl("owner/repo/extra")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GitRepositoryManager.normalizeRemoteUrl(" ")
        }
    }
}
