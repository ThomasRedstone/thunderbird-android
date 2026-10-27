package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfinedOAuthIdentityProbeTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `native identity entry point refuses an unconfined host before creating fixture files`() {
        val previous = System.getProperty("user.home")
        try {
            System.setProperty("user.home", directory.root.absolutePath)
            val testSubject = runCatching {
                ConfinedOAuthIdentityProbe.main(
                    arrayOf(
                        "mail-demo.thomasredstone_mail-demo_0.1.25",
                        "cleanup",
                        directory.root.toPath().resolve("outside").toString(),
                    ),
                )
            }
            assertThat(testSubject.exceptionOrNull()?.message).isEqualTo("Required application confinement unavailable")
            val migration = runCatching {
                ConfinedProductionMigrationProbe.main(
                    arrayOf(
                        "mail-demo.thomasredstone_mail-demo_0.1.25",
                        "a1948b0768d12899c18ae8ff04852e29e769ab44",
                    ),
                )
            }
            assertThat(migration.exceptionOrNull()?.message).isEqualTo("Required application confinement unavailable")
            assertThat(directory.root.listFiles()!!.toList()).isEqualTo(emptyList())
        } finally {
            System.setProperty("user.home", previous)
        }
    }

    @Test
    fun `fixture recovery rejects other directories and symlinks without touching their data`() {
        val parent = Files.createDirectory(directory.root.toPath().resolve("probes"))
        val allowed = Files.createDirectory(parent.resolve("oauth-identity-runtime-owned"))
        val outside = Files.createDirectory(directory.root.toPath().resolve("oauth-identity-runtime-outside"))
        Files.writeString(outside.resolve("keep"), "retained")
        val alias = Files.createSymbolicLink(parent.resolve("oauth-identity-runtime-alias"), outside)
        assertThat(requireIdentityFixtureDirectory(parent, allowed)).isEqualTo(allowed)
        listOf(outside, alias, parent).forEach {
            assertThat(runCatching { requireIdentityFixtureDirectory(parent, it) }.isFailure).isTrue()
        }
        assertThat(Files.readString(outside.resolve("keep"))).isEqualTo("retained")
    }
}
