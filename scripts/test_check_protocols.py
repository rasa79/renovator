#!/usr/bin/env python3
"""
Self-tests for scripts/check_protocols.py (Appendix C rule 6).
Stdlib unittest only. Each test builds a throwaway repo tree under a temp dir and
asserts the checker's verdict, covering every rule positive and negative.

Run: python3 scripts/test_check_protocols.py
"""

import tempfile
import unittest
from pathlib import Path

import check_protocols as chk

VALID_BLOCK = """// LEARN[001] Test block
// Why this way: because it must teach something
// Good sides: it does
// Drawbacks: it costs six lines
// Concept: comments as design docs
// See also: LEARN_INDEX.md
"""
MISSING_FIELD_BLOCK = """// LEARN[001] Test block
// Why this way: because it must teach something
// Good sides: it does
// Drawbacks: it costs six lines
"""


class CheckerFixture:
    def __init__(self, root: Path):
        self.root = root

    def write(self, rel: str, content: str) -> Path:
        p = self.root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
        return p

    def index(self, number, title, location, concept="test"):
        return self.write(
            "LEARN_INDEX.md",
            "| # | Title | Location | Concept |\n"
            f"| {number:03d} | {title} | {location} | {concept} |\n",
        )


class LearnRuleTest(unittest.TestCase):
    def test_accepts_valid_learn_block(self):
        with tempfile.TemporaryDirectory() as td:
            fx = CheckerFixture(Path(td))
            p = fx.write("src/t/Test.kt", VALID_BLOCK)
            fx.index(1, "Test block", f"{p.relative_to(Path(td)).as_posix()}:1")
            violations = []
            chk.check_learn(Path(td), violations)
            self.assertEqual([], violations)

    def test_rejects_missing_field(self):
        with tempfile.TemporaryDirectory() as td:
            fx = CheckerFixture(Path(td))
            fx.write("src/t/Test.kt", MISSING_FIELD_BLOCK)
            violations = []
            chk.check_learn(Path(td), violations)
            self.assertTrue(any("missing field" in v and "Concept" in v for v in violations))

    def test_rejects_numbering_gap(self):
        with tempfile.TemporaryDirectory() as td:
            fx = CheckerFixture(Path(td))
            fx.write("src/t/Test.kt", VALID_BLOCK)
            fx.write("src/t/Test2.kt", VALID_BLOCK.replace("001", "019"))
            violations = []
            chk.check_learn(Path(td), violations)
            self.assertTrue(any("gap" in v for v in violations))


class DeferredWorkRuleTest(unittest.TestCase):
    def test_rejects_orphan_todo_review_marker(self):
        with tempfile.TemporaryDirectory() as td:
            fx = CheckerFixture(Path(td))
            fx.write("src/t/Test.kt", "// TODO(review) KL-07 will do X later\n")
            violations = []
            chk.check_deferred(Path(td), chk.scan_files(Path(td)), False, violations)
            self.assertTrue(any("KL-07" in v and "no KNOWN_LIMITATIONS" in v for v in violations))

    def test_rejects_orphan_limitations_entry(self):
        with tempfile.TemporaryDirectory() as td:
            fx = CheckerFixture(Path(td))
            fx.write(
                "KNOWN_LIMITATIONS.md",
                "| KL-NN | Title | User-visible | Pre-declared | Rationale |\n"
                "| KL-07 | something deferred | user-visible: no | pre-declared: no | later |\n",
            )
            violations = []
            chk.check_deferred(Path(td), chk.scan_files(Path(td)), False, violations)
            self.assertTrue(any("KL-07" in v and "no TODO(review)" in v for v in violations))

    def test_accepts_matched_marker_and_entry(self):
        with tempfile.TemporaryDirectory() as td:
            fx = CheckerFixture(Path(td))
            fx.write("src/t/Test.kt", "// TODO(review) KL-07 will do X later\n")
            fx.write(
                "KNOWN_LIMITATIONS.md",
                "| KL-NN | Title | User-visible | Pre-declared | Rationale |\n"
                "| KL-07 | something deferred | user-visible: no | pre-declared: no | later |\n",
            )
            violations = []
            chk.check_deferred(Path(td), chk.scan_files(Path(td)), False, violations)
            self.assertEqual([], violations)


class PromptLocationRuleTest(unittest.TestCase):
    PROMPT = """
class P {
    val p = \"\"\"
        You are a helpful upgrade planner.
        Reply with JSON only.
        Summarize the failure and respond.
        \"\"\"
}
"""

    def test_rejects_prompts_outside_prompts_dir_when_phase_ge_3(self):
        with tempfile.TemporaryDirectory() as td:
            fx = CheckerFixture(Path(td))
            fx.write("src/t/Test.kt", self.PROMPT)
            violations = []
            chk.check_prompts(Path(td), 3, violations)
            self.assertTrue(any("prompt-shaped literal" in v for v in violations))

    def test_no_prompt_rule_before_phase_3(self):
        with tempfile.TemporaryDirectory() as td:
            fx = CheckerFixture(Path(td))
            fx.write("src/t/Test.kt", self.PROMPT)
            violations = []
            chk.check_prompts(Path(td), 2, violations)
            self.assertEqual([], violations)


class NoVerifyRuleTest(unittest.TestCase):
    def test_rejects_no_verify_in_scripts(self):
        with tempfile.TemporaryDirectory() as td:
            fx = CheckerFixture(Path(td))
            fx.write("scripts/evil.sh", "#!/usr/bin/env bash\ngit commit --no-verify -m x\n")
            violations = []
            chk.check_no_verify(Path(td), violations)
            self.assertTrue(any("GW-4" in v and "evil.sh" in v for v in violations))

    def test_accepts_prose_mention_in_docs(self):
        with tempfile.TemporaryDirectory() as td:
            fx = CheckerFixture(Path(td))
            fx.write("docs/protocol.md", "`--no-verify` is forbidden (GW-4).\n")
            violations = []
            chk.check_no_verify(Path(td), violations)
            self.assertEqual([], violations)


if __name__ == "__main__":
    unittest.main()
