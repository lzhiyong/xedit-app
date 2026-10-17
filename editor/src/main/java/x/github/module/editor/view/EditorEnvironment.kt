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

package x.github.module.editor.view

import x.github.module.editor.EditorGeometry
import x.github.module.editor.input.EditorClipboard
import x.github.module.editor.input.EditorInputConnection
import x.github.module.editor.input.GestureController
import x.github.module.editor.input.InputMethodBridge
import x.github.module.editor.input.KeyboardController
import x.github.module.editor.input.ScrollController
import x.github.module.editor.input.ScrollbarController
import x.github.module.editor.input.SelectionController
import x.github.module.editor.layout.LayoutCoordinator
import x.github.module.editor.layout.TextMetrics
import x.github.module.editor.model.EditHistory
import x.github.module.editor.model.EditorDocument
import x.github.module.editor.render.Gutter
import x.github.module.editor.render.TextRenderer

/**
 * Everything the collaborators of EditorView ask of it, in one interface.
 *
 * Only the collaborators that need something from the *view* (its size and scroll
 * position, its overlay, its message queue, or a decision only the view can carry out)
 * declare a `Host`, and every one of them is listed here.
 *
 * EditorView does not implement this interface itself. A private adapter inside it
 * does (EditorView.Environment), passing each call on to a member of the view. That
 * keeps what is only meant for one collaborator out of the view's public methods:
 * an interface member is public, a member the adapter forwards to can be private.
 *
 * Adding a collaborator that needs the view means adding its Host to this list and
 * its members to the adapter.
 *
 * What is not view business does not come through here at all: text is read from
 * [EditorDocument] and measured with [TextMetrics], and collaborators are handed those
 * two objects directly.
 */
interface EditorEnvironment :
    EditorGeometry,
    TextRenderer.Host,
    ScrollController.Host,
    ScrollbarController.Host,
    SelectionController.Host,
    GestureController.Host,
    EditorInputConnection.Host,
    InputMethodBridge.Host,
    KeyboardController.Host,
    EditorClipboard.Host,
    EditHistory.Host,
    LayoutCoordinator.Host,
    Gutter.Host
