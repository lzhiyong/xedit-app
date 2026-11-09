#!/usr/bin/env python3
#
# Copyright © 2026 Github Lzhiyong
#
# Licensed under the Apache License, Version 2.0 (the 'License');
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an 'AS IS' BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
"""Clone the tree-sitter sources into src/main/cpp/tree-sitter, where CMakeLists.txt
builds them from.

    python3 get_treesitter_source.py                     # branch master
    python3 get_treesitter_source.py --branch v0.25.x    # a branch, or a tag (v0.25.3)
    TREE_SITTER_BRANCH=v0.25.x python3 get_treesitter_source.py

Paths are resolved from where this script lies, so it can be run from any directory.
Gradle runs it before every build (the `clone` task of build.gradle.kts, with the
`treeSitterBranch` of gradle.properties), so a checkout that is already of the branch
asked for is left as it is, without going to the network. One of another branch is
cloned again: into a temporary directory first, then put in its place, so a failed
clone leaves the old one. A checkout with local changes is never replaced, unless
--force is given.

Exit status: 0 when the sources are there, 1 otherwise (Gradle fails the build then).
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

DEFAULT_URL = "https://github.com/tree-sitter/tree-sitter"
DEFAULT_BRANCH = "master"
MODULE_DIR = Path(__file__).resolve().parent
DEFAULT_DEST = MODULE_DIR / "src" / "main" / "cpp" / "tree-sitter"
# what CMakeLists.txt compiles: its absence means the checkout is not usable
REQUIRED_FILE = Path("lib") / "src" / "lib.c"


class SourceError(Exception):
    pass


def git(*args, cwd=None, check=True):
    """Runs git, returning its standard output (stripped)."""
    result = subprocess.run(
        ["git", *args], cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
    )
    if check and result.returncode != 0:
        raise SourceError("git {} failed:\n{}".format(" ".join(args), result.stderr.strip()))
    return result.stdout.strip() if result.returncode == 0 else None


def refs_of(dest):
    """The names the checkout in dest is at: its branch, or the tags of a detached HEAD."""
    names = set()
    branch = git("symbolic-ref", "--short", "-q", "HEAD", cwd=dest, check=False)
    if branch:
        names.add(branch)
    tags = git("tag", "--points-at", "HEAD", cwd=dest, check=False)
    if tags:
        names.update(tags.split())
    return names


def has_local_changes(dest):
    status = git("status", "--porcelain", cwd=dest, check=False)
    return status is None or status != ""


def is_checkout(dest):
    return (dest / ".git").exists()


def clone(url, branch, dest):
    """A shallow clone of branch (or tag) into dest, which must not exist."""
    print("Cloning {} ({}) into {}".format(url, branch, dest), flush=True)
    result = subprocess.run(
        ["git", "clone", "--depth", "1", "--single-branch", "--branch", branch, url, str(dest)]
    )
    if result.returncode != 0:
        raise SourceError(
            "cannot clone {} of {}: is there such a branch or tag, and a network?".format(branch, url)
        )


def clone_in_place(url, branch, dest):
    """Clones into a temporary directory next to dest, then puts it in place of dest."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    temp = Path(tempfile.mkdtemp(prefix=".tree-sitter-", dir=str(dest.parent)))
    try:
        target = temp / "tree-sitter"
        clone(url, branch, target)
        if not (target / REQUIRED_FILE).is_file():
            raise SourceError("{} has no {}: not the tree-sitter sources".format(url, REQUIRED_FILE))
        if dest.exists():
            shutil.rmtree(dest)
        os.replace(target, dest)
    finally:
        shutil.rmtree(temp, ignore_errors=True)


def update(dest, branch, force):
    """The latest commit of branch, for a checkout that is already of it."""
    if has_local_changes(dest) and not force:
        raise SourceError("{} has local changes: not updated (--force to discard them)".format(dest))
    print("Updating {} to the latest {}".format(dest, branch), flush=True)
    git("fetch", "--depth", "1", "origin", branch, cwd=dest)
    git("reset", "--hard", "FETCH_HEAD", cwd=dest)


def fetch_sources(url, branch, dest, force=False, do_update=False):
    if shutil.which("git") is None:
        raise SourceError("git is not installed (Termux: pkg install git)")

    # an empty directory (a stale gitlink checked out as a placeholder): as if absent
    if dest.is_dir() and not any(dest.iterdir()):
        dest.rmdir()

    if not dest.exists():
        clone_in_place(url, branch, dest)
    elif not is_checkout(dest):
        if not force:
            raise SourceError(
                "{} exists but is no git checkout: remove it, or run with --force".format(dest)
            )
        clone_in_place(url, branch, dest)
    elif branch in refs_of(dest):
        if do_update:
            update(dest, branch, force)
        else:
            print("{} is already at {}: left as it is".format(dest, branch))
    else:
        current = ", ".join(sorted(refs_of(dest))) or "a detached commit"
        if has_local_changes(dest) and not force:
            raise SourceError(
                "{} is at {}, not {}, and has local changes: not replaced "
                "(--force to discard them)".format(dest, current, branch)
            )
        print("{} is at {}, not {}: cloning again".format(dest, current, branch))
        clone_in_place(url, branch, dest)

    if not (dest / REQUIRED_FILE).is_file():
        raise SourceError("{} has no {}: not the tree-sitter sources".format(dest, REQUIRED_FILE))
    commit = git("rev-parse", "--short", "HEAD", cwd=dest, check=False) or "?"
    print("tree-sitter sources: {} ({} at {})".format(dest, branch, commit))


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Clone the tree-sitter sources the treesitter module is built from."
    )
    parser.add_argument(
        "-b", "--branch",
        default=os.environ.get("TREE_SITTER_BRANCH") or DEFAULT_BRANCH,
        help="the branch or tag to clone (default: $TREE_SITTER_BRANCH, else %(default)s)"
             if not os.environ.get("TREE_SITTER_BRANCH") else
             "the branch or tag to clone (default: $TREE_SITTER_BRANCH = %(default)s)",
    )
    parser.add_argument("--url", default=DEFAULT_URL, help="the repository (default: %(default)s)")
    parser.add_argument(
        "--dest", type=Path, default=DEFAULT_DEST,
        help="where the sources go (default: src/main/cpp/tree-sitter of the module)",
    )
    parser.add_argument(
        "--update", action="store_true",
        help="fetch the latest commit of the branch even if the checkout is already of it",
    )
    parser.add_argument(
        "--force", action="store_true",
        help="replace a checkout with local changes, or a directory that is no checkout",
    )
    args = parser.parse_args(argv)
    args.branch = args.branch.strip()
    if not args.branch:
        parser.error("the branch must not be empty")
    return args


def main(argv=None):
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        fetch_sources(args.url, args.branch, args.dest.resolve(), args.force, args.update)
    except SourceError as e:
        print("error: {}".format(e), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
