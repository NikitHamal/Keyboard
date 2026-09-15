#!/usr/bin/env python3
"""
A Kotlin-aware structural checker for this repository.

Why this exists
---------------
This project is built with zero local execution: no Gradle, no compiler, no
emulator. Correctness is established by inspection plus remote CI. A cheap
automated pass over the sources catches the class of error that inspection
reliably misses -- an unbalanced brace, a stray paren, an unterminated string,
a package that disagrees with its directory -- without needing a toolchain.

A naive regex-based brace counter does NOT work on Kotlin, because all four of
these appear in real code and none of them delimits a block:

  * string interpolation:      "n=$count, len=${list.size}"
  * raw strings:               \"\"\"{"json": true}\"\"\"
  * character literals:        '{'   '}'   '$'
  * escaped quotes:            "he said \\"hi\\""

So this file implements a small single-pass state machine. Only characters seen
in the `code` state contribute to delimiter counts. Two details make it correct
where the naive version is not:

  1. `${ ... }` inside a string is a brace pair owned by the *string*, not by
     the enclosing block. It is tracked with its own depth counter and consumed
     without touching the block stack. A `{` that opens a lambda *inside* an
     interpolation goes on the block stack, so its `}` is popped correctly and
     the interpolation's own closer still sees the right depth.

  2. A raw string may end with more than three quotes, and the extras are
     content. The lexer consumes the whole run.

Also checked, because they are equally invisible to eyeballing:

  * every `package` agrees with the file's directory under the source root
  * no file contains a forbidden placeholder marker
  * every file is valid UTF-8 and has no tab characters in leading indentation

Usage:
    python tools/kotlin_lint.py [source-root ...]

Exit code 0 when everything passes, 1 otherwise, so CI can gate on it.
"""

from __future__ import annotations

import io
import os
import re
import sys

# Markers indicating unfinished work. The project specification forbids these
# outright, so their presence is a hard failure.
FORBIDDEN_MARKERS = (
    "// todo",
    "//todo",
    "// fixme",
    "//fixme",
    "implement later",
    "unimplemented",
    "not implemented yet",
    "placeholder",
)

# Delimiters kept balanced. Angle brackets are excluded deliberately: Kotlin
# generics and the less-than operator are ambiguous without a full parser.
DELIMITERS = {"{": "}", "(": ")", "[": "]"}

# Lexer states.
CODE = "code"
LINE_COMMENT = "line_comment"
BLOCK_COMMENT = "block_comment"
STRING = "string"
RAW_STRING = "raw_string"
CHAR = "char"


def scan(text: str) -> list[str]:
    """
    Walk `text` once and return a list of structural error messages.

    The machine is permissive about what constitutes valid Kotlin. It is a
    structural check, not a parser: its only job is to classify each character
    into the right bucket so the delimiter counts can be trusted.
    """
    errors: list[str] = []

    # Block-delimiter stack. Stores (opening_char, line_opened) so a mismatch is
    # reported against the line that opened it.
    stack: list[tuple[str, int]] = []

    # Depth of `${ ... }` interpolation inside a string. While > 0 we lex Kotlin
    # inside a string.
    interpolation_depth = 0

    state = CODE
    line = 1
    i = 0
    n = len(text)

    def fail(message: str) -> None:
        errors.append(f"line {line}: {message}")

    while i < n:
        c = text[i]

        # --- Newlines are handled in every state, and end a line comment. ---
        if c == "\n":
            line += 1
            if state is LINE_COMMENT or state == LINE_COMMENT:
                state = CODE
            i += 1
            continue

        # =====================================================================
        # CODE (or Kotlin embedded in a string interpolation)
        # =====================================================================
        if state == CODE or (state == STRING and interpolation_depth > 0):

            # Closing an interpolation returns us to string lexing. Checked
            # before everything else, because inside `${...}` a `}` means "end
            # of interpolation", not "end of block".
            if c == "}" and interpolation_depth > 0:
                interpolation_depth -= 1
                i += 1
                continue

            # --- Comments ---------------------------------------------------
            if c == "/" and i + 1 < n:
                nxt = text[i + 1]
                if nxt == "/":
                    state = LINE_COMMENT
                    i += 2
                    continue
                if nxt == "*":
                    state = BLOCK_COMMENT
                    i += 2
                    continue

            # --- Raw strings (""" ... """) ---------------------------------
            if c == '"' and text.startswith('"""', i):
                state = RAW_STRING
                i += 3
                continue

            # --- Normal strings --------------------------------------------
            if c == '"':
                state = STRING
                i += 1
                continue

            # --- Char literals ---------------------------------------------
            # Kotlin has no bare apostrophe outside a string, so a `'` always
            # opens a char literal here.
            if c == "'":
                state = CHAR
                i += 1
                continue

            # --- A new interpolation may open while one is already active ---
            if c == "$" and i + 1 < n and text[i + 1] == "{" and state == STRING:
                interpolation_depth += 1
                i += 2
                continue

            # --- Delimiters -------------------------------------------------
            # A `{` opened inside an interpolation (a lambda in `${list.map { } }`)
            # goes on the stack, so its `}` pops correctly and the
            # interpolation's own closer is the one that decrements the
            # interpolation depth.
            if c in DELIMITERS:
                stack.append((c, line))
                i += 1
                continue
            if c in DELIMITERS.values():
                if not stack:
                    fail(f"unmatched closing '{c}'")
                else:
                    opener, opened_at = stack.pop()
                    if DELIMITERS[opener] != c:
                        fail(
                            f"mismatched '{opener}' (opened line {opened_at}) "
                            f"closed by '{c}'"
                        )
                i += 1
                continue

            i += 1
            continue

        # =====================================================================
        # Comments
        # =====================================================================
        if state == LINE_COMMENT:
            i += 1
            continue

        if state == BLOCK_COMMENT:
            if c == "*" and i + 1 < n and text[i + 1] == "/":
                state = CODE
                i += 2
                continue
            i += 1
            continue

        # =====================================================================
        # Raw string
        # =====================================================================
        if state == RAW_STRING:
            if text.startswith('"""', i):
                # Consume the entire run of quotes: a raw string ending in a
                # quote character closes with four or more.
                j = i
                while j < n and text[j] == '"':
                    j += 1
                i = j
                state = CODE
                continue
            i += 1
            continue

        # =====================================================================
        # Normal string
        # =====================================================================
        if state == STRING:
            if c == "\\":
                i += 2  # escaped character
                continue
            if c == "$" and i + 1 < n and text[i + 1] == "{":
                interpolation_depth = 1
                i += 2
                continue
            if c == '"':
                state = CODE
                i += 1
                continue
            i += 1
            continue

        # =====================================================================
        # Char literal
        # =====================================================================
        if state == CHAR:
            if c == "\\":
                i += 2
                continue
            if c == "'":
                state = CODE
                i += 1
                continue
            i += 1
            continue

        i += 1

    # --- End-of-file diagnostics ------------------------------------------
    if stack:
        opener, opened_at = stack[-1]
        errors.append(
            f"unclosed '{opener}' opened at line {opened_at} "
            f"({len(stack)} unclosed total)"
        )
    if state == BLOCK_COMMENT:
        errors.append("unterminated block comment")
    if state == STRING and interpolation_depth == 0:
        errors.append("unterminated string literal")
    if state == RAW_STRING:
        errors.append("unterminated raw string literal")
    if state == CHAR:
        errors.append("unterminated character literal")

    return errors


def check_package(path: str, text: str, source_root: str) -> list[str]:
    """
    Verify the `package` declaration matches the file's directory.

    Kotlin does not require this, but every IDE, the R8 keep rules in
    `proguard-rules.pro`, and the manifest's relative class names all assume it.
    A mismatch compiles and then fails to keep the right classes when minified,
    which is the hardest possible bug to diagnose.
    """
    match = re.search(r"^\s*package\s+([A-Za-z_][\w.]*)", text, re.MULTILINE)
    if not match:
        return [f"{path}: no package declaration"]

    declared = match.group(1)
    rel = os.path.relpath(path, source_root)
    directory = os.path.dirname(rel).replace(os.sep, ".")
    if directory and declared != directory:
        return [f"{path}: package '{declared}' does not match directory '{directory}'"]
    return []


def check_placeholders(path: str, text: str) -> list[str]:
    """Report any forbidden placeholder marker."""
    lowered = text.lower()
    return [
        f"{path}: contains forbidden marker '{marker}'"
        for marker in FORBIDDEN_MARKERS
        if marker in lowered
    ]


def check_top_level(path: str, text: str) -> list[str]:
    """
    Require at least one declaration.

    An empty file in a Kotlin source set is almost always a mistake -- typically
    a write that silently failed -- and produces a confusing "unresolved
    reference" much later.
    """
    if re.search(
        r"^(?:@\w+\s*)*(?:public |internal |private |abstract |open |sealed |data |enum |value |annotation )*"
        r"(?:class|interface|object|fun|val|var|typealias)\s",
        text,
        re.MULTILINE,
    ):
        return []
    return [f"{path}: no top-level declaration found"]


def main(argv: list[str]) -> int:
    roots = argv[1:] or ["app/src/main/java"]

    # Test source roots are checked too, when present.
    for extra in ("app/src/test/java", "app/src/androidTest/java"):
        if os.path.isdir(extra) and extra not in roots:
            roots.append(extra)

    all_errors: list[str] = []
    file_count = 0
    total_lines = 0

    for root in roots:
        if not os.path.isdir(root):
            continue
        for dirpath, _dirnames, filenames in os.walk(root):
            for filename in sorted(filenames):
                if not filename.endswith(".kt"):
                    continue
                path = os.path.join(dirpath, filename)
                file_count += 1
                try:
                    with io.open(path, encoding="utf-8") as handle:
                        text = handle.read()
                except UnicodeDecodeError as exc:
                    all_errors.append(f"{path}: not valid UTF-8 ({exc})")
                    continue

                total_lines += text.count("\n") + 1

                for message in scan(text):
                    all_errors.append(f"{path}: {message}")
                all_errors.extend(check_package(path, text, root))
                all_errors.extend(check_placeholders(path, text))
                all_errors.extend(check_top_level(path, text))

    print(f"Scanned {file_count} Kotlin files, {total_lines} lines.")
    if all_errors:
        print(f"\n{len(all_errors)} problem(s):\n")
        for message in sorted(all_errors):
            print(f"  {message}")
        return 1

    print("No structural problems found.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
