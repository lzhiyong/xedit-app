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

#include <stdio.h>

#include "ts_language.h"
#include "ts_utils.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Get the TSLanguage pointer by the grammar function name
 */
jlong JNICALL language_resolve(JNIEnv* env, jclass clazz, jstring name) {
    const char *func_name = env->GetStringUTFChars(name, nullptr);
    env->ReleaseStringUTFChars(name, func_name);
    
    extern struct TSFunction __start_ss;
    extern struct TSFunction __stop_ss;
    
    for (struct TSFunction *fn = &__start_ss; fn < &__stop_ss; fn++) {
        // search function in ss section
        if(strcmp(fn->name, func_name) == 0) {
            return reinterpret_cast<jlong>(fn->invoke());
        }
    }
    // invalid pointer
    return reinterpret_cast<jlong>(nullptr);
}

jlong JNICALL language_copy(jlong language) {
    return reinterpret_cast<jlong>(
        ts_language_copy(reinterpret_cast<TSLanguage*>(language))
    );
}

jint JNICALL language_get_version(JNIEnv* env, jobject thiz) {
    TSLanguage *self = GET_POINTER(TSLanguage, thiz);
    return ts_language_version(self);
}

jint JNICALL language_get_symbol_count(JNIEnv* env, jobject thiz) {
    TSLanguage *self = GET_POINTER(TSLanguage, thiz);
    return ts_language_symbol_count(self);
}

jint JNICALL language_get_state_count(JNIEnv* env, jobject thiz) {
    TSLanguage *self = GET_POINTER(TSLanguage, thiz);
    return ts_language_state_count(self);
}

jint JNICALL language_get_field_count(JNIEnv* env, jobject thiz) {
    TSLanguage *self = GET_POINTER(TSLanguage, thiz);
    return ts_language_field_count(self);
}

jstring JNICALL language_symbol_name(JNIEnv *env, jobject thiz, jshort symbol) {
    TSLanguage *self = GET_POINTER(TSLanguage, thiz);
    const char *name = ts_language_symbol_name(self, static_cast<uint16_t>(symbol));
    return env->NewStringUTF(name);
}

jshort JNICALL language_symbol_for_name(JNIEnv *env, jobject thiz, jstring name, jboolean isNamed) {
    TSLanguage *self = GET_POINTER(TSLanguage, thiz);
    const char *symbol_name = env->GetStringUTFChars(name, nullptr);
    
    TSSymbol symbol = ts_language_symbol_for_name(
        self, 
        symbol_name, 
        strlen(symbol_name), 
        isNamed
    );
    
    env->ReleaseStringUTFChars(name, symbol_name);
    return static_cast<jshort>(symbol);
}

jboolean JNICALL language_is_named(JNIEnv *env, jobject thiz, jshort symbol) {
    TSLanguage *self = GET_POINTER(TSLanguage, thiz);
    TSSymbolType symbol_type = ts_language_symbol_type(self, symbol);
    return (symbol_type == TSSymbolTypeRegular);
}

jboolean JNICALL language_is_visible(JNIEnv *env, jobject thiz, jshort symbol) {
    TSLanguage *self = GET_POINTER(TSLanguage, thiz);
    TSSymbolType symbol_type = ts_language_symbol_type(self, symbol);
    return (symbol_type <= TSSymbolTypeAnonymous);
}

jboolean JNICALL language_is_supertype(JNIEnv *env, jobject thiz, jshort symbol) {
    TSLanguage *self = GET_POINTER(TSLanguage, thiz);
    TSSymbolType symbol_type = ts_language_symbol_type(self, symbol);
    return (jboolean)(symbol_type == TSSymbolTypeSupertype);
}

jstring JNICALL language_field_name_for_id(JNIEnv *env, jobject thiz, jshort id) {
    TSLanguage *self = GET_POINTER(TSLanguage, thiz);
    const char *name = ts_language_field_name_for_id(self, static_cast<uint16_t>(id));
    return name ? env->NewStringUTF(name) : nullptr;
}

jint JNICALL language_field_id_for_name(JNIEnv *env, jobject thiz, jstring name) {
    TSLanguage *self = GET_POINTER(TSLanguage, thiz);
    const char *field_name = env->GetStringUTFChars(name, nullptr);
    
    TSFieldId field = ts_language_field_id_for_name(self, field_name, strlen(field_name));   
    env->ReleaseStringUTFChars(name, field_name);
    return static_cast<jint>(field);
}

jshort JNICALL language_next_state(JNIEnv *env, jobject thiz, jshort state, jshort symbol) {
    TSLanguage *self = GET_POINTER(TSLanguage, thiz);
    return ts_language_next_state(self, static_cast<uint16_t>(state), static_cast<uint16_t>(symbol));
}

void JNICALL language_check_version(JNIEnv *env, jobject thiz) {
    TSLanguage *self = GET_POINTER(TSLanguage, thiz);
    uint32_t version = ts_language_version(self);
    if (version < TREE_SITTER_MIN_COMPATIBLE_LANGUAGE_VERSION ||
       version > TREE_SITTER_LANGUAGE_VERSION
    ) {
        const char *fmt = "Incompatible language version %u. Must be between %u and %u.";
        char buffer[70] = {0}; // length(fmt) + digits(UINT32_MAX)
        sprintf(buffer, fmt, version, TREE_SITTER_MIN_COMPATIBLE_LANGUAGE_VERSION,
                  TREE_SITTER_LANGUAGE_VERSION);
        THROW(IllegalArgumentException, static_cast<const char *>(buffer));
    }
}

jobject JNICALL language_symbol_type(JNIEnv* env, jobject thiz, jshort symbol) {
    TSLanguage *self = GET_POINTER(TSLanguage, thiz);
    TSSymbolType symbol_type = ts_language_symbol_type(self, static_cast<uint16_t>(symbol));
    
    jobject symbol_object = nullptr;
    switch(symbol_type) {
        case TSSymbolTypeRegular:
            symbol_object = GET_STATIC_FIELD(Object, TSSymbolType, TSSymbolType_REGULAR);
            break;
        case TSSymbolTypeAnonymous:
            symbol_object = GET_STATIC_FIELD(Object, TSSymbolType, TSSymbolType_ANONYMOUS);            
            break;
        case TSSymbolTypeAuxiliary:
            symbol_object = GET_STATIC_FIELD(Object, TSSymbolType, TSSymbolType_AUXILIARY);
            break;
        default:
            UNREACHABLE();
    }
    return symbol_object;
}

extern const JNINativeMethod TSLanguage_methods[] = {
    {"copy", "(J)J", (void *)&language_copy},
    {"getVersion", "()I", (void *)&language_get_version},
    {"getSymbolCount", "()I", (void *)&language_get_symbol_count},
    {"getStateCount", "()I", (void *)&language_get_state_count},
    {"getFieldCount", "()I", (void *)&language_get_field_count},
    {"symbolName", "(S)Ljava/lang/String;", (void *)&language_symbol_name},
    {"symbolForName", "(Ljava/lang/String;Z)S", (void *)&language_symbol_for_name},
    {"isNamed", "(S)Z", (void *)&language_is_named},
    {"isVisible", "(S)Z", (void *)&language_is_visible},
    {"isSupertype", "(S)Z", (void *)&language_is_supertype},
    {"fieldNameForId", "(S)Ljava/lang/String;", (void *)&language_field_name_for_id},
    {"fieldIdForName", "(Ljava/lang/String;)S", (void *)&language_field_id_for_name},
    {"nextState", "(SS)S", (void *)&language_next_state},
    {"checkVersion", "()V", (void *)&language_check_version},
    {"symbolType", "(S)L" PACKAGE "TSSymbolType;", (void *)&language_symbol_type},
    {"resolve", "(Ljava/lang/String;)J", (void *)&language_resolve},
};

extern const size_t TSLanguage_methods_size = sizeof TSLanguage_methods / sizeof(JNINativeMethod);

#ifdef __cplusplus
}
#endif // __cplusplus

