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

#include <ctype.h>
#include <malloc.h>

#include "ts_utils.h"

#ifdef __cplusplus
extern "C" {
#endif

static inline bool is_valid_identifier_char(char ch) { return isalnum(ch) || ch == '_'; }

static inline bool is_valid_predicate_char(char ch) {
    return isalnum(ch) || ch == '-' || ch == '_' || ch == '?' || ch == '.' || ch == '!';
}

jlong JNICALL query_init(JNIEnv *env, jclass clazz, jlong language, jstring pattern) {
    TSQueryError error_type;
    const char *pattern_chars = env->GetStringUTFChars(pattern, nullptr);
    uint32_t error_offset, length = env->GetStringUTFLength(pattern);
    // native TSLanguage
    TSLanguage *ts_language = reinterpret_cast<TSLanguage*>(language);
    TSQuery *self = ts_query_new(ts_language, pattern_chars, length, &error_offset, &error_type);
    if (self != nullptr) {
        env->ReleaseStringUTFChars(pattern, pattern_chars);
        // return the TSQuery pointer
        return reinterpret_cast<jlong>(self);
    }
    
    // query init failure, thow exception
    uint32_t start = 0, end = 0, row = 0, column;
#ifndef _MSC_VER
    char *line = strtok((char *)pattern_chars, "\n");
#else
    char *next_token = NULL;
    char *line = strtok_s((char *)pattern_chars, "\n", &next_token);
#endif
    while (line != NULL) {
        end = start + (uint32_t)strlen(line) + 1;
        if (end > error_offset)
            break;
        start = end;
        row += 1;
#ifndef _MSC_VER
        line = strtok(NULL, "\n");
#else
        line = strtok_s(NULL, "\n", &next_token);
#endif
    }
    column = error_offset - start, end = 0;

    switch (error_type) {
        case TSQueryErrorSyntax: {
            jobject exception = nullptr;
            if (error_offset < length) {
                exception = NEW_OBJECT(TSQueryError$Syntax, (jlong)row, (jlong)column);
            } else {
                exception = NEW_OBJECT(TSQueryError$Syntax, (jlong)-1, (jlong)-1);
            }
            env->Throw((jthrowable)exception);
            break;
        }
        case TSQueryErrorCapture: {
            while (is_valid_predicate_char(pattern_chars[error_offset + end])) {
                end += 1;
            }

            char *capture_chars = (char*)calloc(end + 1, sizeof(char));
            memcpy(capture_chars, &pattern_chars[error_offset], end);
            jstring capture = env->NewStringUTF(capture_chars);
            jobject exception = NEW_OBJECT(TSQueryError$Capture, (jint)row, (jint)column, capture);
            env->Throw((jthrowable)exception);
            free((void*)capture_chars);
            break;
        }
        case TSQueryErrorNodeType: {
            while (is_valid_identifier_char(pattern_chars[error_offset + end])) {
                end += 1;
            }

            char *node_chars = (char*)calloc(end + 1, sizeof(char));
            memcpy(node_chars, &pattern_chars[error_offset], end);
            jstring node = env->NewStringUTF(node_chars);
            jobject exception = NEW_OBJECT(TSQueryError$NodeType, (jint)row, (jint)column, node);
            env->Throw((jthrowable)exception);
            free((void*)node_chars);
            break;
        }
        case TSQueryErrorField: {
            while (is_valid_identifier_char(pattern_chars[error_offset + end])) {
                end += 1;
            }

            char *field_chars = (char*)calloc(end + 1, sizeof(char));
            memcpy(field_chars, &pattern_chars[error_offset], end);
            jstring field = env->NewStringUTF(field_chars);
            jobject exception = NEW_OBJECT(TSQueryError$Field, (jint)row, (jint)column, field);
            env->Throw((jthrowable)exception);
            free((void*)field_chars);
            break;
        }
        case TSQueryErrorStructure: {
            jobject exception = NEW_OBJECT(TSQueryError$Structure, (jint)row, (jint)column);
            env->Throw((jthrowable)exception);
            break;
        }
        default:
            UNREACHABLE();
    }

    env->ReleaseStringUTFChars(pattern, pattern_chars);
    return reinterpret_cast<jlong>(nullptr);
}

jlong JNICALL query_cursor() { 
    return reinterpret_cast<jlong>(ts_query_cursor_new()); 
}

void JNICALL query_delete(jlong query, jlong cursor) {
    ts_query_delete(reinterpret_cast<TSQuery*>(query));
    ts_query_cursor_delete(reinterpret_cast<TSQueryCursor*>(cursor));
}

jint JNICALL query_get_pattern_count(JNIEnv *env, jobject thiz) {
    TSQuery *self = GET_POINTER(TSQuery, thiz);
    return static_cast<jint>(ts_query_pattern_count(self));
}

jint JNICALL query_get_capture_count(JNIEnv *env, jobject thiz) {
    TSQuery *self = GET_POINTER(TSQuery, thiz);
    return static_cast<jint>(ts_query_capture_count(self));
}

jlong JNICALL query_get_timeout_micros(JNIEnv *env, jobject thiz) {
    TSQueryCursor *cursor = reinterpret_cast<TSQueryCursor*>(
        GET_FIELD(Long, thiz, TSQuery_cursor)
    );
    return static_cast<jlong>(ts_query_cursor_timeout_micros(cursor));
}

void JNICALL query_set_timeout_micros(JNIEnv *env, jobject thiz, jlong value) {
    TSQueryCursor *cursor = reinterpret_cast<TSQueryCursor*>(
        GET_FIELD(Long, thiz, TSQuery_cursor)
    );
    ts_query_cursor_set_timeout_micros(cursor, static_cast<uint64_t>(value));
    env->SetLongField(thiz, global_field_cache.TSQuery_timeoutMicros, value);
}

jint JNICALL query_get_match_limit(JNIEnv *env, jobject thiz) {
    TSQueryCursor *cursor = reinterpret_cast<TSQueryCursor*>(
        GET_FIELD(Long, thiz, TSQuery_cursor)
    );
    return static_cast<jint>(ts_query_cursor_match_limit(cursor));
}

void JNICALL query_set_match_limit(JNIEnv *env, jobject thiz, jint value) {
    TSQueryCursor *cursor = reinterpret_cast<TSQueryCursor*>(
        GET_FIELD(Long, thiz, TSQuery_cursor)
    );
    
    ts_query_cursor_set_match_limit(cursor, static_cast<uint32_t>(value));
    env->SetIntField(thiz, global_field_cache.TSQuery_matchLimit, value);
}

void JNICALL query_set_max_start_depth(JNIEnv *env, jobject thiz, jint value) {
    TSQueryCursor *cursor = reinterpret_cast<TSQueryCursor*>(
        GET_FIELD(Long, thiz, TSQuery_cursor)
    );
    ts_query_cursor_set_max_start_depth(cursor, static_cast<uint32_t>(value));
    env->SetIntField(thiz, global_field_cache.TSQuery_maxStartDepth, value);
}

jboolean JNICALL query_did_exceed_match_limit(JNIEnv *env, jobject thiz) {
    TSQueryCursor *cursor = reinterpret_cast<TSQueryCursor*>(
        GET_FIELD(Long, thiz, TSQuery_cursor)
    );
    return static_cast<jboolean>(ts_query_cursor_did_exceed_match_limit(cursor));
}

void JNICALL query_disable_pattern(JNIEnv *env, jobject thiz, jint index) {
    TSQuery *self = GET_POINTER(TSQuery, thiz);
    if (ts_query_pattern_count(self) > static_cast<uint32_t>(index)) {
        ts_query_disable_pattern(self, static_cast<uint32_t>(index));
    } else {
        const char *fmt = "Pattern index %u is out of bounds";
        char buffer[45] = {0};
        sprintf(buffer, fmt, static_cast<uint32_t>(index));
        THROW(IndexOutOfBoundsException, static_cast<const char*>(buffer));
    }
}

jint JNICALL query_start_byte_for_pattern(JNIEnv *env, jobject thiz, jint index) {
    TSQuery *self = GET_POINTER(TSQuery, thiz);
    if (ts_query_pattern_count(self) > static_cast<uint32_t>(index)) {
        return static_cast<jint>(
            ts_query_start_byte_for_pattern(self, static_cast<uint32_t>(index))
        );
    } 
    const char *fmt = "Pattern index %u is out of bounds";
    char buffer[45] = {0};
    sprintf(buffer, fmt, static_cast<uint32_t>(index));
    THROW(IndexOutOfBoundsException, static_cast<const char*>(buffer));
    // return invalid offset
    return -1;
}

jint JNICALL query_end_byte_for_pattern(JNIEnv *env, jobject thiz, jint index) {
    TSQuery *self = GET_POINTER(TSQuery, thiz);
    if (ts_query_pattern_count(self) > static_cast<uint32_t>(index)) {
        return static_cast<jint>(
            ts_query_end_byte_for_pattern(self, static_cast<uint32_t>(index))
        );
    } 
    const char *fmt = "Pattern index %u is out of bounds";
    char buffer[45] = {0};
    sprintf(buffer, fmt, static_cast<uint32_t>(index));
    THROW(IndexOutOfBoundsException, static_cast<const char*>(buffer));
    // return invalid offset
    return -1;
}

jboolean JNICALL query_is_pattern_rooted(JNIEnv *env, jobject thiz, jint index) {
    TSQuery *self = GET_POINTER(TSQuery, thiz);
    if (ts_query_pattern_count(self) > static_cast<uint32_t>(index)) {
        return static_cast<jboolean>(
            ts_query_is_pattern_rooted(self, static_cast<uint32_t>(index))
        );
    } 
    const char *fmt = "Pattern index %u is out of bounds";
    char buffer[45] = {0};
    sprintf(buffer, fmt, static_cast<uint32_t>(index));
    THROW(IndexOutOfBoundsException, static_cast<const char*>(buffer));
    // return invalid state
    return JNI_FALSE;
}

jboolean JNICALL query_is_pattern_non_local(JNIEnv *env, jobject thiz, jint index) {
    TSQuery *self = GET_POINTER(TSQuery, thiz);
    if (ts_query_pattern_count(self) > static_cast<uint32_t>(index)) {
        return static_cast<jboolean>(
            ts_query_is_pattern_non_local(self, static_cast<uint32_t>(index))
        );
    } 
    const char *fmt = "Pattern index %u is out of bounds";
    char buffer[45] = {0};
    sprintf(buffer, fmt, static_cast<uint32_t>(index));
    THROW(IndexOutOfBoundsException, static_cast<const char*>(buffer));
    // return invalid state
    return JNI_FALSE;
}

jint JNICALL query_string_count(JNIEnv *env, jobject thiz) {
    TSQuery *self = GET_POINTER(TSQuery, thiz);
    return static_cast<jint>(ts_query_string_count(self));
}

jstring JNICALL query_capture_name_for_id(JNIEnv *env, jobject thiz, jint index) {
    TSQuery *self = GET_POINTER(TSQuery, thiz);
    uint32_t length;
    const char *name = ts_query_capture_name_for_id(
        self, static_cast<uint32_t>(index), &length
    );
    return name ? env->NewStringUTF(name) : nullptr;
}

jstring JNICALL query_string_value_for_id(JNIEnv *env, jobject thiz, jint index) {
    TSQuery *self = GET_POINTER(TSQuery, thiz);
    uint32_t length;
    const char *name = ts_query_string_value_for_id(
        self, static_cast<uint32_t>(index), &length
    );
    return name ? env->NewStringUTF(name) : nullptr;
}

jboolean JNICALL query_native_is_pattern_guaranteed_at_step(JNIEnv *env, jobject thiz, jint offset) {
    TSQuery *self = GET_POINTER(TSQuery, thiz);
    return static_cast<jboolean>(
        ts_query_is_pattern_guaranteed_at_step(self, static_cast<uint32_t>(offset))
    );
}

void JNICALL query_native_disable_capture(JNIEnv *env, jobject thiz, jstring capture) {
    TSQuery *self = GET_POINTER(TSQuery, thiz);
    const char *capture_chars = env->GetStringUTFChars(capture, nullptr);
    uint32_t length = static_cast<uint32_t>(env->GetStringUTFLength(capture));
    ts_query_disable_capture(self, capture_chars, length);
    env->ReleaseStringUTFChars(capture, capture_chars);
}

void JNICALL query_native_set_byte_range(JNIEnv *env, jobject thiz, jint start, jint end) {
    TSQueryCursor *cursor = reinterpret_cast<TSQueryCursor*>(
        GET_FIELD(Long, thiz, TSQuery_cursor)
    );
    ts_query_cursor_set_byte_range(cursor, static_cast<uint32_t>(start), static_cast<uint32_t>(end));
}

void JNICALL query_native_set_point_range(JNIEnv *env, jobject thiz, jobject start, jobject end) {
    TSQueryCursor *cursor = reinterpret_cast<TSQueryCursor*>(
        GET_FIELD(Long, thiz, TSQuery_cursor)
    );
    TSPoint start_point = unmarshal_point(env, start), end_point = unmarshal_point(env, end);
    ts_query_cursor_set_point_range(cursor, start_point, end_point);
}

jobject JNICALL query_predicates_for_pattern(JNIEnv *env, jobject thiz, jint index) {
    TSQuery *self = GET_POINTER(TSQuery, thiz);
    uint32_t step_count;
    const TSQueryPredicateStep *steps = ts_query_predicates_for_pattern(
        self, static_cast<uint32_t>(index), &step_count
    );
    if (step_count == 0)
        return nullptr;

    jobject predicates = NEW_OBJECT(ArrayList, static_cast<jint>(step_count));
    for (uint32_t i = 0; i < step_count; ++i) {
        const jint values[2] = {(jint)steps[i].value_id, (jint)steps[i].type};
        jintArray predicate = (jintArray)env->NewIntArray(2);
        env->SetIntArrayRegion(predicate, 0, 2, values);
        CALL_METHOD(Boolean, predicates, ArrayList_add, predicate);
        env->DeleteLocalRef(predicate);
    }
    return predicates;
}

void JNICALL query_exec(JNIEnv *env, jobject thiz, jobject node) {
    TSQuery *self = GET_POINTER(TSQuery, thiz);
    TSQueryCursor *cursor = reinterpret_cast<TSQueryCursor*>(
        GET_FIELD(Long, thiz, TSQuery_cursor)
    );
    TSNode ts_node = unmarshal_node(env, node);
    ts_query_cursor_exec(cursor, self, ts_node);
}

jobject query_next_match(JNIEnv *env, jobject thiz, jobject tree) {
    TSQueryCursor *cursor = reinterpret_cast<TSQueryCursor*>(
        GET_FIELD(Long, thiz, TSQuery_cursor)
    );
    TSQueryMatch match;
    if (!ts_query_cursor_next_match(cursor, &match))
        return nullptr;

    jobject capture_names = GET_FIELD(Object, thiz, TSQuery_captureNames);
    // array list object
    jobject captures = NEW_OBJECT(ArrayList, (jint)match.capture_count);
    for (uint16_t i = 0; i < match.capture_count; ++i) {
        TSQueryCapture capture = match.captures[i];
        jobject node = marshal_node(env, &capture.node, tree);
        jobject name = CALL_METHOD(Object, capture_names, List_get, capture.index);
        if (env->ExceptionCheck())
            return nullptr;

        jobject capture_object = NEW_OBJECT(TSQueryCapture, node, name);
        CALL_METHOD(Boolean, captures, ArrayList_add, capture_object);
        env->DeleteLocalRef(capture_object);
        env->DeleteLocalRef(node);
        env->DeleteLocalRef(name);
        if (env->ExceptionCheck())
            return nullptr;
    }
    return NEW_OBJECT(TSQueryMatch, (jint)match.pattern_index, captures);
}

jobject JNICALL query_next_capture(JNIEnv *env, jobject thiz, jobject tree) {
    TSQueryCursor *cursor = reinterpret_cast<TSQueryCursor*>(
        GET_FIELD(Long, thiz, TSQuery_cursor)
    );
    uint32_t capture_index;
    TSQueryMatch match;
    if (!ts_query_cursor_next_capture(cursor, &match, &capture_index))
        return nullptr;

    jobject capture_names = GET_FIELD(Object, thiz, TSQuery_captureNames);
    jobject captures = NEW_OBJECT(ArrayList, (jint)match.capture_count);
    for (uint16_t i = 0; i < match.capture_count; ++i) {
        TSQueryCapture capture = match.captures[i];
        jobject node = marshal_node(env, &capture.node, tree);
        jobject name = CALL_METHOD(Object, capture_names, List_get, capture.index);
        if (env->ExceptionCheck())
            return nullptr;

        jobject capture_object = NEW_OBJECT(TSQueryCapture, node, name);
        CALL_METHOD(Boolean, captures, ArrayList_add, capture_object);
        env->DeleteLocalRef(capture_object);
        env->DeleteLocalRef(node);
        env->DeleteLocalRef(name);
        if (env->ExceptionCheck())
            return nullptr;
    }
    jobject match_object = NEW_OBJECT(TSQueryMatch, (jint)match.pattern_index, captures);
    jobject index = env->AllocObject(global_class_cache.UInt);
    env->SetIntField(index, global_field_cache.UInt_data, (jint)capture_index);
    return NEW_OBJECT(Pair, index, match_object);
}

extern const JNINativeMethod TSQuery_methods[] = {
    {"init", "(JLjava/lang/String;)J", (void *)&query_init},
    {"cursor", "()J", (void *)&query_cursor},
    {"delete", "(JJ)V", (void *)&query_delete},
    {"getPatternCount", "()I", (void *)&query_get_pattern_count},
    {"getCaptureCount", "()I", (void *)&query_get_capture_count},
    {"getTimeoutMicros", "()J", (void *)&query_get_timeout_micros},
    {"setTimeoutMicros", "(J)V", (void *)&query_set_timeout_micros},
    {"getMatchLimit", "()I", (void *)&query_get_match_limit},
    {"setMatchLimit", "(I)V", (void *)&query_set_match_limit},
    {"setMaxStartDepth", "(I)V", (void *)&query_set_max_start_depth},
    {"didExceedMatchLimit", "()Z", (void *)&query_did_exceed_match_limit},
    {"disablePattern", "(I)V", (void *)&query_disable_pattern},    
    {"startByteForPattern", "(I)I", (void *)&query_start_byte_for_pattern},
    {"endByteForPattern", "(I)I", (void *)&query_end_byte_for_pattern},
    {"isPatternRooted", "(I)Z", (void *)&query_is_pattern_rooted},
    {"isPatternNonLocal", "(I)Z", (void *)&query_is_pattern_non_local},
    {"stringCount", "()I", (void *)&query_string_count},
    {"captureNameForId", "(I)Ljava/lang/String;", (void *)&query_capture_name_for_id},
    {"stringValueForId", "(I)Ljava/lang/String;", (void *)&query_string_value_for_id},
    {"exec", "(L" PACKAGE "TSNode;)V", (void *)&query_exec},
    {"nextMatch", "(L" PACKAGE "TSTree;)L" PACKAGE "TSQueryMatch;", (void *)&query_next_match},
    {"nextCapture", "(L" PACKAGE "TSTree;)Lkotlin/Pair;", (void *)&query_next_capture},
    {"nativeSetByteRange", "(II)V", (void *)&query_native_set_byte_range},
    {"nativeSetPointRange", "(L" PACKAGE "TSPoint;L" PACKAGE "TSPoint;)V",
     (void *)&query_native_set_point_range},
    {"nativeDisableCapture", "(Ljava/lang/String;)V", (void *)&query_native_disable_capture},
    {"nativeIsPatternGuaranteedAtStep", "(I)Z",
     (void *)&query_native_is_pattern_guaranteed_at_step},
    {"predicatesForPattern", "(I)Ljava/util/List;", (void *)&query_predicates_for_pattern},
};

extern const size_t TSQuery_methods_size = sizeof TSQuery_methods / sizeof(JNINativeMethod);

#ifdef __cplusplus
}
#endif // __cplusplus
