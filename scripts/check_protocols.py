#!/usr/bin/env python3
"""
Mechanical protocol checker for the Renovator repo (PLAN.md Appendix C).

Exit 0 = clean; exit 1 = violations (one line each).

Rules implemented (Appendix C, with one documented refinement):
  1. LEARN[NNN] blocks: all 5 fields (Why this way / Good sides / Drawbacks /
     Concept; See also optional), >= 6 lines, unless a LEARN-REF[NNN] stub
     (exempt, must reference an existing number).
  2. Numbering gapless from 001, no duplicates; LEARN_INDEX.md has exactly one
     live row per number, with matching title and current file:line (recomputed).
  3. TODO(review) KL-NN markers <-> KNOWN_LIMITATIONS.md rows, 1:1 both
     directions. REFINEMENT: rows flagged `pre-declared: yes` are the seeded
     scope/design limitations from PLAN §10.2 (KL-01..KL-06) — they carry no code
     marker by construction and are legitimate without one; every other row must
     map to exactly one live marker. `user-visible: yes` rows additionally need a
     README.md sentence once the audit demands it (`--full`, or phase >= 7).
  4. Prompts live only under src/main/resources/prompts/ (advisory before
     Phase 3, hard fail from Phase 3; phase detected from git tags, overridable).
  5. --phase-boundary: `git status --porcelain` empty AND the newest
     phase-N-complete tag (if any) is reachable from HEAD.

// LEARN[002] The protocol lint is mechanical and load-bearing
// Why this way: protocols enforced by review drift — a reviewer who must remember
//   "did the LEARN comment have all five fields?" will forget exactly once, at the
//   worst time. If the check runs in pre-commit and fails the commit, the protocol
//   holds by construction instead of by vigilance. The Sentinel discipline this
//   plan descends from had the same lesson: human-checked rules decay, hooked
//   rules persist.
// Good sides: the checks are re-runnable (Task 7.2 runs --full over history), the
//   failures name file+line, and one caught mis-formatting catches every future
//   one; a new author learns the format from the error, not from a doc unread.
// Drawbacks: a linter cannot judge *meaning* — a five-field comment can still be
//   vacuous (the fix is the restate test in phase reports, which stays human). It
//   also costs a few hundred lines of stdlib Python that must itself stay honest;
//   the self-tests (test_check_protocols.py) are the guard on the guard.
// Concept: think of it as a compiler for an internal DSL of conventions: only
//   rules that fail loudly get followed, and failure messages are compiler
//   diagnostics.
// See also: scripts/test_check_protocols.py, docs/protocol.md
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

LEARN_FIELDS = ("Why this way:", "Good sides:", "Drawbacks:", "Concept:")
SCANNED_ROOTS = ("src", "fixtures", "scripts", "docs")
PHASE_REPORT_DIR = "docs/phase-reports"
PROMPT_DIR = "src/main/resources/prompts"

# Heuristic for prompt-shaped code: triple-quoted string of >= 3 lines containing
# an instruction verb (Appendix C rule 4).
PROMPT_VERBS = (
    "you are", "your task", "respond with", "reply with", "summarize",
    "explain the", "generate a", "instruct", "must return", "answer in json",
)


def find_repo_root(start: Path) -> Path:
    for candidate in (start, *start.parents):
        if (candidate / ".git").exists():
            return candidate
    raise SystemExit("not inside a git repository")


def scan_files(root: Path) -> list[Path]:
    files = []
    for root_name in SCANNED_ROOTS:
        base = root / root_name
        if not base.exists():
            continue
        for path in base.rglob("*"):
            if not path.is_file() or "target" in path.parts or ".tools" in path.parts:
                continue
            rel = path.relative_to(root).as_posix()
            if rel == "LEARN_INDEX.md" or rel == "scripts/test_check_protocols.py":
                continue
            if rel.startswith(PHASE_REPORT_DIR):
                continue
            if path.suffix in (".kt", ".java", ".md", ".py", ".sh", ".yml", ".yaml"):
                files.append(path)
    return files


def read_text(path: Path) -> str | None:
    try:
        return path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return None


def learn_markers(root: Path, files: list[Path]) -> dict[int, dict]:
    markers = {}
    for f in files:
        text = read_text(f)
        if text is None:
            continue
        lines = text.splitlines()
        block_end = -1
        for i, line in enumerate(lines):
            m = re.search(r"^(\s*(//|#)\s*)LEARN\[(\d{3})\]", line)
            if not m:
                continue
            # A marker is a comment line that STARTS with LEARN[NNN]; any other
            # mention ("See also: ... LEARN[006]", prose) is a reference, not a block.
            if i <= block_end and False:
                continue
            number = int(m.group(3))
            # Block = marker line plus the following consecutive comment lines.
            block = [line]
            for j in range(i + 1, len(lines)):
                if re.match(r"^\s*(//|#)\s?", lines[j]):
                    block.append(lines[j])
                else:
                    break
            block_end = i + len(block) - 1
            markers[number] = {
                "file": f.relative_to(root).as_posix(),
                "line": i + 1,
                "fields": {field for field in LEARN_FIELDS if any(field in l for l in block)},
                "nlines": len(block),
                "stub": bool(re.search(r"^(\s*(//|#)\s*)LEARN-REF\[\d{3}\]", line)),
            }
    return markers


def read_index(root: Path) -> dict[int, dict]:
    idx = root / "LEARN_INDEX.md"
    text = read_text(idx)
    if text is None:
        return {}
    rows = {}
    for line_no, line in enumerate(text.splitlines(), 1):
        if "~~" in line[:20]:
            continue  # struck-through rows are historical, not live
        m = re.match(r"^\|\s*(\d{3})\s*\|", line)
        if not m:
            continue
        number = int(m.group(1))
        parts = [p.strip() for p in line.strip("|").split("|")]
        rows[number] = {
            "row": line_no,
            "title": parts[1] if len(parts) > 1 else "",
            "location": parts[2] if len(parts) > 2 else "",
            "concept": parts[3] if len(parts) > 3 else "",
        }
    return rows


def git(root: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", "-C", str(root), *args], capture_output=True, text=True, check=False
    )


def current_phase(root: Path) -> int:
    tags = git(root, "tag").stdout.splitlines()
    phases = [
        int(re.search(r"phase-(\d)", t).group(1))
        for t in tags
        if re.fullmatch(r"phase-\d-complete", t)
    ]
    return max(phases, default=0)


# PLAN §10.1.8 pre-allocates slots 001-016 with fixed placements; its own ordering
# lands them out of sequence across tasks (e.g. LEARN[006] is Task 2.7, after 007 in
# Task 2.3), so gaps inside 1..16 are transient and permitted. Below 17, numbers must
# be gapless; --full audits 1..16 completeness.
PRE_ALLOCATED_SLOTS = 16


def check_learn(root: Path, violations: list[str], full: bool = False):
    files = scan_files(root)
    markers = learn_markers(root, files)

    live_numbers = sorted(n for n, m in markers.items() if not m["stub"])
    live_numbers = [n for n in live_numbers if n > 0]
    for expected, number in enumerate(live_numbers, 1):
        pass
    beyond = [n for n in live_numbers if n > PRE_ALLOCATED_SLOTS]
    for expected, number in enumerate(beyond, 1):
        if number != expected + PRE_ALLOCATED_SLOTS:
            violations.append(f"LEARN numbering: gap in post-allocated numbers at {expected + PRE_ALLOCATED_SLOTS:03d} (saw {number:03d})")
            break
    if len(live_numbers) != len(set(live_numbers)):
        violations.append("LEARN numbering: duplicate numbers present")
    if full:
        for expected in range(1, PRE_ALLOCATED_SLOTS + 1):
            if expected not in markers:
                violations.append(f"LEARN numbering: pre-allocated slot {expected:03d} missing (audit --full)")

    for number, m in markers.items():
        if m["stub"]:
            ref = re.search(r"LEARN-REF\[(\d{3})\]", " ".join(m["block"]))
            if ref and int(ref.group(1)) not in markers:
                violations.append(
                    f"LEARN-REF[{ref.group(1)}] {m['file']}:{m['line']} references unknown number"
                )
            continue
        missing = sorted(set(LEARN_FIELDS) - m["fields"])
        if missing:
            violations.append(
                f"LEARN[{number:03d}] {m['file']}:{m['line']} missing field(s): {', '.join(missing)}"
            )
        if m["nlines"] < 6:
            violations.append(
                f"LEARN[{number:03d}] {m['file']}:{m['line']} body is {m['nlines']} lines (< 6)"
            )

    index = read_index(root)
    for number in live_numbers:
        row = index.get(number)
        if row is None:
            violations.append(f"LEARN[{number:03d}] present in code but missing from LEARN_INDEX.md")
            continue
        expected_loc = f"{markers[number]['file']}:{markers[number]['line']}"
        if row["location"] != expected_loc:
            violations.append(
                f"LEARN[{number:03d}] index location '{row['location']}' != code '{expected_loc}'"
            )
    for number in index:
        if number not in markers:
            violations.append(f"LEARN[{number:03d}] in LEARN_INDEX.md but no marker in code")


def todo_markers(files: list[Path], verbose: bool = False) -> set[int]:
    markers = set()
    for f in files:
        text = read_text(f)
        if text is None:
            continue
        for line_no, line in enumerate(text.splitlines(), 1):
            if "TODO(review)" not in line:
                continue
            m = re.search(r"TODO\(review\)\s*KL-(\d{2})", line)
            if not m:
                if verbose:
                    print(f"NOTE: TODO(review) without KL-NN id: {f}:{line_no} ({line.strip()})")
                continue
            markers.add(int(m.group(1)))
    return markers


def limitations_rows(root: Path) -> dict[int, dict]:
    text = read_text(root / "KNOWN_LIMITATIONS.md")
    if text is None:
        return {}
    rows = {}
    for line_no, line in enumerate(text.splitlines(), 1):
        if "~~" in line[:16]:
            continue
        m = re.match(r"^\|\s*KL-(\d{2})\s*\|", line)
        if not m:
            continue
        number = int(m.group(1))
        flags = line.lower()
        rows[number] = {
            "row": line_no,
            "predeclared": "pre-declared: yes" in flags,
            "user_visible": "user-visible: yes" in flags,
        }
    return rows


def check_deferred(root: Path, files: list[Path], check_readme: bool, violations: list[str], verbose: bool = False):
    markers = todo_markers(files, verbose=verbose)
    rows = limitations_rows(root)

    for number in sorted(markers):
        if number not in rows:
            violations.append(f"TODO(review) KL-{number:02d} marker has no KNOWN_LIMITATIONS.md row")
    for number, row in sorted(rows.items()):
        if row["predeclared"]:
            continue
        if number not in markers:
            violations.append(f"KL-{number:02d} KNOWN_LIMITATIONS.md row has no TODO(review) marker")

    if check_readme:
        readme_text = read_text(root / "README.md") or ""
        for number, row in sorted(rows.items()):
            if row["user_visible"] and f"KL-{number:02d}" not in readme_text:
                violations.append(
                    f"KL-{number:02d} is user-visible: yes but README.md has no sentence mentioning it"
                )


def check_prompts(root: Path, phase: int, violations: list[str]):
    if phase < 3:
        return  # advisory only before Phase 3 (Appendix C rule 4)
    for f in scan_files(root):
        if f.suffix not in (".kt", ".java"):
            continue
        if PROMPT_DIR in f.relative_to(root).as_posix():
            continue
        text = read_text(f)
        if text is None:
            continue
        for m in re.finditer(r'"""([^"]{20,}?)"""', text, re.S):
            snippet = m.group(1)
            if len(snippet.splitlines()) >= 3 and any(v in snippet.lower() for v in PROMPT_VERBS):
                violations.append(
                    f"prompt-shaped literal outside {PROMPT_DIR}: {f} "
                    f"(found {len(snippet.splitlines())}-line triple-quoted string)"
                )
                break


def check_no_verify(root: Path, violations: list[str]):
    """GW-4 guard (reviewer standing condition, Phase 2): executable tooling must
    never contain --no-verify, so the compound-fallback failure mode of the Phase-1.1
    incident cannot silently return. Prose in docs/ (which documents the ban) is
    deliberately not scanned. The pre-commit hook itself is covered even though it is
    not under scripts/ — it is the file most likely to receive such a flag."""
    targets = [(root / ".git/hooks/pre-commit", ".git/hooks/pre-commit")]
    targets += [(f, f.relative_to(root).as_posix()) for f in (root / "scripts").glob("*.sh")]
    for path, label in targets:
        text = read_text(path)
        if text is not None and "--no-verify" in text:
            violations.append(f"GW-4: {label} contains --no-verify (forbidden by PLAN 10.3)")


def check_phase_boundary(root: Path, violations: list[str]):
    status = git(root, "status", "--porcelain").stdout
    if status.strip():
        violations.append("--phase-boundary: git status --porcelain is not empty:\n" + status.strip())
    tags = [
        t
        for t in git(root, "tag").stdout.splitlines()
        if re.fullmatch(r"phase-\d-complete", t)
    ]
    if not tags:
        print("note: no phase-*-complete tag yet (Phase 0 pre-gate state)")
        return
    newest = sorted(tags, key=lambda t: int(re.search(r"phase-(\d)", t).group(1)))[-1]
    if git(root, "merge-base", "--is-ancestor", newest, "HEAD").returncode != 0:
        violations.append(f"--phase-boundary: newest tag {newest} is not reachable from HEAD")


def main() -> int:
    parser = argparse.ArgumentParser(description="Renovator protocol checker")
    parser.add_argument("--phase-boundary", action="store_true", help="also enforce git cleanliness")
    parser.add_argument("--full", action="store_true", help="full audit incl. README sentence check")
    parser.add_argument("--force-phase", type=int, default=None, help="override detected phase")
    parser.add_argument("--verbose", action="store_true", help="print informational notes")
    args = parser.parse_args()

    root = find_repo_root(Path(__file__).resolve().parent)
    files = scan_files(root)
    phase = args.force_phase if args.force_phase is not None else current_phase(root)
    violations: list[str] = []

    check_learn(root, violations, full=args.full)
    check_no_verify(root, violations)
    check_deferred(root, files, check_readme=args.full or phase >= 7, violations=violations, verbose=args.verbose)
    check_prompts(root, phase, violations)
    if args.phase_boundary:
        check_phase_boundary(root, violations)

    if violations:
        print(f"Protocol violations ({len(violations)}):")
        for v in violations:
            print(f"  - {v}")
        return 1
    print("0 violations")
    return 0


if __name__ == "__main__":
    sys.exit(main())
