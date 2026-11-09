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

#ifndef TS_QUERY_FILTER_H
#define TS_QUERY_FILTER_H

#include <stdint.h>
#include <memory>
#include <string>
#include <tree_sitter/api.h>

// Decides, before they are handed to Kotlin, the text and node predicates of the
// matches, the way Neovim does as the matches come (it removes the failed ones from the
// cursor). A match whose predicates fail is left out; one whose predicates all hold, every
// one of them decided here, comes flagged (TSQueryMatch.predicatesDecided) and Kotlin
// does not evaluate them again. Each match handed to Kotlin and evaluated there costs a
// list, nodes, a capture, regexes, and calls back into the VM: a third of the time a block
// of a large C file took to be highlighted.
//
// Decided here, with the semantics of the Kotlin predicates (TSQueryPredicate,
// QueryPredicates), when the cursor was given the text (setText):
//
//  - eq? / not-eq? / any-eq? / any-not-eq?, with a string or another capture;
//  - any-of? / not-any-of?;
//  - lua-match? and its not- / any- forms: Lua patterns in full (classes, sets,
//    quantifiers, anchors, captures, back-references, %b, %f), as Lua 5.1 / LuaJIT match
//    them (string.find, which Neovim uses), on the UTF-8 bytes of the text;
//  - contains?, has-ancestor?, has-parent?, kind-eq? and their not- / any- forms;
//  - directives (set!, offset!, ...) and predicates Kotlin does not know: they hold.
//
// Not decided here: match? / vim-match? (Vim regexes), a capture whose text is not in the
// text given, a pattern Lua would not take. Such a match is only left out if another of
// its predicates fails, and is otherwise decided in Kotlin as before.
//
// Without setText the predicates that do not read text (has-ancestor?, has-parent?,
// kind-eq?, and eq? and the others on a capture that took no part) are still decided; a
// pattern with no predicate, or a tree Kotlin has no text of, is accepted as it is.
namespace query_filter {

// What the predicates of a query are decided with, worked out once per query (filters_of).
struct QueryFilters;

enum class Decision {
    // a predicate is not one of those decided here, or cannot be for this match
    UNDECIDED,
    // a predicate fails: the match is left out
    REJECT,
    // every predicate holds, all decided here
    ACCEPT
};

// A text the predicates are decided on: the document's (UTF-16) from start_byte on.
// Either a cursor's own (TSQueryCursor.setText(String)) or a TSQueryText, which any
// number of cursors read in place (TSQueryCursor.setText(TSQueryText)): the text of a
// block is then copied here once, not once for each cursor of the block.
struct QueryText {
    uint32_t start_byte = 0;
    std::u16string text;
};

// What one cursor decides its matches with. Owned by the cursor (ts_query_cursor.cpp)
// and only used by the thread that runs it: no lock, no lookup per match.
struct CursorState {
    const TSQuery *query = nullptr;
    std::shared_ptr<const QueryFilters> filters;
    // Whether Kotlin could read the text of the tree (TSTree.hasText) when the cursor
    // was run: without it, TSQueryCursor takes every predicate as holding.
    bool tree_has_text = true;
    // The text given (setText), null without one; forgotten when the cursor is run
    // again (exec). Shared: a TSQueryText closed meanwhile stays alive as long as this.
    std::shared_ptr<const QueryText> text;
    // the UTF-8 of a node's text for lua-match?, kept between matches (no malloc per test)
    std::string scratch;
};

// the filters of [query], worked out the first time a cursor runs it
std::shared_ptr<const QueryFilters> filters_of(const TSQuery *query);
// the query is deleted
void forget_query(const TSQuery *query);

// what the predicates of the pattern of [match] say
Decision decide(CursorState &state, const TSQueryMatch &match);

// The Lua pattern matcher (string.find(text, pattern) ~= nil), on bytes. [error] is set
// (and false returned) for a pattern Lua would raise an error for.
bool lua_find(const char *text, size_t length, const char *pattern, bool *error);

} // namespace query_filter

#endif // TS_QUERY_FILTER_H
