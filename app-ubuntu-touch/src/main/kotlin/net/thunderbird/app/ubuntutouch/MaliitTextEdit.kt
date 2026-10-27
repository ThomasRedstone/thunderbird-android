package net.thunderbird.app.ubuntutouch

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal sealed interface MaliitTextEdit {
    data class Preedit(val text: String) : MaliitTextEdit
    data class Commit(val text: String) : MaliitTextEdit
    data object Backspace : MaliitTextEdit
    data object Enter : MaliitTextEdit
}

internal fun applyMaliitEdit(value: TextFieldValue, edit: MaliitTextEdit): TextFieldValue {
    val range = value.composition ?: value.selection
    return when (edit) {
        is MaliitTextEdit.Preedit, is MaliitTextEdit.Commit -> {
            val replacement = when (edit) {
                is MaliitTextEdit.Preedit -> edit.text
                is MaliitTextEdit.Commit -> edit.text
            }
            val end = range.min + replacement.length
            TextFieldValue(
                text = value.text.replaceRange(range.min, range.max, replacement),
                selection = TextRange(end),
                composition = if (edit is MaliitTextEdit.Preedit && replacement.isNotEmpty()) {
                    TextRange(range.min, end)
                } else {
                    null
                },
            )
        }

        MaliitTextEdit.Backspace -> {
            val selection = value.selection
            val start = if (selection.collapsed && selection.start > 0) {
                value.text.offsetByCodePoints(selection.start, -1)
            } else {
                selection.min
            }
            val end = selection.max
            val composition = value.composition?.let {
                if (start > it.min) TextRange(it.min, start) else null
            }
            TextFieldValue(value.text.removeRange(start, end), TextRange(start), composition)
        }

        MaliitTextEdit.Enter -> value
    }
}
