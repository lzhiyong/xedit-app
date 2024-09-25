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

#include "ts_utils.h"

#ifdef __cplusplus
extern "C" {
#endif

// define global variables
jclass javaTSTreeCursorClass = nullptr;

jlong JNICALL tree_cursor_init(JNIEnv *env, jclass clazz, jobject node) {
    return reinterpret_cast<jlong>(
        new TSTreeCursor(
            ts_tree_cursor_new(unmarshal_node(env, node))
        )
    );
}

jlong JNICALL tree_cursor_copy(jlong pointer) {
    TSTreeCursor copy = ts_tree_cursor_copy(
        reinterpret_cast<TSTreeCursor*>(pointer)
    );
    // return the copied TSTreeCursor
    return reinterpret_cast<jlong>(new TSTreeCursor(copy));
}

void JNICALL tree_cursor_delete(jlong pointer) {
    ts_tree_cursor_delete(reinterpret_cast<TSTreeCursor*>(pointer));
    delete reinterpret_cast<TSTreeCursor*>(pointer);
}

jobject JNICALL tree_cursor_get_current_node(JNIEnv *env, jobject thiz) {
    auto tree_cursor = GET_POINTER(TSTreeCursor, thiz);
    TSNode node = ts_tree_cursor_current_node(tree_cursor);
    jobject tree = GET_FIELD(Object, thiz, TSTreeCursor_tree);
    return marshal_node(env, &node, tree);
}

jint JNICALL tree_cursor_get_current_depth(JNIEnv *env, jobject thiz) {
    auto tree_cursor = GET_POINTER(TSTreeCursor, thiz);
    return ts_tree_cursor_current_depth(tree_cursor);
}

jshort JNICALL tree_cursor_get_current_field_id(JNIEnv *env, jobject thiz) {
    auto tree_cursor = GET_POINTER(TSTreeCursor, thiz);
    return ts_tree_cursor_current_field_id(tree_cursor);
}

jstring JNICALL tree_cursor_get_current_field_name(JNIEnv *env, jobject thiz) {
    auto tree_cursor = GET_POINTER(TSTreeCursor, thiz);
    const char *name = ts_tree_cursor_current_field_name(tree_cursor);
    return name ? env->NewStringUTF(name) : nullptr;
}

jint JNICALL tree_cursor_get_current_descendant_index(JNIEnv *env, jobject thiz) {
    auto tree_cursor = GET_POINTER(TSTreeCursor, thiz);
    return ts_tree_cursor_current_descendant_index(tree_cursor);
}

void JNICALL tree_cursor_reset__node(JNIEnv *env, jobject thiz, jobject node) {
    auto tree_cursor = GET_POINTER(TSTreeCursor, thiz);
    ts_tree_cursor_reset(tree_cursor, unmarshal_node(env, node));
}

void JNICALL tree_cursor_reset__cursor(JNIEnv *env, jobject thiz, jobject cursor) {
    auto self = GET_POINTER(TSTreeCursor, thiz);
    auto other = GET_POINTER(TSTreeCursor, cursor);
    ts_tree_cursor_reset_to(self, other);
}

jboolean JNICALL tree_cursor_goto_first_child(JNIEnv *env, jobject thiz) {
    auto tree_cursor = GET_POINTER(TSTreeCursor, thiz);
    return ts_tree_cursor_goto_first_child(tree_cursor);
}

jboolean JNICALL tree_cursor_goto_last_child(JNIEnv *env, jobject thiz) {
    auto tree_cursor = GET_POINTER(TSTreeCursor, thiz);
    return ts_tree_cursor_goto_last_child(tree_cursor);
}

jboolean JNICALL tree_cursor_goto_parent(JNIEnv *env, jobject thiz) {
    auto tree_cursor = GET_POINTER(TSTreeCursor, thiz);
    return ts_tree_cursor_goto_parent(tree_cursor);
}

jboolean JNICALL tree_cursor_goto_next_sibling(JNIEnv *env, jobject thiz) {
    auto tree_cursor = GET_POINTER(TSTreeCursor, thiz);
    return ts_tree_cursor_goto_next_sibling(tree_cursor);
}

jboolean JNICALL tree_cursor_goto_previous_sibling(JNIEnv *env, jobject thiz) {
    auto tree_cursor = GET_POINTER(TSTreeCursor, thiz);
    return ts_tree_cursor_goto_previous_sibling(tree_cursor);
}

void JNICALL tree_cursor_goto_descendant(JNIEnv *env, jobject thiz, jint index) {
    auto tree_cursor = GET_POINTER(TSTreeCursor, thiz);
    ts_tree_cursor_goto_descendant(tree_cursor, static_cast<uint32_t>(index));
}

jlong JNICALL tree_cursor_goto_first_child_for_byte(JNIEnv *env, jobject thiz, jint byte) {
    auto tree_cursor = GET_POINTER(TSTreeCursor, thiz);
    return ts_tree_cursor_goto_first_child_for_byte(tree_cursor, static_cast<uint32_t>(byte));
}

jlong JNICALL tree_cursor_goto_first_child_for_point(JNIEnv *env, jobject thiz, jobject point) {
    auto tree_cursor = GET_POINTER(TSTreeCursor, thiz);
    return ts_tree_cursor_goto_first_child_for_point(tree_cursor, unmarshal_point(env, point));
}

extern const JNINativeMethod TSTreeCursor_methods[] = {
    {"init", "(L" PACKAGE "TSNode;)J", (void *)&tree_cursor_init},
    {"copy", "(J)J", (void *)&tree_cursor_copy},
    {"delete", "(J)V", (void *)&tree_cursor_delete},
    {"getCurrentNode", "()L" PACKAGE "TSNode;", (void *)&tree_cursor_get_current_node},
    {"getCurrentDepth", "()I", (void *)&tree_cursor_get_current_depth},
    {"getCurrentFieldId", "()S", (void *)&tree_cursor_get_current_field_id},
    {"getCurrentFieldName", "()Ljava/lang/String;", (void *)&tree_cursor_get_current_field_name},
    {"getCurrentDescendantIndex", "()I", (void *)&tree_cursor_get_current_descendant_index},
    {"reset", "(L" PACKAGE "TSNode;)V", (void *)&tree_cursor_reset__node},
    {"reset", "(L" PACKAGE "TSTreeCursor;)V", (void *)&tree_cursor_reset__cursor},
    {"gotoFirstChild", "()Z", (void *)&tree_cursor_goto_first_child},
    {"gotoLastChild", "()Z", (void *)&tree_cursor_goto_last_child},
    {"gotoParent", "()Z", (void *)&tree_cursor_goto_parent},
    {"gotoNextSibling", "()Z", (void *)&tree_cursor_goto_next_sibling},
    {"gotoPreviousSibling", "()Z", (void *)&tree_cursor_goto_previous_sibling},
    {"gotoDescendant", "(I)V", (void *)&tree_cursor_goto_descendant},
    {"gotoFirstChildForByte", "(I)J", (void *)&tree_cursor_goto_first_child_for_byte},
    {"gotoFirstChildForPoint", "(L" PACKAGE "TSPoint;)J",
     (void *)&tree_cursor_goto_first_child_for_point},
};

extern const size_t TSTreeCursor_methods_size = sizeof TSTreeCursor_methods / sizeof(JNINativeMethod);

#ifdef __cplusplus
}
#endif // __cplusplus

