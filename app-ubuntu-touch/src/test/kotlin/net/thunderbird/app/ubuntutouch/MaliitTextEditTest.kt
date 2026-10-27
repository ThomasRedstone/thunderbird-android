package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test

class MaliitTextEditTest {
    @Test
    fun `incremental preedit replaces the composing word instead of duplicating letters`() {
        var testSubject = TextFieldValue()
        listOf("t", "th", "thi", "this").forEach {
            testSubject = applyMaliitEdit(testSubject, MaliitTextEdit.Preedit(it))
        }
        testSubject = applyMaliitEdit(testSubject, MaliitTextEdit.Commit("this"))
        testSubject = applyMaliitEdit(testSubject, MaliitTextEdit.Commit(" "))

        assertThat(testSubject.text).isEqualTo("this ")
        assertThat(testSubject.composition).isNull()
    }

    @Test
    fun `preedit and commit replace the active selection and preserve surrounding text`() {
        var testSubject = TextFieldValue("abcd", TextRange(1, 3))
        testSubject = applyMaliitEdit(testSubject, MaliitTextEdit.Preedit("x"))
        testSubject = applyMaliitEdit(testSubject, MaliitTextEdit.Preedit("xy"))
        assertThat(testSubject.text).isEqualTo("axyd")
        testSubject = applyMaliitEdit(testSubject, MaliitTextEdit.Commit("z"))

        assertThat(testSubject.text).isEqualTo("azd")
        assertThat(testSubject.selection).isEqualTo(TextRange(2))
        assertThat(testSubject.composition).isNull()
    }

    @Test
    fun `backspace removes a complete unicode character and handles empty text`() {
        var testSubject = TextFieldValue("A😀", TextRange(3))
        testSubject = applyMaliitEdit(testSubject, MaliitTextEdit.Backspace)
        assertThat(testSubject.text).isEqualTo("A")
        testSubject = applyMaliitEdit(testSubject, MaliitTextEdit.Backspace)
        testSubject = applyMaliitEdit(testSubject, MaliitTextEdit.Backspace)
        assertThat(testSubject.text).isEqualTo("")
    }
}
