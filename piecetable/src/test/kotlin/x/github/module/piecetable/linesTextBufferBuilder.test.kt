/*
 * Copyright © 2022 Github Lzhiyong
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
 
package x.github.module.piecetable

import x.github.module.piecetable.common.*
import kotlin.test.*

class PieceTextBufferBuilderTest {

    internal fun testTextBufferFactory(
        text: String,
        eol: String,
        mightContainNonBasicASCII: Boolean,
        mightContainRTL: Boolean
    ) {
        val pieceBuilder = PieceTreeTextBufferBuilder()
        pieceBuilder.acceptChunk(text)
        val textBuffer = pieceBuilder.build(EndOfLine.LF)

        assertEquals(textBuffer.mightContainNonBasicASCII(), mightContainNonBasicASCII)
        assertEquals(textBuffer.mightContainRTL(), mightContainRTL)
        assertEquals(textBuffer.getEOL(), eol)
    }

    // #region ModelBuilder

    @Test
    fun `t1`() {
        testTextBufferFactory("", "\n", false, false)
    }

    @Test
    fun `t2`() {
        testTextBufferFactory("Hello world", "\n", false, false)
    }

    @Test
    fun `t3`() {
        testTextBufferFactory("Hello world\nHow are you?", "\n", false, false)
    }

    @Test
    fun `t4`() {
        testTextBufferFactory(
            "Hello world\nHow are you?\nIs everything good today?\nDo you enjoy the weather?",
            "\n",
            false,
            false
        )
    }

    @Test
    fun `carriage return detection CRLF 1`() {
        testTextBufferFactory(
            "Hello world\r\nHow are you?\nIs everything good today?\nDo you enjoy the weather?",
            "\n",
            false,
            false
        )
    }

    @Test
    fun `carriage return detection CRLF 2`() {
        testTextBufferFactory(
            "Hello world\r\nHow are you?\r\nIs everything good today?\nDo you enjoy the weather?",
            "\r\n",
            false,
            false
        )
    }

    @Test
    fun `carriage return detection CRLF 3`() {
        testTextBufferFactory(
            "Hello world\r\nHow are you?\r\nIs everything good today?\r\nDo you enjoy the weather?",
            "\r\n",
            false,
            false
        )
    }

    @Test
    fun `BOM handling 1`() {
        testTextBufferFactory(Strings.UTF8_BOM_CHARACTER + "Hello world!", "\n", false, false)
    }

    @Test
    fun `BOM handling 2`() {
        testTextBufferFactory(Strings.UTF8_BOM_CHARACTER + "Hello 😍 world!", "\n", true, false)
    }

    @Test
    fun `RTL handling 2`() {
        testTextBufferFactory("Hello world!זוהי עובדה מבוססת שדעתו", "\n", true, true)
    }

    @Test
    fun `RTL handling 3`() {
        testTextBufferFactory("Hello world!זוהי \nעובדה מבוססת שדעתו", "\n", true, true)
    }

    @Test
    fun `ASCII handling 1`() {
        testTextBufferFactory("Hello world!!\nHow do you do?", "\n", false, false)
    }

    @Test
    fun `ASCII handling 2`() {
        testTextBufferFactory("Hello world!!\nHow do you do?Züricha📚📚b", "\n", true, false)
    }

    // #endregion
}
