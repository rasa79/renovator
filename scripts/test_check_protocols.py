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


class LedgerOrderRuleTest(unittest.TestCase):
    def test_rejects_out_of_order_learn_index(self):
        with tempfile.TemporaryDirectory() as td:
            fx = CheckerFixture(Path(td))
            fx.write(
                "LEARN_INDEX.md",
                "| # | Title | Location | Concept |\n"
                "| 002 | b | f:2 | c |\n"
                "| 001 | a | f:1 | c |\n",
            )
            violations = []
            chk.check_ledger_order(Path(td), violations)
            self.assertTrue(any("LEARN_INDEX.md" in v and "out of ascending order" in v for v in violations))

    def test_accepts_sorted_learn_index(self):
        with tempfile.TemporaryDirectory() as td:
            fx = CheckerFixture(Path(td))
            fx.write(
                "LEARN_INDEX.md",
                "| # | Title | Location | Concept |\n"
                "| 001 | a | f:1 | c |\n"
                "| 008 | h | f:8 | c |\n",
            )
            violations = []
            chk.check_ledger_order(Path(td), violations)
            self.assertEqual([], violations)

    def test_rejects_out_of_order_kl_ledger(self):
        with tempfile.TemporaryDirectory() as td:
            fx = CheckerFixture(Path(td))
            fx.write(
                "KNOWN_LIMITATIONS.md",
                "| KL-NN | Title | User-visible | Pre-declared | Rationale |\n"
                "| KL-10 | x | user-visible: no | pre-declared: yes | r |\n"
                "| KL-06 | y | user-visible: no | pre-declared: yes | r |\n",
            )
            violations = []
            chk.check_ledger_order(Path(td), violations)
            self.assertTrue(any("KNOWN_LIMITATIONS.md" in v and "KL-10" in v for v in violations))


class LedgerShapeRuleTest(unittest.TestCase):
    HEADER = "| KL-NN | Title | User-visible | Pre-declared | Rationale |\n"

    def test_accepts_well_formed_kl_table(self):
        with tempfile.TemporaryDirectory() as td:
            fx = CheckerFixture(Path(td))
            fx.write(
                "KNOWN_LIMITATIONS.md",
                self.HEADER
                + "| KL-10 | x | user-visible: no | pre-declared: yes | r |\n"
                + "| ~~KL-12~~ | y | user-visible: no | CLOSED (phase-3.2) | implemented |\n"
                + "| KL-13 | z | user-visible: no | pre-declared: no | r |\n",
            )
            violations = []
            chk.check_ledger_shape(Path(td), violations)
            self.assertEqual([], violations)

    def test_rejects_kl_row_split_across_lines(self):
        # The exact bug class: the number cell alone on its own line, the rest
        # of the row's cells dangling after the NEXT row (outside the table).
        with tempfile.TemporaryDirectory() as td:
            fx = CheckerFixture(Path(td))
            fx.write(
                "KNOWN_LIMITATIONS.md",
                self.HEADER
                + "| KL-10 | x | user-visible: no | pre-declared: yes | r |\n"
                + "| ~~KL-12~~ |\n"
                + "| KL-13 | z | user-visible: no | pre-declared: no | r |\n"
                + "  dangling cells | user-visible: no | CLOSED (phase-3.2) | implemented |\n",
            )
            violations = []
            chk.check_ledger_shape(Path(td), violations)
            self.assertTrue(
                any("has 1 cells" in v and "~~KL-12~~" in v for v in violations),
                f"expected the split KL-12 row to be flagged, got: {violations}",
            )

    def test_rejects_row_with_wrong_cell_count(self):
        with tempfile.TemporaryDirectory() as td:
            fx = CheckerFixture(Path(td))
            fx.write(
                "KNOWN_LIMITATIONS.md",
                self.HEADER
                + "| KL-10 | x | user-visible: no | pre-declared: yes | r |\n"
                + "| KL-06 | y | user-visible: no |\n",  # only 3 cells
            )
            violations = []
            chk.check_ledger_shape(Path(td), violations)
            self.assertTrue(any("has 3 cells" in v and "KL-06" in v for v in violations))
