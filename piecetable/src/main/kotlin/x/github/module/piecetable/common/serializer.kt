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

package x.github.module.piecetable.common

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

import x.github.module.piecetable.TreeNode
import x.github.module.piecetable.NodeColor
import x.github.module.piecetable.Piece
import x.github.module.piecetable.SENTINEL
import x.github.module.piecetable.NULL

/**
 * The serializer for StringBuffer
 */
internal object StringBufferSerializer : KSerializer<StringBuffer> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "java.lang.StringBuffer", 
        PrimitiveKind.STRING
    )

    override fun serialize(encoder: Encoder, value: StringBuffer) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): StringBuffer {
        return StringBuffer(decoder.decodeString())
    }
}

/**
 * TreeNode cannot be serialized directly because it contains self-references
 * which will cause a stack overflow error
 * therefore, its internal self-reference property need to be turned into serializable fields 
 * and the tree structure needs to be flattened into a list
 */
@Serializable
internal data class SerializableNode(
    val id: Int,
    val piece: Piece,
    val color: NodeColor,
    val size_left: Int,
    val lf_left: Int,
    val parent_id: Int,
    val left_id: Int,
    val right_id: Int
)

/**
 * The serializer for TreeNode
 */
internal object TreeNodeSerializer : KSerializer<TreeNode> {
    // Special IDs for singleton nodes
    private const val SENTINEL_ID = -0x1
    private const val NULL_ID = -0x2

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TreeNode") {
        element("nodes", ListSerializer(SerializableNode.serializer()).descriptor)
        element<Int>("rootId")
    }

    override fun serialize(encoder: Encoder, value: TreeNode) {
        encoder.encodeStructure(descriptor) {
            val nodeToId = mutableMapOf<TreeNode, Int>()
            val nodes = mutableListOf<TreeNode>()

            // Traverse the tree to find all nodes and assign IDs
            fun traverse(node: TreeNode) {
                if (node === SENTINEL || node === NULL || node in nodeToId) {
                    return@traverse
                }
                nodeToId[node] = nodes.size
                nodes.add(node)                
                traverse(node.left)
                traverse(node.right)
            }
            
            // Start traversal from the root node
            // note that the value node may not the root node
            val rootNode = value.root()
            traverse(rootNode)
                        
            // Assign special IDs
            nodeToId[SENTINEL] = SENTINEL_ID
            nodeToId[NULL] = NULL_ID

            val serializableNodes = nodes.map { node ->
                SerializableNode(
                    id = nodeToId.getValue(node),
                    piece = node.piece,
                    color = node.color,
                    size_left = node.size_left,
                    lf_left = node.lf_left,
                    parent_id = nodeToId.getValue(node.parent),
                    left_id = nodeToId.getValue(node.left),
                    right_id = nodeToId.getValue(node.right)
                )
            }
            
            encodeSerializableElement(descriptor, 0, ListSerializer(SerializableNode.serializer()), serializableNodes)
            encodeIntElement(descriptor, 1, nodeToId[rootNode] ?: SENTINEL_ID)
        }
    }

    override fun deserialize(decoder: Decoder): TreeNode {
        return decoder.decodeStructure(descriptor) {
            var serializableNodes: List<SerializableNode> = emptyList()
            var rootId = SENTINEL_ID
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> serializableNodes = decodeSerializableElement(descriptor, 0, ListSerializer(SerializableNode.serializer()))
                    1 -> rootId = decodeIntElement(descriptor, 1)
                    CompositeDecoder.DECODE_DONE -> break                    
                }
            }
            
            val idToNode = mutableMapOf<Int, TreeNode>()
            idToNode[SENTINEL_ID] = SENTINEL
            idToNode[NULL_ID] = NULL
                        
            // First, create all TreeNode instances without setting references
            for (sNode in serializableNodes) {
                val node = TreeNode(sNode.piece, sNode.color).apply {
                    size_left = sNode.size_left
                    lf_left = sNode.lf_left
                }
                idToNode[sNode.id] = node
            }

            // Second, set the parent, left, and right references
            for (sNode in serializableNodes) {
                val node = idToNode.getValue(sNode.id)
                node.parent = idToNode.getValue(sNode.parent_id)
                node.left = idToNode.getValue(sNode.left_id)
                node.right = idToNode.getValue(sNode.right_id)
            }
                        
            idToNode[rootId] ?: SENTINEL
        }
    }
}

