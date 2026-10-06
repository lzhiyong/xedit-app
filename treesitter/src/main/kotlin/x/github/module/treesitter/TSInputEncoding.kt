/*
 * Copyright © 2023 Github Lzhiyong
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

package x.github.module.treesitter

import java.nio.charset.Charset

/**
 * The encoding of the input text.
 *
 * @since 0.25.0
 */
enum class TSInputEncoding(val charset: Charset) {
    UTF_8(Charsets.UTF_8),
    UTF_16LE(Charsets.UTF_16LE),
    UTF_16BE(Charsets.UTF_16BE)
}