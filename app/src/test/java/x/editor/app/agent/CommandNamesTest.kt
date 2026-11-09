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

package x.editor.app.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class CommandNamesTest {

    private fun names(line: String) = CommandNames.of(line)

    @Test
    fun theProgramsOfEachCommand() {
        assertEquals(listOf("cd", "python3", "grep"), names("cd app && python3 x.py | grep -c ok"))
        assertEquals(listOf("ls", "wc"), names("ls -la\nwc -l *.kt"))
        assertEquals(listOf("cd", "javac"), names("(cd sub; javac A.java)"))
        assertEquals(listOf("git", "echo"), names("x=\$(git rev-parse HEAD); echo \$x"))
    }

    @Test
    fun pastVariablesRedirectionsAndStarters() {
        assertEquals(listOf("make", "tail"), names("CC=clang make -j4 2>&1 | tail -n 20"))
        assertEquals(listOf("node"), names("env FOO=1 node index.js > out.txt"))
        assertEquals(listOf("cargo"), names("timeout 60 cargo test -- --nocapture"))
        assertEquals(listOf("sleep"), names("nohup sleep 5 &"))
        assertEquals(listOf("echo"), names("echo \$((1+2)) > /dev/null 2> err.txt"))
    }

    @Test
    fun theShellsOwnWordsAndWhatTellsNothing() {
        assertEquals(listOf("gcc"), names("for f in *.c; do gcc -c \"\$f\"; done"))
        assertEquals(listOf("["), names("if [ -f build.gradle ]; then ./gradlew build; fi"))
        assertEquals(emptyList(), names("\$CC main.c && ./a.out"))
        assertEquals(listOf("pip"), names("command -v python3 || pip install x"))
    }

    @Test
    fun quotesAndCommentsKeepTheirSigns() {
        assertEquals(listOf("echo", "ls"), names("echo \"a; b | c\" && ls"))
        assertEquals(listOf("pkg"), names("pkg install -y python # comment ; rm"))
    }
}
