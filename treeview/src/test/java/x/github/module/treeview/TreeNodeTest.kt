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

package x.github.module.treeview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The tree itself, without the view: children, parents, depths, ancestors, descendants, load state. */
class TreeNodeTest {

    // root / a / (a1, a2), b; addChild returns the node it is called on (for chaining)
    private fun tree(): Triple<TreeNode<String>, TreeNode<String>, TreeNode<String>> {
        val root = TreeNode("root", isExpanded = true)
        val a = TreeNode("a")
        val b = TreeNode("b")
        root.addChild(a).addChild(b)
        a.addChild(TreeNode("a1")).addChild(TreeNode("a2"))
        return Triple(root, a, b)
    }

    @Test
    fun addChildReturnsTheParentForChaining() {
        val root = TreeNode("root")
        val child = TreeNode("child")
        assertSame(root, root.addChild(child))
        assertSame(root, child.parent)
    }

    @Test
    fun aChildKnowsItsParentAndDepth() {
        val (root, a, _) = tree()
        val a1 = a.children.first()
        assertSame(a, a1.parent)
        assertSame(root, a.parent)
        assertNull(root.parent)
        assertEquals(listOf(0, 1, 2), listOf(root.depth, a.depth, a1.depth))
        assertSame(root, a1.root)
    }

    @Test
    fun childrenInOrder() {
        val (root, a, b) = tree()
        assertEquals(listOf("a", "b"), root.children.map { it.value })
        val c = TreeNode("c")
        root.insertChild(1, c)
        assertEquals(listOf("a", "c", "b"), root.children.map { it.value })
        assertTrue(root.removeChild(c))
        assertNull(c.parent)
        assertFalse(root.removeChild(c))
        assertEquals(listOf(a, b), root.children)
    }

    @Test
    fun ancestorsAndDescendants() {
        val (root, a, b) = tree()
        val a2 = a.children.last()
        assertEquals(listOf("a", "root"), a2.ancestors().map { it.value }.toList())
        assertEquals(setOf("a", "a1", "a2", "b"), root.descendants().map { it.value }.toSet())
        assertTrue(a2.isDescendantOf(root))
        assertTrue(a2.isDescendantOf(a))
        assertFalse(a2.isDescendantOf(b))
        assertFalse(root.isDescendantOf(a2))
    }

    @Test
    fun aMovedSubtreeTakesItsDepths() {
        val (root, a, b) = tree()
        root.removeChild(a)
        b.addChild(a)
        assertSame(b, a.parent)
        assertEquals(2, a.depth)
        assertEquals(listOf(3, 3), a.children.map { it.depth })
    }

    @Test
    fun leavesAndWhatIsKnownToBeEmpty() {
        val loaded = TreeNode("empty dir")
        assertTrue(loaded.isLeaf)
        assertTrue(loaded.isKnownEmpty)
        // children not loaded yet: no children so far, but not known to be empty
        val lazy = TreeNode("not listed yet", childrenLoaded = false)
        assertTrue(lazy.isLeaf)
        assertFalse(lazy.isKnownEmpty)
        assertEquals(TreeNode.LoadState.NOT_LOADED, lazy.loadState)
    }
}
