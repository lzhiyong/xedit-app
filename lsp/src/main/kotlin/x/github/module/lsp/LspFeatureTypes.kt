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

// The types of the features beyond completion, diagnostics and hover, in plain Kotlin
// (see LspFeatureConverters for lsp4j): what the editor works with.

/** A place in a document: its [uri] and a [range] in it. */
data class LspLocation(val uri: String, val range: TextRange)

/** One parameter of a signature: its [label] in the signature's label ([start] to [end]). */
data class ParameterInfo(val start: Int, val end: Int, val documentation: String? = null)

/** One signature of a function: its [label], and where each parameter is in it. */
data class SignatureInfo(
    val label: String,
    val documentation: String?,
    val parameters: List<ParameterInfo>,
    /** The parameter being typed, if the signature says (it wins over SignatureHelpInfo's). */
    val activeParameter: Int?,
)

/** The signatures of the call the cursor is in, the one that fits best, and the parameter typed. */
data class SignatureHelpInfo(val signatures: List<SignatureInfo>, val activeSignature: Int, val activeParameter: Int) {
    /** The signature to show, and the parameter of it that is typed (-1 if none). */
    val active: Pair<SignatureInfo, Int>?
        get() {
            val signature = signatures.getOrNull(activeSignature) ?: signatures.firstOrNull() ?: return null
            return signature to (signature.activeParameter ?: activeParameter)
        }
}

/** Edits to documents, by their URI, as a rename or a code action makes them. */
data class LspWorkspaceEdit(val changes: Map<String, List<TextEdit>>) {
    val isEmpty: Boolean get() = changes.values.all { it.isEmpty() }
}

/** A command of the server (workspace/executeCommand). */
data class LspCommand(val title: String, val command: String, val arguments: List<Any?>)

/** What a server offers to do at a place: an [edit], a [command] to run after it, or both. */
data class LspCodeAction(
    val title: String,
    val kind: String?,
    val edit: LspWorkspaceEdit?,
    val command: LspCommand?,
    val isPreferred: Boolean = false,
)

/** The kinds of symbols of the protocol (SymbolKind), in its order. */
enum class SymbolKindInfo {
    FILE, MODULE, NAMESPACE, PACKAGE, CLASS, METHOD, PROPERTY, FIELD, CONSTRUCTOR, ENUM, INTERFACE,
    FUNCTION, VARIABLE, CONSTANT, STRING, NUMBER, BOOLEAN, ARRAY, OBJECT, KEY, NULL, ENUM_MEMBER,
    STRUCT, EVENT, OPERATOR, TYPE_PARAMETER;

    companion object {
        fun of(value: Int): SymbolKindInfo? = entries.getOrNull(value - 1)
    }
}

/** A symbol of a document (its outline): where it is, where its name is, what is in it. */
data class LspSymbol(
    val name: String,
    val detail: String?,
    val kind: SymbolKindInfo?,
    val range: TextRange,
    val selectionRange: TextRange,
    val children: List<LspSymbol> = emptyList(),
)
