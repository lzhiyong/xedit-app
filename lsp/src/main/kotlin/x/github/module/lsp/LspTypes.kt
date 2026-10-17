/*
 * Copyright © 2026 Github Lzhiyong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package x.github.module.lsp

/**
 * A place in a document as the Language Server Protocol counts: [line] and
 * [character] from 0, characters in UTF-16 code units, which are the chars of a
 * Kotlin String (the client negotiates that encoding, see LspClient).
 *
 * An editor that counts from 1, like the piece table (line and column of a
 * `Range`), converts with [fromOneBased] and [toOneBased].
 */
data class TextPosition(val line: Int, val character: Int) : Comparable<TextPosition> {

    init {
        require(line >= 0 && character >= 0) { "negative position: $line:$character" }
    }

    override fun compareTo(other: TextPosition): Int =
        if (line != other.line) line.compareTo(other.line) else character.compareTo(other.character)

    /** This position counted from 1: (line, column). */
    fun toOneBased(): Pair<Int, Int> = (line + 1) to (character + 1)

    override fun toString() = "$line:$character"

    companion object {
        /** The position of [line] and [column] counted from 1. */
        fun fromOneBased(line: Int, column: Int) = TextPosition(line - 1, column - 1)
    }
}

/** The text from [start] (inclusive) to [end] (exclusive). */
data class TextRange(val start: TextPosition, val end: TextPosition) {

    init {
        require(start <= end) { "the range ends before it starts: $start-$end" }
    }

    val isEmpty: Boolean get() = start == end

    operator fun contains(position: TextPosition): Boolean = position >= start && position <= end

    override fun toString() = "[$start-$end)"

    companion object {
        /** An empty range at [position]: where something is inserted. */
        fun at(position: TextPosition) = TextRange(position, position)
    }
}

/** Replace [range] by [newText]. */
data class TextEdit(val range: TextRange, val newText: String)

/**
 * A change of a document, for the server: [text] replaces [range], given in the
 * document as it is before this change (and after the changes before it in the
 * same call). A null range replaces the whole document.
 */
data class TextChange(val range: TextRange?, val text: String) {
    companion object {
        /** The whole document is now [text]. */
        fun full(text: String) = TextChange(null, text)
    }
}

// #region diagnostics

enum class DiagnosticSeverity { ERROR, WARNING, INFORMATION, HINT }

enum class DiagnosticTagKind {
    /** code that is not used: shown faded */
    UNNECESSARY,
    /** code that is deprecated: shown struck through */
    DEPRECATED
}

/**
 * A problem the server found in a document.
 *
 * @property source what found it (`clang`, `clang-tidy`, `pyright`, ...)
 * @property code its code (`-Wunused-variable`, `E501`, ...)
 */
data class LspDiagnostic(
    val range: TextRange,
    val severity: DiagnosticSeverity,
    val message: String,
    val source: String? = null,
    val code: String? = null,
    val tags: Set<DiagnosticTagKind> = emptySet()
)

// #endregion

// #region completion

/** The kinds of completion items, in the order of the protocol (1 = TEXT). */
enum class CompletionKind {
    TEXT, METHOD, FUNCTION, CONSTRUCTOR, FIELD, VARIABLE, CLASS, INTERFACE, MODULE,
    PROPERTY, UNIT, VALUE, ENUM, KEYWORD, SNIPPET, COLOR, FILE, REFERENCE, FOLDER,
    ENUM_MEMBER, CONSTANT, STRUCT, EVENT, OPERATOR, TYPE_PARAMETER;

    companion object {
        /** The kind of the protocol's number [value]; null for one this does not know. */
        fun of(value: Int): CompletionKind? = entries.getOrNull(value - 1)
    }
}

/**
 * A completion, ready to be shown and applied.
 *
 * @property insertText what to insert: [edit] says where, or else the word at the
 *   cursor is replaced (see CompletionRanker.prefixAt). A snippet ([isSnippet]) is
 *   expanded with SnippetParser first.
 * @property labelDetail what follows the label in the list: the signature, say
 * @property documentation plain text or Markdown ([isMarkdown]); may come only with
 *   LspClient.resolve
 * @property sortText the order the server wants (falls back to the label)
 * @property filterText what the typed text is matched against (falls back to the label)
 */
class LspCompletionItem(
    val label: String,
    val kind: CompletionKind? = null,
    val detail: String? = null,
    val labelDetail: String? = null,
    val documentation: String? = null,
    val isMarkdown: Boolean = false,
    val insertText: String = label,
    val isSnippet: Boolean = false,
    val edit: TextEdit? = null,
    val additionalEdits: List<TextEdit> = emptyList(),
    val sortText: String = label,
    val filterText: String = label,
    val deprecated: Boolean = false,
    val commitCharacters: List<String> = emptyList(),
    /** the item as the server sent it: what LspClient.resolve sends back */
    internal val source: org.eclipse.lsp4j.CompletionItem? = null
) {
    override fun toString() = "LspCompletionItem($label, $kind)"
}

/**
 * The completions at a place. [isIncomplete]: the server has more; ask again as
 * the user types, instead of only filtering these.
 */
data class CompletionResult(val items: List<LspCompletionItem>, val isIncomplete: Boolean) {
    companion object {
        val EMPTY = CompletionResult(emptyList(), false)
    }
}

// #endregion

/** What the server says about the code at a place: plain text or Markdown. */
data class HoverInfo(val text: String, val isMarkdown: Boolean, val range: TextRange? = null)

/** The kinds of messages a server sends: errors down to its log. */
enum class MessageKind { ERROR, WARNING, INFO, LOG }

/** How the server wants to hear of changes of a document. */
enum class TextSyncKind {
    /** not at all */
    NONE,
    /** the whole text after each change */
    FULL,
    /** the changes themselves */
    INCREMENTAL
}

/** A request failed: the server answered with an error, or did not answer in time. */
class LspException(message: String, cause: Throwable? = null) : Exception(message, cause)
