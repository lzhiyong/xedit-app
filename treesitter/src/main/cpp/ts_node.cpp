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

/*
 * Derived from kotlin-tree-sitter (https://github.com/tree-sitter/kotlin-tree-sitter),
 * Copyright (c) 2024 tree-sitter contributors, under the MIT License: see
 * LICENSE-kotlin-tree-sitter at the root of this module. Its JNI layer, written in C there,
 * rewritten in C++, and changed since.
 */

#include <stdio.h>

#include "ts_utils.h"

#ifdef __cplusplus
extern "C" {
#endif

// The scalars of a node, as @CriticalNative calls (TSNode's companion): the node comes
// as its fields, no object is read through JNI, no JNIEnv is set up. A getter such as
// endByte was otherwise five JNI calls to read the TSNode, for one addition.
static inline TSNode node_of(jint c0, jint c1, jint c2, jint c3, jlong id, jlong tree) {
    return TSNode {
        .context = {(uint32_t)c0, (uint32_t)c1, (uint32_t)c2, (uint32_t)c3},
        .id = reinterpret_cast<const void *>(id),
        .tree = reinterpret_cast<const TSTree *>(tree)
    };
}

#define NODE_SCALAR(name, type, expr)                                                   \
    static type JNICALL name(jint c0, jint c1, jint c2, jint c3, jlong id, jlong tree) { \
        TSNode self = node_of(c0, c1, c2, c3, id, tree);                                \
        return static_cast<type>(expr);                                                 \
    }

NODE_SCALAR(node_end_byte, jint, ts_node_end_byte(self))
NODE_SCALAR(node_end_row, jint, ts_node_end_point(self).row)
NODE_SCALAR(node_end_column, jint, ts_node_end_point(self).column)
NODE_SCALAR(node_symbol, jint, ts_node_symbol(self))
NODE_SCALAR(node_grammar_symbol, jint, ts_node_grammar_symbol(self))
NODE_SCALAR(node_is_named, jboolean, ts_node_is_named(self))
NODE_SCALAR(node_is_extra, jboolean, ts_node_is_extra(self))
NODE_SCALAR(node_is_error, jboolean, ts_node_is_error(self))
NODE_SCALAR(node_is_missing, jboolean, ts_node_is_missing(self))
NODE_SCALAR(node_has_error, jboolean, ts_node_has_error(self))
NODE_SCALAR(node_has_changes, jboolean, ts_node_has_changes(self))
NODE_SCALAR(node_parse_state, jint, ts_node_parse_state(self))
NODE_SCALAR(node_next_parse_state, jint, ts_node_next_parse_state(self))
NODE_SCALAR(node_child_count, jint, ts_node_child_count(self))
NODE_SCALAR(node_named_child_count, jint, ts_node_named_child_count(self))
NODE_SCALAR(node_descendant_count, jint, ts_node_descendant_count(self))

#undef NODE_SCALAR

jobject JNICALL node_string(JNIEnv *env, jobject thiz) {
    TSNode self = unmarshal_node(env, thiz);
    const char *string = ts_node_string(self);
    jstring node_text = env->NewStringUTF(string);
    // here requires free the string
    free((void*)string);
    return node_text;
}



jobject JNICALL node_get_parent(JNIEnv *env, jobject thiz) {
    TSNode self = unmarshal_node(env, thiz);
    auto parent = ts_node_parent(self);
    if (ts_node_is_null(parent))
        return nullptr;
    jobject tree = GET_FIELD(Object, thiz, TSNode_tree);
    return marshal_node(env, &parent, tree);
}

jobject JNICALL node_get_next_sibling(JNIEnv *env, jobject thiz) {
    TSNode self = unmarshal_node(env, thiz);
    TSNode result = ts_node_next_sibling(self);
    if (ts_node_is_null(result))
        return nullptr;
    jobject tree = GET_FIELD(Object, thiz, TSNode_tree);
    return marshal_node(env, &result, tree);
}

jobject JNICALL node_get_prev_sibling(JNIEnv *env, jobject thiz) {
    TSNode self = unmarshal_node(env, thiz);
    TSNode result = ts_node_prev_sibling(self);
    if (ts_node_is_null(result))
        return nullptr;
    jobject tree = GET_FIELD(Object, thiz, TSNode_tree);
    return marshal_node(env, &result, tree);
}

jobject JNICALL node_get_prev_named_sibling(JNIEnv *env, jobject thiz) {
    TSNode self = unmarshal_node(env, thiz);
    TSNode result = ts_node_prev_named_sibling(self);
    if (ts_node_is_null(result))
        return nullptr;
    jobject tree = GET_FIELD(Object, thiz, TSNode_tree);
    return marshal_node(env, &result, tree);
}

jobject JNICALL node_get_next_named_sibling(JNIEnv *env, jobject thiz) {
    TSNode self = unmarshal_node(env, thiz);
    TSNode result = ts_node_next_named_sibling(self);
    if (ts_node_is_null(result))
        return nullptr;
    jobject tree = GET_FIELD(Object, thiz, TSNode_tree);
    return marshal_node(env, &result, tree);
}

jobject JNICALL node_get_children(JNIEnv *env, jobject thiz) {
    TSNode self = unmarshal_node(env, thiz);
    uint32_t count = ts_node_child_count(self);
    
    jobject array_list = NEW_OBJECT(ArrayList, (jint)count);
    if (count == 0) return array_list;

    jobject tree = GET_FIELD(Object, thiz, TSNode_tree);
    TSTreeCursor cursor = ts_tree_cursor_new(self);
    ts_tree_cursor_goto_first_child(&cursor);
    for (uint32_t i = 0; i < count; ++i) {
        TSNode child_node = ts_tree_cursor_current_node(&cursor);
        jobject node_object = marshal_node(env, &child_node, tree);
        CALL_METHOD(Boolean, array_list, ArrayList_add, node_object);       
        env->DeleteLocalRef(node_object);
        ts_tree_cursor_goto_next_sibling(&cursor);
    }
    
    ts_tree_cursor_delete(&cursor);    
    return array_list;
}


jobject JNICALL node_child(JNIEnv *env, jobject thiz, jint index) {
    TSNode self = unmarshal_node(env, thiz);
    if (ts_node_child_count(self) <= static_cast<uint32_t>(index)) {
        const char *fmt = "Child index %u is out of bounds";
        char buffer[40] = {0};
        sprintf(buffer, fmt, static_cast<uint32_t>(index));
        THROW(IndexOutOfBoundsException, static_cast<const char*>(buffer));
        return nullptr;
    }

    TSNode result = ts_node_child(self, static_cast<uint32_t>(index));
    if (ts_node_is_null(result))
        return nullptr;
    jobject tree = GET_FIELD(Object, thiz, TSNode_tree);
    return marshal_node(env, &result, tree);
}

jobject JNICALL node_named_child(JNIEnv *env, jobject thiz, jint index) {
    TSNode self = unmarshal_node(env, thiz);
    if (ts_node_child_count(self) <= static_cast<uint32_t>(index)) {
        const char *fmt = "Child index %u is out of bounds";
        char buffer[40] = {0};
        sprintf(buffer, fmt, static_cast<uint32_t>(index));
        THROW(IndexOutOfBoundsException, static_cast<const char*>(buffer));
        return nullptr;
    }

    TSNode result = ts_node_named_child(self, static_cast<uint32_t>(index));
    if (ts_node_is_null(result))
        return nullptr;
    jobject tree = GET_FIELD(Object, thiz, TSNode_tree);
    return marshal_node(env, &result, tree);
}

jobject JNICALL node_first_child_for_byte(JNIEnv *env, jobject thiz, jint byte) {
    TSNode self = unmarshal_node(env, thiz);
    TSNode result = ts_node_first_child_for_byte(self, byte);
    if (ts_node_is_null(result))
        return nullptr;
    jobject tree = GET_FIELD(Object, thiz, TSNode_tree);
    return marshal_node(env, &result, tree);
}

jobject JNICALL node_first_named_child_for_byte(JNIEnv *env, jobject thiz, jint byte) {
    TSNode self = unmarshal_node(env, thiz);
    TSNode result = ts_node_first_named_child_for_byte(self, byte);
    if (ts_node_is_null(result))
        return nullptr;
    jobject tree = GET_FIELD(Object, thiz, TSNode_tree);
    return marshal_node(env, &result, tree);
}

jobject JNICALL node_child_by_field_id(JNIEnv *env, jobject thiz, jshort id) {
    TSNode self = unmarshal_node(env, thiz);
    TSNode result = ts_node_child_by_field_id(self, static_cast<uint16_t>(id));
    if (ts_node_is_null(result))
        return nullptr;
    jobject tree = GET_FIELD(Object, thiz, TSNode_tree);
    return marshal_node(env, &result, tree);
}

jobject JNICALL node_child_by_field_name(JNIEnv *env, jobject thiz, jstring name) {
    TSNode self = unmarshal_node(env, thiz);
    const char *field_name = env->GetStringUTFChars(name, nullptr);
    uint32_t length = static_cast<uint32_t>(env->GetStringUTFLength(name));
    TSNode result = ts_node_child_by_field_name(self, field_name, length);
    env->ReleaseStringUTFChars(name, field_name);
    if (ts_node_is_null(result))
        return nullptr;
    jobject tree = GET_FIELD(Object, thiz, TSNode_tree);
    return marshal_node(env, &result, tree);
}

jobject JNICALL node_children_by_field_id(JNIEnv *env, jobject thiz, jshort id) {
    // nothing to do
    if (id == 0) return NEW_OBJECT(ArrayList, 0);
    // ...
    TSNode self = unmarshal_node(env, thiz);
    uint32_t count = ts_node_child_count(self);
    jobject array_list = NEW_OBJECT(ArrayList, static_cast<jint>(count));
    jobject tree = GET_FIELD(Object, thiz, TSNode_tree);
    TSTreeCursor cursor = ts_tree_cursor_new(self);
    bool ok = ts_tree_cursor_goto_first_child(&cursor);
    while (ok) {
        uint16_t field_id = ts_tree_cursor_current_field_id(&cursor);
        if (field_id == static_cast<uint16_t>(id)) {
            TSNode child_node = ts_tree_cursor_current_node(&cursor);
            jobject node_object = marshal_node(env, &child_node, tree);
            CALL_METHOD(Boolean, array_list, ArrayList_add, node_object);
            env->DeleteLocalRef(node_object);
        }
        ok = ts_tree_cursor_goto_next_sibling(&cursor);
    }
    ts_tree_cursor_delete(&cursor);
    return array_list;
}

jstring JNICALL node_field_name_for_child(JNIEnv *env, jobject thiz, jint index) {
    TSNode self = unmarshal_node(env, thiz);
    if (ts_node_child_count(self) <= static_cast<uint32_t>(index)) {
        const char *fmt = "Child index %u is out of bounds";
        char buffer[40] = {0};
        sprintf(buffer, fmt, static_cast<uint32_t>(index));
        THROW(IndexOutOfBoundsException, static_cast<const char*>(buffer));
        return nullptr;
    }

    const char *field_name = ts_node_field_name_for_child(self, static_cast<uint32_t>(index));
    return field_name ? env->NewStringUTF(field_name) : nullptr;
}

jstring JNICALL node_field_name_for_named_child(JNIEnv *env, jobject thiz, jint index) {
    TSNode self = unmarshal_node(env, thiz);
    if (ts_node_child_count(self) <= static_cast<uint32_t>(index)) {
        const char *fmt = "Child index %u is out of bounds";
        char buffer[40] = {0};
        sprintf(buffer, fmt, static_cast<uint32_t>(index));
        THROW(IndexOutOfBoundsException, static_cast<const char*>(buffer));
        return nullptr;
    }

    const char *field_name = ts_node_field_name_for_named_child(self, static_cast<uint32_t>(index));
    return field_name ? env->NewStringUTF(field_name) : nullptr;
}

jobject JNICALL node_child_with_descendant(JNIEnv *env, jobject thiz, jobject descendant) {
    TSNode self = unmarshal_node(env, thiz);
    TSNode other = unmarshal_node(env, descendant);
    TSNode result = ts_node_child_with_descendant(self, other);
    if (ts_node_is_null(result))
        return nullptr;
    jobject tree = GET_FIELD(Object, thiz, TSNode_tree);
    return marshal_node(env, &result, tree);
}

jobject JNICALL node_descendant_bytes(JNIEnv *env, jobject thiz, jint start, jint end) {
    TSNode self = unmarshal_node(env, thiz);
    TSNode result = ts_node_descendant_for_byte_range(self, (uint32_t)start, (uint32_t)end);
    if (ts_node_is_null(result))
        return nullptr;
    jobject tree = GET_FIELD(Object, thiz, TSNode_tree);
    return marshal_node(env, &result, tree);
}

jobject JNICALL node_descendant_points(JNIEnv *env, jobject thiz, jobject start, jobject end) {
    TSNode self = unmarshal_node(env, thiz);
    TSPoint start_point = unmarshal_point(env, start);
    TSPoint end_point = unmarshal_point(env, end);
    TSNode result = ts_node_descendant_for_point_range(self, start_point, end_point);
    if (ts_node_is_null(result))
        return nullptr;
    jobject tree = GET_FIELD(Object, thiz, TSNode_tree);
    return marshal_node(env, &result, tree);
}

jobject JNICALL node_named_descendant_bytes(JNIEnv *env, jobject thiz, jint start, jint end) {
    TSNode self = unmarshal_node(env, thiz);
    TSNode result = ts_node_named_descendant_for_byte_range(self, (uint32_t)start, (uint32_t)end);
    if (ts_node_is_null(result))
        return nullptr;
    jobject tree = GET_FIELD(Object, thiz, TSNode_tree);
    return marshal_node(env, &result, tree);
}

jobject JNICALL node_named_descendant_points(JNIEnv *env, jobject thiz, jobject start, jobject end) {
    TSNode self = unmarshal_node(env, thiz);
    TSPoint start_point = unmarshal_point(env, start);
    TSPoint end_point = unmarshal_point(env, end);
    TSNode result = ts_node_named_descendant_for_point_range(self, start_point, end_point);
    if (ts_node_is_null(result))
        return nullptr;
    jobject tree = GET_FIELD(Object, thiz, TSNode_tree);
    return marshal_node(env, &result, tree);
}

void JNICALL node_edit(JNIEnv *env, jobject thiz, jobject edit) {
    TSNode self = unmarshal_node(env, thiz);
    TSInputEdit input_edit = unmarshal_input_edit(env, edit);
    ts_node_edit(&self, &input_edit);
    jintArray context = (jintArray)GET_FIELD(Object, thiz, TSNode_context);
    env->SetIntArrayRegion(context, 0, 4, (jint *)self.context);
}

jstring JNICALL node_sexp(JNIEnv *env, jobject thiz) {
    TSNode self = unmarshal_node(env, thiz);
    const char *sexp = ts_node_string(self);
    jstring result = env->NewStringUTF(sexp);
    free((void*)sexp);
    return result;
}



extern const JNINativeMethod TSNode_methods[] = {
    {"getString", "()Ljava/lang/String;", (void *)&node_string},
    {"getParent", "()L" PACKAGE "TSNode;", (void *)&node_get_parent},
    {"getNextSibling", "()L" PACKAGE "TSNode;", (void *)&node_get_next_sibling},
    {"getNextNamedSibling", "()L" PACKAGE "TSNode;", (void *)&node_get_next_named_sibling},
    {"getPrevSibling", "()L" PACKAGE "TSNode;", (void *)&node_get_prev_sibling},
    {"getPrevNamedSibling", "()L" PACKAGE "TSNode;", (void *)&node_get_prev_named_sibling},
    {"getChildren", "()Ljava/util/List;", (void *)&node_get_children},
    {"child", "(I)L" PACKAGE "TSNode;", (void *)&node_child},
    {"namedChild", "(I)L" PACKAGE "TSNode;", (void *)&node_named_child},
    {"firstChildForByte", "(I)L" PACKAGE "TSNode;", (void *)&node_first_child_for_byte},
    {"firstNamedChildForByte", "(I)L" PACKAGE "TSNode;", (void *)&node_first_named_child_for_byte},
    {"childByFieldId", "(S)L" PACKAGE "TSNode;", (void *)&node_child_by_field_id},
    {"childByFieldName", "(Ljava/lang/String;)L" PACKAGE "TSNode;",
     (void *)&node_child_by_field_name},
    {"childrenByFieldId", "(S)Ljava/util/List;", (void *)&node_children_by_field_id},
    {"fieldNameForChild", "(I)Ljava/lang/String;", (void *)&node_field_name_for_child},
    {"fieldNameForNamedChild", "(I)Ljava/lang/String;", (void *)&node_field_name_for_named_child},
    {"childWithDescendant", "(L" PACKAGE "TSNode;)L" PACKAGE "TSNode;",
     (void *)&node_child_with_descendant},
    {"descendant", "(II)L" PACKAGE "TSNode;", (void *)&node_descendant_bytes},
    {"descendant", "(L" PACKAGE "TSPoint;L" PACKAGE "TSPoint;)L" PACKAGE "TSNode;",
     (void *)&node_descendant_points},
    {"namedDescendant", "(II)L" PACKAGE "TSNode;", (void *)&node_named_descendant_bytes},
    {"namedDescendant", "(L" PACKAGE "TSPoint;L" PACKAGE "TSPoint;)L" PACKAGE "TSNode;",
     (void *)&node_named_descendant_points},
    {"edit", "(L" PACKAGE "TSInputEdit;)V", (void *)&node_edit},
    {"sexp", "()Ljava/lang/String;", (void *)&node_sexp},
    // @CriticalNative, static (TSNode.Companion)
    {"nEndByte", "(IIIIJJ)I", (void *)&node_end_byte},
    {"nEndRow", "(IIIIJJ)I", (void *)&node_end_row},
    {"nEndColumn", "(IIIIJJ)I", (void *)&node_end_column},
    {"nSymbol", "(IIIIJJ)I", (void *)&node_symbol},
    {"nGrammarSymbol", "(IIIIJJ)I", (void *)&node_grammar_symbol},
    {"nIsNamed", "(IIIIJJ)Z", (void *)&node_is_named},
    {"nIsExtra", "(IIIIJJ)Z", (void *)&node_is_extra},
    {"nIsError", "(IIIIJJ)Z", (void *)&node_is_error},
    {"nIsMissing", "(IIIIJJ)Z", (void *)&node_is_missing},
    {"nHasError", "(IIIIJJ)Z", (void *)&node_has_error},
    {"nHasChanges", "(IIIIJJ)Z", (void *)&node_has_changes},
    {"nParseState", "(IIIIJJ)I", (void *)&node_parse_state},
    {"nNextParseState", "(IIIIJJ)I", (void *)&node_next_parse_state},
    {"nChildCount", "(IIIIJJ)I", (void *)&node_child_count},
    {"nNamedChildCount", "(IIIIJJ)I", (void *)&node_named_child_count},
    {"nDescendantCount", "(IIIIJJ)I", (void *)&node_descendant_count},
};

extern const size_t TSNode_methods_size = sizeof TSNode_methods / sizeof(JNINativeMethod);

#ifdef __cplusplus
}
#endif // __cplusplus

