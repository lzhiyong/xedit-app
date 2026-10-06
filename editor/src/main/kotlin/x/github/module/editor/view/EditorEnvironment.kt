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

/**
 * The single interface EditorView implements on behalf of all its collaborators.
 *
 * Each collaborator still declares its own narrow `Host` (so its constructor type shows
 * exactly what it depends on, and a test can fake just that much), and every one of them
 * is listed here. Adding a new collaborator means adding its Host to this list and
 * nothing else in EditorView's class header.
 */
interface EditorEnvironment :
    EditorGeometry,
    EditorTextSource,
    TextLayout.Host,
    TextRenderer.Host,
    ScrollController.Host,
    ScrollbarController.Host,
    SelectionController.Host,
    GestureController.Host,
    EditHistory.Host
