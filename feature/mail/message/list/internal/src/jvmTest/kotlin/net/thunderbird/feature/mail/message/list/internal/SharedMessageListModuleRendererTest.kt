package net.thunderbird.feature.mail.message.list.internal

import assertk.assertThat
import assertk.assertions.isInstanceOf
import kotlin.test.Test
import net.thunderbird.feature.mail.message.list.internal.ui.MessageListScreenRenderer
import net.thunderbird.feature.mail.message.list.ui.MessageListContract
import org.koin.dsl.koinApplication

class SharedMessageListModuleRendererTest {
    @Test
    fun `sharedMessageListModule should provide the canonical screen renderer`() {
        // Arrange
        val koin = koinApplication { modules(sharedMessageListModule) }.koin

        // Act
        val testSubject = koin.get<MessageListContract.MessageListScreenRenderer>()

        // Assert
        assertThat(testSubject).isInstanceOf<MessageListScreenRenderer>()
    }
}
