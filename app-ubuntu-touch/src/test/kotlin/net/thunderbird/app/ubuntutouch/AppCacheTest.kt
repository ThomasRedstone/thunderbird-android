package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isTrue
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `prepare cache creates missing parents and tolerates an existing directory`() {
        val directory = temporaryFolder.root.toPath().resolve("cache with spaces/app/tmp")

        prepareCache(directory)
        prepareCache(directory)

        assertThat(Files.isDirectory(directory)).isTrue()
    }
}
