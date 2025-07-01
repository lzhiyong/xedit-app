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

#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>

#include "ts_utils.h"

#ifdef __cplusplus
extern "C" {
#endif

// define global variables
static jbyteArray bytes = nullptr;
static jbyte *chunks = nullptr;

jlong JNICALL parser_init() {
    return reinterpret_cast<jlong>(ts_parser_new()); 
}

void JNICALL parser_delete(JNIEnv *env, jclass clazz, jlong parser) {
    TSParser *self = reinterpret_cast<TSParser*>(parser);
    TSLogger logger = ts_parser_logger(self);
    if (logger.payload != nullptr)
        env->DeleteGlobalRef((jobject)logger.payload);
    ts_parser_delete(self);
}

void JNICALL parser_reset(JNIEnv *env, jobject thiz) {
    TSParser *self = GET_POINTER(TSParser, thiz);
    ts_parser_reset(self);
}

void JNICALL parser_set_language(JNIEnv *env, jobject thiz, jobject value) {    
    TSParser *self = GET_POINTER(TSParser, thiz);
    TSLanguage *language = GET_POINTER(TSLanguage, value);
    ts_parser_set_language(self, language);
    env->SetObjectField(thiz, global_field_cache.TSParser_language, value);
}

void JNICALL parser_set_timeout_micros(JNIEnv *env, jobject thiz, jlong value) {
    TSParser *self = GET_POINTER(TSParser, thiz);
    ts_parser_set_timeout_micros(self, static_cast<uint64_t>(value));
    env->SetLongField(thiz, global_field_cache.TSParser_timeoutMicros, value);
}

void JNICALL parser_set_included_ranges(JNIEnv *env, jobject thiz, jobject value) {
    // get the parser
    TSParser *self = GET_POINTER(TSParser, thiz);
    // get the list size    
    uint32_t size = (uint32_t)CALL_METHOD_NO_ARGS(Int, value, List_size);
    
    // calloc native TSRange array
    TSRange *ranges = (TSRange *)calloc(size, sizeof(TSRange));
    for (uint32_t i = 0; i < size; ++i) {
        jobject range_object = CALL_METHOD(Object, value, List_get, (jint)i);
        *(ranges + i) = unmarshal_range(env, range_object);
        env->DeleteLocalRef(range_object);
    }
    
    if (ts_parser_set_included_ranges(self, ranges, size)) {
        env->SetObjectField(thiz, global_field_cache.TSParser_includedRanges, value);
    } else {
        THROW(IllegalArgumentException, "Included ranges must be in ascending order and not overlap");
    }
    // here requires free memory
    free((void*)ranges);
}

void JNICALL parser_set_cancelled_flag(JNIEnv *env, jobject thiz, jboolean value) {
    TSParser *self = GET_POINTER(TSParser, thiz);
    ts_parser_set_cancellation_flag(self, reinterpret_cast<size_t*>(&value));
    env->SetBooleanField(thiz, global_field_cache.TSParser_isCancelled, value);
}

void JNICALL parser_dot_graphs(JNIEnv* env, jobject thiz, jstring pathname) {
    TSParser *self = GET_POINTER(TSParser, thiz);
    const char *path =env->GetStringUTFChars(pathname, nullptr); 
    // here may requires MANAGE_EXTERNAL_STORAGE permission
    int fp = open(path, O_CREAT|O_TRUNC|O_RDWR, 0666);
    if(fp < 0) 
        LOGE("Error: %s\n", strerror(errno));
    else 
        ts_parser_print_dot_graphs(self, fp);
    
    env->ReleaseStringUTFChars(pathname, path);
}

// the callback param is kotlin lambda
void JNICALL parser_set_logger(JNIEnv *env, jobject thiz, jobject value) {    
    // convert lambda to C-Style function pointer
    auto callback = [](void *payload, TSLogType type, const char *buffer) {
        // local JNIEnv pointer
        JNIEnv *env = ::getEnv();
        // java enum TSLogType object
        jobject log_type = nullptr;       
        switch(type) {
            case TSLogTypeParse:
                log_type = GET_STATIC_FIELD(Object, TSLogType, TSLogType_PARSE);
                break;
            case TSLogTypeLex:
                log_type = GET_STATIC_FIELD(Object, TSLogType, TSLogType_LEX);
                break;
            default:
                UNREACHABLE();
        }
        
        // the message from native TSLogType
        jstring message = env->NewStringUTF(buffer);        
        // call the kotlin TSParser logger lambda     
        CALL_METHOD(Object, (jobject)payload, Function2_invoke, log_type, message);
    };
    
    TSParser *self = GET_POINTER(TSParser, thiz);
    TSLogger logger = ts_parser_logger(self);
    if (logger.payload != nullptr) {
        // release previous global logger reference
        env->DeleteGlobalRef(reinterpret_cast<jobject>(logger.payload));
    }
    // check the kotlin lambda object
    if (value != nullptr) {        
        logger.payload = reinterpret_cast<void*>(
            // new a global reference
            env->NewGlobalRef(value)
        );
        logger.log = callback;
    } else {
        logger.payload = nullptr;
        logger.log = nullptr;
    }
    // native set logger
    ts_parser_set_logger(self, logger);
    env->SetObjectField(thiz, global_field_cache.TSParser_logger, value);
}

jobject JNICALL parser_parse_string(
    JNIEnv *env, jobject thiz, jobject oldTree, jobject charset, jbyteArray byte_array
) { 
    // get the java TSLanguage object from java TSParser
    // note here language is jobect not native type
    jobject language = GET_FIELD(Object, thiz, TSParser_language);
    if (language == nullptr) {
        THROW(IllegalStateException, "The parser has no language assigned");
    }
    // native TSParser pointer
    TSParser *self = GET_POINTER(TSParser, thiz);  
    // text encoding, UTF-8 or UTF-16   
    TSInputEncoding encoding = static_cast<TSInputEncoding>(
        CALL_METHOD_NO_ARGS(Int, charset, TSInputEncoding_ordinal)
    );
    jbyte *byte_chars = env->GetByteArrayElements(byte_array, nullptr);
    size_t length = env->GetArrayLength(byte_array);
    
    // old native TSTree
    TSTree *old_tree = oldTree ? GET_POINTER(TSTree, oldTree) : nullptr;
    // new native TSTree    
    TSTree *new_tree = ts_parser_parse_string_encoding(
        self, old_tree, reinterpret_cast<const char*>(byte_chars), length, encoding
    );
    
    jstring source = nullptr;
    // note here requires to check the encoding
    if(encoding == TSInputEncodingUTF8) {
        source = env->NewStringUTF(reinterpret_cast<const char*>(byte_chars));
    } else {
        source = env->NewString(reinterpret_cast<const jchar*>(byte_chars), length / 2);
    }
    
    env->ReleaseByteArrayElements(byte_array, byte_chars, JNI_ABORT);
    // return the new java TSTree object
    return NEW_OBJECT(TSTree, reinterpret_cast<jlong>(new_tree), source, language);
}

jobject JNICALL parser_parse_function(
    JNIEnv *env, jobject thiz, jobject oldTree, jobject charset, jobject value
) {   
    // reinitialize jbytes when reparse
    ::bytes = nullptr; 
    ::chunks = nullptr;
    
    // get the java TSLanguage object from java TSParser
    // note here language is jobect not native type
    jobject language = GET_FIELD(Object, thiz, TSParser_language);
    if (language == nullptr) {
        THROW(IllegalStateException, "The parser has no language assigned");
    }    
    
    // convert lambda to C-Style function pointer
    auto callback = [](
        void *payload, uint32_t byte_index, TSPoint point, uint32_t *bytes_read
    ) -> const char* {
        // local JNIEnv pointer
        JNIEnv *env = ::getEnv();
        if(bytes != nullptr && chunks != nullptr) {
            // free the memory of the previous chunk
            env->ReleaseByteArrayElements(bytes, chunks, JNI_ABORT);
        }
        
        // local java TSPoint object
        jobject position = NEW_OBJECT(TSPoint, point.row, point.column);
        // convert kotlin Int to UInt
        jobject uint_index = env->AllocObject(global_class_cache.UInt);
        env->SetIntField(uint_index, global_field_cache.UInt_data, static_cast<jint>(byte_index));
        
        // call kotlin ParseCallback lambda
        bytes = reinterpret_cast<jbyteArray>(
            CALL_METHOD(Object, (jobject)payload, Function2_invoke, uint_index, position)
        );        
        chunks = env->GetByteArrayElements(bytes, nullptr);
        // reset bytes_read
        *bytes_read = env->GetArrayLength(bytes);
        
        env->DeleteLocalRef(uint_index);
        env->DeleteLocalRef(position);
        // return the native string             
        return reinterpret_cast<const char*>(chunks);
    };
    
    // native TSParser pointer
    TSParser *self = GET_POINTER(TSParser, thiz);
    // text encoding, UTF-8 or UTF-16
    TSInputEncoding encoding = static_cast<TSInputEncoding>(
        CALL_METHOD_NO_ARGS(Int, charset, TSInputEncoding_ordinal)
    );
    
    // old native TSTree
    TSTree *old_tree = oldTree ? GET_POINTER(TSTree, oldTree) : nullptr;
    // new native TSTree
    TSTree *new_tree = ts_parser_parse(self, old_tree, {(void*)value, callback, encoding});    
    // return the new java TSTree object
    return NEW_OBJECT(TSTree, reinterpret_cast<jlong>(new_tree), nullptr, language);
}

extern const JNINativeMethod TSParser_methods[] = {
    {"init", "()J", (void *)&parser_init},
    {"delete", "(J)V", (void *)&parser_delete},
    {"reset", "()V", (void *)&parser_reset},
    {"dotGraphs", "(Ljava/lang/String;)V", (void *)&parser_dot_graphs},
    {"setLanguage", "(L" PACKAGE "TSLanguage;)V", (void *)&parser_set_language},
    {"setIncludedRanges", "(Ljava/util/List;)V", (void *)&parser_set_included_ranges},
    {"setTimeoutMicros", "(J)V", (void *)&parser_set_timeout_micros},
    {"setCancelled", "(Z)V", (void *)&parser_set_cancelled_flag},
    {"setLogger", "(Lkotlin/jvm/functions/Function2;)V", (void *)&parser_set_logger},
    {"parse", "(L" PACKAGE "TSTree;L" PACKAGE "TSInputEncoding;[B)L" PACKAGE "TSTree;",
      (void *)&parser_parse_string},
    {"parse", "(L" PACKAGE "TSTree;L" PACKAGE "TSInputEncoding;Lkotlin/jvm/functions/Function2;)L" PACKAGE "TSTree;",
      (void *)&parser_parse_function}
};

extern const size_t TSParser_methods_size = sizeof TSParser_methods / sizeof(JNINativeMethod);


#ifdef __cplusplus
}
#endif // __cplusplus

