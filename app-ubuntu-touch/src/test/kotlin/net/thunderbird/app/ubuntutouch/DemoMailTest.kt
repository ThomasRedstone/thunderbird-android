package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test

class DemoMailTest {
    @Test
    fun `bundled mailbox includes nested folders and decoded plain text`() {
        val testSubject = DemoMailRepository()

        val folders = testSubject.load()
        val inbox = folders.single { it.id == "inbox" }
        val welcome = inbox.messages.single { it.id == "01-intro" }

        assertThat(inbox.messages.size).isEqualTo(7)
        assertThat(folders.any { it.id == "nested/nested_level_1/nested_level_2" }).isTrue()
        assertThat(welcome.body.contains("Congratulations")).isTrue()
        assertThat(welcome.subject).isEqualTo("Welcome to Thunderbird for Android")
        assertThat(testSubject.load()).isEqualTo(folders)
    }

    @Test
    fun `navigation returns through folder and preserves its filter`() {
        val folder = DemoMailRepository().load().first { it.messages.isNotEmpty() }
        val testSubject = DemoMailViewModel(listOf(folder))

        testSubject.event(DemoMailEvent.OpenFolder(folder))
        testSubject.event(DemoMailEvent.Search("Welcome"))
        testSubject.event(DemoMailEvent.OpenMessage(folder.messages.first()))
        assertThat(testSubject.state.value.message?.body.orEmpty()).isNotEmpty()
        testSubject.event(DemoMailEvent.Back)

        assertThat(testSubject.state.value.message).isNull()
        assertThat(testSubject.state.value.folder).isEqualTo(folder)
        assertThat(testSubject.state.value.query).isEqualTo("Welcome")
        testSubject.event(DemoMailEvent.Back)
        assertThat(testSubject.state.value.folder).isNull()
        assertThat(testSubject.state.value.query).isEqualTo("")
    }
}
