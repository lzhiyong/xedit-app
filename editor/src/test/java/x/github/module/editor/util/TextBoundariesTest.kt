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

package x.github.module.editor.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The word movement of Ctrl+Left / Ctrl+Right. (The grapheme functions of
 * TextBoundaries need android.icu and are covered on a device, by EditorViewTest.)
 */
class TextBoundariesTest {

    @Test
    fun previousWordStartGoesToTheStartOfTheWordBefore() {
        //          0123456789
        val text = "foo barbaz"

        assertEquals(4, TextBoundaries.previousWordStart(text, 10), "from the end of a word")
        assertEquals(4, TextBoundaries.previousWordStart(text, 7), "from inside a word")
        assertEquals(0, TextBoundaries.previousWordStart(text, 4), "from the start of a word: the one before")
        assertEquals(0, TextBoundaries.previousWordStart(text, 3), "from the end of the first word")
        assertEquals(0, TextBoundaries.previousWordStart(text, 0), "nowhere to go")
    }

    @Test
    fun nextWordEndGoesToTheEndOfTheWordAfter() {
        //          0123456789
        val text = "foo barbaz"

        assertEquals(3, TextBoundaries.nextWordEnd(text, 0), "from the start of a word")
        assertEquals(3, TextBoundaries.nextWordEnd(text, 1), "from inside a word")
        assertEquals(10, TextBoundaries.nextWordEnd(text, 3), "from the end of a word: the one after")
        assertEquals(10, TextBoundaries.nextWordEnd(text, 10), "nowhere to go")
    }

    @Test
    fun whitespaceIsSkippedHoweverMuchThereIs() {
        //          01234567890
        val text = "ab    \t cd"

        assertEquals(0, TextBoundaries.previousWordStart(text, 8))
        assertEquals(10, TextBoundaries.nextWordEnd(text, 2))
    }

    // In code, "foo.bar(baz)" is five stops, not one.
    @Test
    fun punctuationIsAStopOfItsOwn() {
        //          012345678901
        val text = "foo.bar(baz)"

        assertEquals(3, TextBoundaries.nextWordEnd(text, 0))
        assertEquals(4, TextBoundaries.nextWordEnd(text, 3))
        assertEquals(7, TextBoundaries.nextWordEnd(text, 4))
        assertEquals(8, TextBoundaries.nextWordEnd(text, 7))

        assertEquals(11, TextBoundaries.previousWordStart(text, 12))
        assertEquals(8, TextBoundaries.previousWordStart(text, 11))
        assertEquals(7, TextBoundaries.previousWordStart(text, 8))
    }

    @Test
    fun aRunOfPunctuationIsOneStop() {
        //          0123456
        val text = "a != b"

        assertEquals(4, TextBoundaries.nextWordEnd(text, 1))
        assertEquals(2, TextBoundaries.previousWordStart(text, 4))
    }

    @Test
    fun underscoresAndDigitsBelongToTheWord() {
        val text = "snake_case_2 x"

        assertEquals(12, TextBoundaries.nextWordEnd(text, 0))
        assertEquals(0, TextBoundaries.previousWordStart(text, 12))
    }

    @Test
    fun offsetsOutsideTheTextAreTakenAsItsEnds() {
        assertEquals(0, TextBoundaries.previousWordStart("abc", -5))
        assertEquals(0, TextBoundaries.previousWordStart("abc", 99))
        assertEquals(3, TextBoundaries.nextWordEnd("abc", 99))
        assertEquals(0, TextBoundaries.nextWordEnd("", 0))
    }
}
