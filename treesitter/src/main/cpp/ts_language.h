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

#ifndef __TS_LANGUAGE_H__
#define __TS_LANGUAGE_H__

#include <tree_sitter/api.h>

#ifdef __cplusplus
extern "C" {
#endif

struct TSFunction {
    // language function pointer
    TSLanguage* (*invoke) (void);
    // language function name
    const char* name;
};

// map tree-sitter languages to section ss
#define reflect(x) __attribute__((section("ss"))) \
    struct TSFunction __##x = {x, #x};
    
// Bash
TSLanguage *tree_sitter_bash();
reflect(tree_sitter_bash);

// C
TSLanguage *tree_sitter_c();
reflect(tree_sitter_c);

// Cpp
TSLanguage *tree_sitter_cpp();
reflect(tree_sitter_cpp);

// CMake
TSLanguage *tree_sitter_cmake();
reflect(tree_sitter_cmake);

// C#
TSLanguage *tree_sitter_c_sharp();
reflect(tree_sitter_c_sharp);

// Go
TSLanguage *tree_sitter_go();
reflect(tree_sitter_go);

// Html
TSLanguage *tree_sitter_html();
reflect(tree_sitter_html);

// Java
TSLanguage *tree_sitter_java();
reflect(tree_sitter_java);

// Javascript
TSLanguage *tree_sitter_javascript();
reflect(tree_sitter_javascript);

// Json
TSLanguage *tree_sitter_json();
reflect(tree_sitter_json);

// kotlin
TSLanguage *tree_sitter_kotlin();
reflect(tree_sitter_kotlin);

// Lua
TSLanguage *tree_sitter_lua();
reflect(tree_sitter_lua);

// Make
TSLanguage *tree_sitter_make();
reflect(tree_sitter_make);

// Markdown
TSLanguage *tree_sitter_markdown();
reflect(tree_sitter_markdown);

// Python
TSLanguage *tree_sitter_python();
reflect(tree_sitter_python);

// Query
TSLanguage *tree_sitter_query();
reflect(tree_sitter_query);

// Rust
TSLanguage *tree_sitter_rust();
reflect(tree_sitter_rust);

// Smail
TSLanguage *tree_sitter_smali();
reflect(tree_sitter_smali);

// Swift
TSLanguage *tree_sitter_swift();
reflect(tree_sitter_swift);

#ifdef __cplusplus
}
#endif

#endif // __TS_LANGUAGE_H__

