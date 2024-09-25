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

jlong JNICALL lookahead_iterator_init(jlong language, jshort state) {
    auto lookahead = ts_lookahead_iterator_new(
        reinterpret_cast<TSLanguage*>(language), 
        static_cast<uint16_t>(state)
    );
    return reinterpret_cast<jlong>(lookahead);
}

void JNICALL lookahead_iterator_delete(jlong pointer) {
    ts_lookahead_iterator_delete(
        reinterpret_cast<TSLookaheadIterator*>(pointer)
    );
}

jobject JNICALL lookahead_iterator_get_language(JNIEnv *env, jobject thiz) {
    auto lookahead = GET_POINTER(TSLookaheadIterator, thiz);
    auto language = ts_lookahead_iterator_language(lookahead);
    // create java TSLanguage object
    jobject lang_object = env->AllocObject(global_class_cache.TSLanguage);
    env->SetLongField(
        lang_object, 
        global_field_cache.TSLanguage_pointer, 
        reinterpret_cast<jlong>(language)
    );
    return lang_object;
}

jshort JNICALL lookahead_iterator_get_current_symbol(JNIEnv *env, jobject thiz) {
    auto lookahead = GET_POINTER(TSLookaheadIterator, thiz);
    return static_cast<jshort>(ts_lookahead_iterator_current_symbol(lookahead));
}

jstring JNICALL lookahead_iterator_get_current_symbol_name(JNIEnv *env, jobject thiz) {
    auto lookahead = GET_POINTER(TSLookaheadIterator, thiz);
    const char *name = ts_lookahead_iterator_current_symbol_name(lookahead);
    return env->NewStringUTF(name);
}

jboolean JNICALL lookahead_iterator_reset(JNIEnv *env, jobject thiz, jshort state, jobject language) {
    auto lookahead = GET_POINTER(TSLookaheadIterator, thiz);
    if (language == nullptr) {
        auto result = ts_lookahead_iterator_reset_state(
            lookahead, 
            static_cast<uint16_t>(state)
        );
        return static_cast<jboolean>(result);
    }
    
    // native TSLanguage
    auto ts_language = GET_POINTER(TSLanguage, language);
    auto result = ts_lookahead_iterator_reset(
        lookahead, 
        ts_language, 
        static_cast<uint16_t>(state)
    );
    return static_cast<jboolean>(result);
}

jboolean JNICALL lookahead_iterator_next(JNIEnv *env, jobject thiz) {
    auto lookahead = GET_POINTER(TSLookaheadIterator, thiz);
    auto result = ts_lookahead_iterator_next(lookahead);
    return static_cast<jboolean>(result);
}

extern const JNINativeMethod TSLookaheadIterator_methods[] = {
    {"init", "(JS)J", (void *)&lookahead_iterator_init},
    {"delete", "(J)V", (void *)&lookahead_iterator_delete},
    {"getLanguage", "()L" PACKAGE "TSLanguage;", (void *)&lookahead_iterator_get_language},
    {"getCurrentSymbol", "()S", (void *)&lookahead_iterator_get_current_symbol},
    {"getCurrentSymbolName", "()Ljava/lang/String;",
     (void *)&lookahead_iterator_get_current_symbol_name},
    {"reset", "(SL" PACKAGE "TSLanguage;)Z", (void *)&lookahead_iterator_reset},
    {"next", "()Z", (void *)&lookahead_iterator_next},
};

extern const size_t TSLookaheadIterator_methods_size =
    sizeof TSLookaheadIterator_methods / sizeof(JNINativeMethod);

#ifdef __cplusplus
}
#endif

