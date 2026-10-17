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

package x.github.module.editor.input

/**
 * Decides how much one press of the delete key takes away while the key is held.
 *
 * Holding backspace first deletes character by character, as each repeat of the key
 * arrives. If it is still held after a while, the user evidently wants a lot gone:
 * from then on it deletes whole words, at a pace of its own ([wordsPerSecond]). A
 * key repeats every 50 ms or so, and a word at every repeat would be twenty words a
 * second, far more than anybody can follow or stop in time; so in between, repeats
 * delete nothing. Letting go (a pause between two presses) starts over with single
 * characters.
 *
 * It only counts time between presses; where they come from does not matter: the
 * repeating key of a hardware keyboard, or a soft keyboard sending one delete after
 * another while its key is held.
 */
class BackspaceRepeat(
    // Presses closer together than this belong to one hold of the key. A held key
    // repeats every 50 ms or so; fingers tapping as fast as they can rarely go
    // under 130 ms, and they should go on deleting characters.
    private val repeatGapMillis: Long = 120L,
    // after holding for this long, deletion goes by words
    private val wordAfterMillis: Long = 900L
) {

    /** What one press of the key does. */
    enum class Step {
        /** Delete the character before the cursor. */
        CHARACTER,
        /** Delete the word before the cursor. */
        WORD,
        /** Nothing: a repeat between two words. */
        NONE
    }

    companion object {
        /** How many words a second a held key deletes, unless set otherwise. */
        const val DEFAULT_WORDS_PER_SECOND = 10f
    }

    /**
     * How many words a second are deleted once the key has been held long enough.
     * 0 never goes over to words: the key goes on deleting characters, as fast as it
     * repeats.
     */
    var wordsPerSecond: Float = DEFAULT_WORDS_PER_SECOND
        set(value) {
            field = Math.max(0f, value)
        }

    // when the key went down for the hold that is going on, and when it last repeated
    private var holdStartMillis = 0L
    private var lastPressMillis = Long.MIN_VALUE / 2
    // when the last word of this hold went
    private var lastWordMillis = Long.MIN_VALUE / 2

    /**
     * Call for every backspace, with the time it arrived at (uptime, milliseconds).
     *
     * @return what this one should delete
     */
    fun next(nowMillis: Long): Step {
        if (nowMillis - lastPressMillis > repeatGapMillis) {
            // a pause before it: a new hold (or a single tap)
            holdStartMillis = nowMillis
            lastWordMillis = Long.MIN_VALUE / 2
        }
        lastPressMillis = nowMillis

        val speed = wordsPerSecond
        if (speed <= 0f || nowMillis - holdStartMillis < wordAfterMillis) {
            return Step.CHARACTER
        }
        // The first word goes at once, the next ones when their time has come.
        // (Counted from when the last one went, not on a fixed beat: a repeat comes
        // a little late now and then, and should not make two words go at once.)
        val interval = (1000f / speed).toLong()
        if (nowMillis - lastWordMillis < interval) {
            return Step.NONE
        }
        lastWordMillis = nowMillis
        return Step.WORD
    }

    /** Forgets the hold that is going on: the next backspace is a character again. */
    fun reset() {
        lastPressMillis = Long.MIN_VALUE / 2
        lastWordMillis = Long.MIN_VALUE / 2
    }
}
