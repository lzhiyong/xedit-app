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

#include <ctype.h>
#include <malloc.h>

#include "ts_utils.h"
#include "ts_query_filter.h"

namespace {

using query_filter::Decision;

// What TSQueryCursor.self points to: the tree-sitter cursor, and what is kept with it
// between the calls (the predicates of its query, the text, a match the batched calls
// stopped at). Only the thread that runs the cursor uses it, so nothing here is locked:
// before, every match took a global mutex and two hash lookups to find this.
struct CursorHandle {
    TSQueryCursor *cursor = ts_query_cursor_new();
    query_filter::CursorState filter;

    // The decisions of the matches seen lately, by id. In capture order a match comes
    // once for each of its captures, and its predicates (a lua-match? on its text) need
    // not be decided again each time. Ids only grow within a run (exec).
    struct Seen {
        uint32_t id;
        Decision decision;
        bool valid;
    };
    static constexpr uint32_t SEEN = 64;
    Seen seen[SEEN] = {};

    // The match (and capture) handed out next, already taken from tree-sitter: one the
    // batched calls stopped at because Kotlin has to check it (or it did not fit), which
    // nextMatch / nextCapture then hand out as objects. Its captures are tree-sitter's,
    // valid until the cursor is advanced again.
    bool pending = false;
    bool pending_is_capture = false;
    TSQueryMatch pending_match{};
    uint32_t pending_capture = 0;
    Decision pending_decision = Decision::UNDECIDED;
    // the id of the match handed out last as an object (discardLastMatch)
    uint32_t last_match_id = 0;

    // For the progress callback, which tree-sitter calls while the cursor advances:
    // the Kotlin callback and the env of the call that advances it now. Set at each such
    // call, as a local reference of that call (one kept from exec would be dangling).
    JNIEnv *env = nullptr;
    jobject progress = nullptr;

    // The buffer of setText(String), kept for the next one (the cursor is run again,
    // TSQueryCursor.reset, rather than made anew for each block): no malloc per text.
    std::shared_ptr<query_filter::QueryText> own_text;

    ~CursorHandle() { ts_query_cursor_delete(cursor); }

    void reset_run() {
        pending = false;
        for (Seen &s : seen) s.valid = false;
    }
};

inline CursorHandle *handle_of(JNIEnv *env, jobject thiz) {
    return reinterpret_cast<CursorHandle *>(GET_FIELD(Long, thiz, TSQueryCursor_self));
}

// Before the cursor is advanced: what its progress callback needs (see CursorHandle).
inline CursorHandle *advancing(JNIEnv *env, jobject thiz) {
    CursorHandle *handle = handle_of(env, thiz);
    handle->env = env;
    handle->progress = GET_FIELD(Object, thiz, TSQueryCursor_progressCallback);
    return handle;
}

// After it: the local reference goes with the call.
inline void advanced(CursorHandle *handle) {
    if (handle->progress != nullptr) handle->env->DeleteLocalRef(handle->progress);
    handle->progress = nullptr;
}

Decision decide_capture(CursorHandle *handle, const TSQueryMatch &match) {
    CursorHandle::Seen &slot = handle->seen[match.id % CursorHandle::SEEN];
    if (slot.valid && slot.id == match.id) return slot.decision;
    Decision decision = query_filter::decide(handle->filter, match);
    slot = {match.id, decision, true};
    return decision;
}

// The next capture whose match is not left out here, and what was decided of it.
bool next_capture(CursorHandle *handle, TSQueryMatch *match, uint32_t *index, Decision *decision) {
    if (handle->pending) {
        handle->pending = false;
        if (handle->pending_is_capture) {
            *match = handle->pending_match;
            *index = handle->pending_capture;
            *decision = handle->pending_decision;
            return true;
        }
    }
    for (;;) {
        if (!ts_query_cursor_next_capture(handle->cursor, match, index)) return false;
        *decision = decide_capture(handle, *match);
        if (*decision != Decision::REJECT) return true;
        // its other captures will not come either, as Neovim does
        ts_query_cursor_remove_match(handle->cursor, match->id);
    }
}

// The next match that is not left out here, and what was decided of it.
bool next_match(CursorHandle *handle, TSQueryMatch *match, Decision *decision) {
    if (handle->pending) {
        handle->pending = false;
        if (!handle->pending_is_capture) {
            *match = handle->pending_match;
            *decision = handle->pending_decision;
            return true;
        }
    }
    do {
        if (!ts_query_cursor_next_match(handle->cursor, match)) return false;
        *decision = query_filter::decide(handle->filter, *match);
    } while (*decision == Decision::REJECT);
    return true;
}

void keep_pending(CursorHandle *handle, const TSQueryMatch &match, bool is_capture, uint32_t capture, Decision decision) {
    handle->pending = true;
    handle->pending_is_capture = is_capture;
    handle->pending_match = match;
    handle->pending_capture = capture;
    handle->pending_decision = decision;
}

// The pattern index of [match] for TSQueryMatch, with the top bit set when its predicates
// were all decided natively and hold (TSQueryMatch.predicatesDecided).
inline jint pattern_of(const TSQueryMatch &match, Decision decision) {
    uint32_t raw = match.pattern_index & 0x7FFFFFFFu;
    if (decision == Decision::ACCEPT) raw |= 0x80000000u;
    return (jint)raw;
}

// [match] as a TSQueryMatch object (its captures with their nodes and names)
jobject marshal_match(JNIEnv *env, const TSQueryMatch &match, Decision decision, jobject capture_names, jobject tree) {
    jobject captures = NEW_OBJECT(ArrayList, (jint)match.capture_count);
    for (uint16_t i = 0; i < match.capture_count; ++i) {
        TSQueryCapture capture = match.captures[i];
        jobject node = marshal_node(env, &capture.node, tree);
        jobject name = CALL_METHOD(Object, capture_names, List_get, (jint)capture.index);
        if (env->ExceptionCheck())
            return nullptr;

        jobject capture_obj = NEW_OBJECT(TSQueryCapture, node, name);
        CALL_METHOD(Boolean, captures, ArrayList_add, capture_obj);
        env->DeleteLocalRef(capture_obj);
        env->DeleteLocalRef(node);
        env->DeleteLocalRef(name);
        if (env->ExceptionCheck())
            return nullptr;
    }
    jobject match_obj = NEW_OBJECT(TSQueryMatch, pattern_of(match, decision), captures);
    env->DeleteLocalRef(captures);
    return match_obj;
}

bool query_progress_callback(TSQueryCursorState *state) {
    CursorHandle *handle = static_cast<CursorHandle *>(state->payload);
    JNIEnv *env = handle->env;
    if (handle->progress == nullptr || env == nullptr || env->ExceptionCheck()) return false;
    jobject offset = env->AllocObject(global_class_cache.UInt);
    env->SetIntField(offset, global_field_cache.UInt_data, (jint)state->current_byte_offset);
    jobject result = CALL_METHOD(Object, handle->progress, Function1_invoke, offset);
    env->DeleteLocalRef(offset);
    if (env->ExceptionCheck() || result == nullptr) return true;
    bool halt = (bool)env->GetBooleanField(result, global_field_cache.Boolean_value);
    env->DeleteLocalRef(result);
    return halt;
}

// The most records a batched call writes: on the stack, then copied to Kotlin at once.
constexpr jint BATCH_INTS = 1024;
// a record of nextCaptures: pattern, capture (index into the capture names), start, end
constexpr jint CAPTURE_INTS = 4;
// a capture in a record of nextMatches: capture, start, end
constexpr jint MATCH_CAPTURE_INTS = 3;
// the same with its node: then the rest of its context and its id (low, high)
constexpr jint MATCH_NODE_CAPTURE_INTS = 8;
// the record has to be checked in Kotlin (its match comes next as an object)
constexpr uint32_t NEEDS_CHECK = 0x80000000u;

} // namespace

#ifdef __cplusplus
extern "C" {
#endif

jlong JNICALL query_cursor_init() {
    return reinterpret_cast<jlong>(new CursorHandle());
}

void JNICALL query_cursor_delete(jlong cursor) {
    delete reinterpret_cast<CursorHandle *>(cursor);
}

jint JNICALL query_cursor_get_match_limit(JNIEnv *env, jobject thiz) {
    return static_cast<jint>(ts_query_cursor_match_limit(handle_of(env, thiz)->cursor));
}

void JNICALL query_cursor_set_match_limit(JNIEnv *env, jobject thiz, jint value) {
    ts_query_cursor_set_match_limit(handle_of(env, thiz)->cursor, static_cast<uint32_t>(value));
    env->SetIntField(thiz, global_field_cache.TSQueryCursor_matchLimit, value);
}

void JNICALL query_cursor_set_max_start_depth(JNIEnv *env, jobject thiz, jint value) {
    ts_query_cursor_set_max_start_depth(handle_of(env, thiz)->cursor, static_cast<uint32_t>(value));
    env->SetIntField(thiz, global_field_cache.TSQueryCursor_maxStartDepth, value);
}

jboolean JNICALL query_cursor_did_exceed_match_limit(JNIEnv *env, jobject thiz) {
    return static_cast<jboolean>(ts_query_cursor_did_exceed_match_limit(handle_of(env, thiz)->cursor));
}

// Registered as (II)Z: the result has to be returned, or the Kotlin side reads
// whatever happens to be in the return register.
jboolean JNICALL query_cursor_native_set_byte_range(JNIEnv *env, jobject thiz, jint start, jint end) {
    return static_cast<jboolean>(ts_query_cursor_set_byte_range(
        handle_of(env, thiz)->cursor, static_cast<uint32_t>(start), static_cast<uint32_t>(end)
    ));
}

// Registered as (II)Z, like the byte range. An end of 0 is no end (tree-sitter's).
jboolean JNICALL query_cursor_native_set_containing_byte_range(JNIEnv *env, jobject thiz, jint start, jint end) {
    return static_cast<jboolean>(ts_query_cursor_set_containing_byte_range(
        handle_of(env, thiz)->cursor, static_cast<uint32_t>(start), static_cast<uint32_t>(end)
    ));
}

// The text of the document from start_byte on (UTF-16): the matches whose text
// predicates certainly fail are left out here, not handed to Kotlin (ts_query_filter.h).
void JNICALL query_cursor_native_set_text(JNIEnv *env, jobject thiz, jint start_byte, jstring text) {
    CursorHandle *handle = handle_of(env, thiz);
    // (the buffer of a text set before is used again; it is this cursor's alone)
    if (!handle->own_text) handle->own_text = std::make_shared<query_filter::QueryText>();
    query_filter::QueryText &own = *handle->own_text;
    jsize length = env->GetStringLength(text);
    own.text.resize(static_cast<size_t>(length));
    if (length > 0) env->GetStringRegion(text, 0, length, reinterpret_cast<jchar *>(&own.text[0]));
    own.start_byte = static_cast<uint32_t>(start_byte);
    handle->filter.text = handle->own_text;
}

// A TSQueryText (its self: a std::shared_ptr<QueryText> *), read in place: nothing is
// copied here, the cursor holds a reference to it.
void JNICALL query_cursor_native_set_shared_text(JNIEnv *env, jobject thiz, jlong text) {
    handle_of(env, thiz)->filter.text = *reinterpret_cast<std::shared_ptr<query_filter::QueryText> *>(text);
}

jboolean JNICALL query_cursor_native_set_point_range(JNIEnv *env, jobject thiz, jobject start, jobject end) {
    TSPoint start_point = unmarshal_point(env, start), end_point = unmarshal_point(env, end);
    return static_cast<jboolean>(ts_query_cursor_set_point_range(handle_of(env, thiz)->cursor, start_point, end_point));
}

void JNICALL query_cursor_exec(
    JNIEnv *env, jobject thiz, jlong query, jobject node, jobject progress_callback, jboolean tree_has_text
) {
    CursorHandle *handle = handle_of(env, thiz);
    TSNode ts_node = unmarshal_node(env, node);
    const TSQuery *ts_query = reinterpret_cast<const TSQuery *>(query);
    // the predicates decided here (see ts_query_filter.h): looked up once, for every match
    handle->filter.query = ts_query;
    handle->filter.filters = query_filter::filters_of(ts_query);
    handle->filter.tree_has_text = tree_has_text;
    // the text was of the last run: it may not be of this node, nor of this tree
    handle->filter.text.reset();
    handle->reset_run();
    if (progress_callback != nullptr) {
        TSQueryCursorOptions options = {
            .payload = (void *)handle,
            .progress_callback = query_progress_callback,
        };
        ts_query_cursor_exec_with_options(handle->cursor, ts_query, ts_node, &options);
    } else {
        ts_query_cursor_exec(handle->cursor, ts_query, ts_node);
    }
}

jobject JNICALL query_cursor_next_capture(JNIEnv *env, jobject thiz, jobject capture_names, jobject tree) {
    CursorHandle *handle = advancing(env, thiz);
    TSQueryMatch match;
    uint32_t capture_index;
    Decision decision;
    bool found = next_capture(handle, &match, &capture_index, &decision);
    advanced(handle);
    if (!found) return nullptr;
    handle->last_match_id = match.id;

    jobject match_obj = marshal_match(env, match, decision, capture_names, tree);
    if (match_obj == nullptr) return nullptr;
    jobject index = env->AllocObject(global_class_cache.UInt);
    env->SetIntField(index, global_field_cache.UInt_data, (jint)capture_index);
    return NEW_OBJECT(Pair, index, match_obj);
}

jobject JNICALL query_cursor_next_match(JNIEnv *env, jobject thiz, jobject capture_names, jobject tree) {
    CursorHandle *handle = advancing(env, thiz);
    TSQueryMatch match;
    Decision decision;
    bool found = next_match(handle, &match, &decision);
    advanced(handle);
    if (!found) return nullptr;
    handle->last_match_id = match.id;
    return marshal_match(env, match, decision, capture_names, tree);
}

// The captures from here on, as records of CAPTURE_INTS ints in [buffer]: the pattern,
// the capture (an index into the capture names), its start and end byte. No object is
// made for them: a capture handed over as objects costs its node, the int[] of the
// node, a capture, a list, a match, a pair, a boxed index, and, for a match of n
// captures, n times as many, once for each of its captures.
//
// The predicates of the records' matches all hold, decided here, but for the last
// record when its pattern has NEEDS_CHECK set: then Kotlin has to check its predicates,
// on its match, which the next nextCapture hands out. 0: no more captures.
jint JNICALL query_cursor_next_captures(JNIEnv *env, jobject thiz, jintArray buffer) {
    CursorHandle *handle = advancing(env, thiz);
    jint room = env->GetArrayLength(buffer);
    if (room > BATCH_INTS) room = BATCH_INTS;
    room -= room % CAPTURE_INTS;
    jint out[BATCH_INTS];
    jint used = 0;
    TSQueryMatch match;
    uint32_t capture_index;
    Decision decision;
    while (used < room && next_capture(handle, &match, &capture_index, &decision)) {
        const TSNode &node = match.captures[capture_index].node;
        jint *record = out + used;
        record[0] = (jint)(match.pattern_index & 0x7FFFFFFFu);
        record[1] = (jint)match.captures[capture_index].index;
        record[2] = (jint)ts_node_start_byte(node);
        record[3] = (jint)ts_node_end_byte(node);
        used += CAPTURE_INTS;
        if (decision != Decision::ACCEPT) {
            record[0] = (jint)((uint32_t)record[0] | NEEDS_CHECK);
            keep_pending(handle, match, true, capture_index, decision);
            break;
        }
    }
    advanced(handle);
    if (used > 0) env->SetIntArrayRegion(buffer, 0, used, out);
    return used / CAPTURE_INTS;
}

// The matches from here on, as records in [buffer]: the pattern, the number n of
// captures, then for each capture its index into the capture names, its start and end
// byte (2 + 3n ints). Returns the number of ints written, 0 when there are no more.
//
// With [with_nodes], each capture also has what makes its node, for Kotlin to build the
// ones it wants without a call back here (2 + 8n ints): context[1..3] (context[0] is the
// start byte), and the id, low and high ints. The tree of the node is the cursor's.
//
// As with nextCaptures, NEEDS_CHECK on the pattern of the last record: its match comes
// next from nextMatch, for Kotlin to check. A match too large for the buffer comes as a
// record with n = -1 (and nothing else): nextMatch then hands it out, whole.
jint JNICALL query_cursor_next_matches(JNIEnv *env, jobject thiz, jintArray buffer, jboolean with_nodes) {
    CursorHandle *handle = advancing(env, thiz);
    jint room = env->GetArrayLength(buffer);
    if (room > BATCH_INTS) room = BATCH_INTS;
    const jint capture_ints = with_nodes ? MATCH_NODE_CAPTURE_INTS : MATCH_CAPTURE_INTS;
    jint out[BATCH_INTS];
    jint used = 0;
    TSQueryMatch match;
    Decision decision;
    while (used + 2 <= room && next_match(handle, &match, &decision)) {
        jint size = 2 + capture_ints * (jint)match.capture_count;
        uint32_t pattern = match.pattern_index & 0x7FFFFFFFu;
        if (decision != Decision::ACCEPT) pattern |= NEEDS_CHECK;
        if (used + size > room) {
            keep_pending(handle, match, false, 0, decision);
            if (used == 0) {
                // it would not fit in any call
                out[0] = (jint)(pattern | NEEDS_CHECK);
                out[1] = -1;
                used = 2;
            }
            break;
        }
        jint *record = out + used;
        record[0] = (jint)pattern;
        record[1] = (jint)match.capture_count;
        for (uint16_t i = 0; i < match.capture_count; ++i) {
            const TSQueryCapture &capture = match.captures[i];
            jint *ints = record + 2 + capture_ints * i;
            ints[0] = (jint)capture.index;
            ints[1] = (jint)ts_node_start_byte(capture.node);
            ints[2] = (jint)ts_node_end_byte(capture.node);
            if (with_nodes) {
                uint64_t id = reinterpret_cast<uintptr_t>(capture.node.id);
                ints[3] = (jint)capture.node.context[1];
                ints[4] = (jint)capture.node.context[2];
                ints[5] = (jint)capture.node.context[3];
                ints[6] = (jint)(uint32_t)id;
                ints[7] = (jint)(uint32_t)(id >> 32);
            }
        }
        used += size;
        if (decision != Decision::ACCEPT) {
            keep_pending(handle, match, false, 0, decision);
            break;
        }
    }
    advanced(handle);
    if (used > 0) env->SetIntArrayRegion(buffer, 0, used, out);
    return used;
}

// The match handed out last as an object is left out (its predicates failed in
// Kotlin): its other captures do not come either.
void JNICALL query_cursor_discard_last_match(JNIEnv *env, jobject thiz) {
    CursorHandle *handle = handle_of(env, thiz);
    ts_query_cursor_remove_match(handle->cursor, handle->last_match_id);
}

// The match handed out last as an object holds (its predicates were checked in Kotlin):
// its other captures come without being checked again.
void JNICALL query_cursor_accept_last_match(JNIEnv *env, jobject thiz) {
    CursorHandle *handle = handle_of(env, thiz);
    CursorHandle::Seen &slot = handle->seen[handle->last_match_id % CursorHandle::SEEN];
    slot = {handle->last_match_id, Decision::ACCEPT, true};
}

extern const JNINativeMethod TSQueryCursor_methods[] = {
    {"init", "()J", (void *)&query_cursor_init},
    {"delete", "(J)V", (void *)&query_cursor_delete},
    {"getMatchLimit", "()I", (void *)&query_cursor_get_match_limit},
    {"setMatchLimit", "(I)V", (void *)&query_cursor_set_match_limit},
    {"setMaxStartDepth", "(I)V", (void *)&query_cursor_set_max_start_depth},
    {"didExceedMatchLimit", "()Z", (void *)&query_cursor_did_exceed_match_limit},
    {"nativeSetByteRange", "(II)Z", (void *)&query_cursor_native_set_byte_range},
    {"nativeSetContainingByteRange", "(II)Z", (void *)&query_cursor_native_set_containing_byte_range},
    {"nativeSetText", "(ILjava/lang/String;)V", (void *)&query_cursor_native_set_text},
    {"nativeSetSharedText", "(J)V", (void *)&query_cursor_native_set_shared_text},
    {"nativeSetPointRange", "(L" PACKAGE "TSPoint;L" PACKAGE "TSPoint;)Z",
     (void *)&query_cursor_native_set_point_range},
    {"nextMatch", "(Ljava/util/List;L" PACKAGE "TSTree;)L" PACKAGE "TSQueryMatch;",
     (void *)&query_cursor_next_match},
    {"nextCapture", "(Ljava/util/List;L" PACKAGE "TSTree;)Lkotlin/Pair;",
     (void *)&query_cursor_next_capture},
    {"nativeNextCaptures", "([I)I", (void *)&query_cursor_next_captures},
    {"nativeNextMatches", "([IZ)I", (void *)&query_cursor_next_matches},
    {"discardLastMatch", "()V", (void *)&query_cursor_discard_last_match},
    {"acceptLastMatch", "()V", (void *)&query_cursor_accept_last_match},
    {"exec", "(JL" PACKAGE "TSNode;Lkotlin/jvm/functions/Function1;Z)V", (void *)&query_cursor_exec}
};

extern const size_t TSQueryCursor_methods_size = sizeof TSQueryCursor_methods / sizeof(JNINativeMethod);

// TSQueryText: a text for the predicates of any number of cursors (see QueryText). Its
// self is a std::shared_ptr of its own: a cursor given it holds another, so that closing
// the TSQueryText never frees a text a cursor still reads.

jlong JNICALL query_text_init() {
    return reinterpret_cast<jlong>(
        new std::shared_ptr<query_filter::QueryText>(std::make_shared<query_filter::QueryText>())
    );
}

void JNICALL query_text_delete(jlong self) {
    delete reinterpret_cast<std::shared_ptr<query_filter::QueryText> *>(self);
}

// The chars [start, start + length) of [text], from the byte [start_byte] of the
// document on. The buffer is used again: no malloc once it is large enough.
void JNICALL query_text_native_set(JNIEnv *env, jclass clazz, jlong self, jint start_byte, jstring text, jint start, jint length) {
    query_filter::QueryText &target = **reinterpret_cast<std::shared_ptr<query_filter::QueryText> *>(self);
    target.text.resize(static_cast<size_t>(length));
    if (length > 0) env->GetStringRegion(text, start, length, reinterpret_cast<jchar *>(&target.text[0]));
    target.start_byte = static_cast<uint32_t>(start_byte);
}

extern const JNINativeMethod TSQueryText_methods[] = {
    {"init", "()J", (void *)&query_text_init},
    {"delete", "(J)V", (void *)&query_text_delete},
    {"nativeSet", "(JILjava/lang/String;II)V", (void *)&query_text_native_set}
};

extern const size_t TSQueryText_methods_size = sizeof TSQueryText_methods / sizeof(JNINativeMethod);

#ifdef __cplusplus
}
#endif // __cplusplus
