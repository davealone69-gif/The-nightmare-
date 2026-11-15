import tempfile
import unittest
from pathlib import Path

from nightmare.contracts import Output
from nightmare.seal import SealedLog, verify_chain
from nightmare.skills import SkillCapsule, SkillLibrary, identity
from nightmare.ghosts import Mandela, Matrix
from nightmare.anchor import Anchor
from nightmare.reset import ResetPlan, total_reset
from nightmare.fixed_suite import FIXED_PROMPTS, run_contract_suite

WEIGHTS = "a" * 64

class CoreTests(unittest.TestCase):
    def test_output_contract_and_abstention(self):
        good = Output("r1", "worker", "abstain", abstain_reason="no evidence",
                      weights_hash=WEIGHTS)
        self.assertEqual(good.validate({WEIGHTS}), [])
        bad = Output("r1", "worker", "ok", weights_hash="")
        self.assertTrue(bad.validate())

    def test_sealed_log_detects_tampering(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "events.jsonl"
            log = SealedLog(path)
            log.append("human", "decision", {"decision": "reject"})
            self.assertTrue(verify_chain(path))
            path.write_text(path.read_text().replace('"reject"', '"approve"'))
            self.assertFalse(verify_chain(path))

    def test_skill_capsules_and_persistent_approval(self):
        with tempfile.TemporaryDirectory() as tmp:
            library = SkillLibrary(tmp)
            first = library.add(SkillCapsule("retrieval", 1, "retrieval_policy", {"top_k": 4}))
            second = library.evolve(first.hash, {"top_k": 5})
            self.assertEqual(second.parent, first.hash)
            with self.assertRaises(PermissionError):
                library.approve(second.hash, human_authorized=False, tests_passed=True)
            library.approve(second.hash, human_authorized=True, tests_passed=True)
            reloaded = SkillLibrary(tmp)
            self.assertIn(second.hash, reloaded.approved_hashes)
            self.assertEqual(identity(WEIGHTS, [second])["weights_hash"], WEIGHTS)

    def test_ghosts_observe_without_correction_interface(self):
        outputs = [
            Output("r", "w1", "ok", {"answer": "same"}, 0.8, weights_hash=WEIGHTS),
            Output("r", "w2", "ok", {"answer": "same"}, 0.8, weights_hash=WEIGHTS),
        ]
        ghost = Mandela()
        event = ghost.observe("r", outputs)
        self.assertIsNotNone(event)
        self.assertFalse(hasattr(ghost, "correct"))
        matrix = Matrix()
        self.assertIsNone(matrix.observe("r", [], set()))

    def test_anchor_requires_human_decision(self):
        anchor = Anchor("anchor-1", 0, 9999999999)
        view = anchor.merged_view([], [{"created_at": 1.0, "payload": {"signal": "x"}}])
        self.assertEqual(view[0]["_label"], "second look")
        result = anchor.approve(view[0], human_decision="reject")
        self.assertIs(result["human"], True)

    def test_reset_fails_closed_when_network_or_restart_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "scratch"
            target.mkdir()
            (target / "state").write_text("old")
            plan = ResetPlan(
                baseline_env={"PATH": "/usr/bin"}, fixed_seed=7,
                network_block=lambda: False, network_is_blocked=lambda: False,
                restart_model_server=lambda: True, targets={"scratch": str(target)})
            result = total_reset(plan)
            self.assertFalse(result["complete"])
            self.assertFalse(target.exists())

    def test_fixed_suite_has_ten_prompts(self):
        self.assertEqual(len(FIXED_PROMPTS), 10)
        self.assertEqual([p["id"] for p in FIXED_PROMPTS],
                         ["A01","A02","A03","A04","B01","B02","B03","C01","C02","C03"])

if __name__ == "__main__":
    unittest.main()
