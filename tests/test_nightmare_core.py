import tempfile
import unittest
from pathlib import Path

from nightmare.contracts import Output
from nightmare.seal import SealedLog, verify_chain
from nightmare.skills import SkillCapsule, SkillLibrary, identity
from nightmare.ghosts import Mandela, Matrix
from nightmare.anchor import Anchor
from nightmare.reset import ResetPlan, total_reset
from nightmare.fixed_suite import FIXED_PROMPTS

WEIGHTS = "a" * 64
CODE_HASH = "b" * 64


class CoreTests(unittest.TestCase):
    def test_output_contract_distinguishes_model_and_ghost_code(self):
        good = Output("r1", "worker", "abstain", abstain_reason="no evidence",
                      component_hash=WEIGHTS, component_kind="model")
        self.assertEqual(good.validate({WEIGHTS}), [])
        self.assertIn("approved model artifact manifest is required", good.validate())
        ghost = Output("r1", "ghost.mandela", "ok", {"signal": "drift"},
                       component_hash=CODE_HASH, component_kind="code")
        self.assertEqual(ghost.validate(approved_code_components={CODE_HASH}), [])
        self.assertIn("approved code artifact manifest is required", ghost.validate())
        self.assertIn("component_hash is not an approved code artifact",
                      ghost.validate(approved_code_components={WEIGHTS}))
        bad_kind = Output("r1", "ghost.matrix", "ok", {"signal": "x"},
                          component_hash=WEIGHTS, component_kind="model")
        self.assertTrue(any("ghost output requires" in e for e in bad_kind.validate()))
        bad = Output("r1", "worker", "ok", component_hash="")
        self.assertTrue(bad.validate())

    def test_sealed_log_detects_tampering_and_blinds_anchor_slice(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "events.jsonl"
            log = SealedLog(path)
            log.append("ghost.mandela", "observation", {
                "role": "ghost.mandela", "component_hash": CODE_HASH,
                "payload": {"signal": "drift", "ghost_id": "mandela"}})
            log.append("human-anchor", "human_decision", {"decision": "defer"})
            self.assertTrue(verify_chain(path))
            projection = log.anchor_slice(0, 2)
            self.assertEqual(projection["entries"][0]["actor"], "monitor")
            self.assertEqual(projection["entries"][0]["kind"], "observation")
            self.assertNotIn("component_hash", projection["entries"][0]["data"])
            self.assertNotIn("ghost_id", projection["entries"][0]["data"]["payload"])
            self.assertNotIn("ghost.mandela", str(projection))
            self.assertEqual(projection["entries"][0]["data"]["payload"]["signal"], "monitor_flag")
            path.write_text(path.read_text().replace('"defer"', '"approve"'))
            self.assertFalse(verify_chain(path))

    def test_skill_capsules_are_deeply_frozen_and_hash_pinned(self):
        nested = {"policy": {"allowed": ["read", "cite"]}}
        capsule = SkillCapsule("retrieval", 1, "retrieval_policy", nested)
        original_hash = capsule.hash
        nested["policy"]["allowed"].append("write")
        self.assertEqual(capsule.hash, original_hash)
        self.assertEqual(capsule.body["policy"]["allowed"], ("read", "cite"))
        with self.assertRaises(TypeError):
            capsule.body["policy"]["allowed"] += ("write",)
        with self.assertRaises(TypeError):
            capsule.body["policy"]["new"] = True
        capsule.validate()
        with tempfile.TemporaryDirectory() as tmp:
            library = SkillLibrary(tmp)
            library.add(capsule)
            child = library.evolve(capsule.hash, {"top_k": 5})
            self.assertEqual(child.parent, capsule.hash)
            with self.assertRaises(PermissionError):
                library.approve(child.hash, human_authorized=False, tests_passed=True)
            library.approve(child.hash, human_authorized=True, tests_passed=True)
            reloaded = SkillLibrary(tmp)
            self.assertIn(child.hash, reloaded.approved_hashes)
            self.assertEqual(identity(WEIGHTS, [child])["weights_hash"], WEIGHTS)

    def test_ghost_observation_uses_code_identity(self):
        outputs = [
            Output("r", "w1", "ok", {"answer": "same"}, 0.8,
                   component_hash=WEIGHTS, component_kind="model"),
            Output("r", "w2", "ok", {"answer": "same"}, 0.8,
                   component_hash=WEIGHTS, component_kind="model"),
        ]
        ghost = Mandela()
        event = ghost.observe("r", outputs)
        self.assertIsNotNone(event)
        self.assertEqual(event.component_kind, "code")
        self.assertEqual(event.validate(approved_code_components={event.component_hash}), [])
        self.assertEqual(Mandela().component_hash, ghost.component_hash)
        self.assertFalse(hasattr(ghost, "correct"))
        matrix = Matrix()
        self.assertIsNone(matrix.observe("r", [], set()))

    def test_anchor_blindness_solo_recommitment_and_rubber_stamp_window(self):
        anchor = Anchor("anchor-1", 0, 9999999999)
        anchor_tmp = tempfile.TemporaryDirectory()
        anchor_log = SealedLog(Path(anchor_tmp.name) / "anchor.jsonl")
        self.assertTrue(anchor.monthly_recommitment_due(now=100))
        self.assertTrue(anchor.paused)
        anchor.solo_recommit(slice_reopened=True, slice_reread=True,
                             stop_button_tested=True, human_confirmation=True, sealed_log=anchor_log, now=100)
        self.assertFalse(anchor.paused)
        self.assertFalse(anchor.rubber_stamp_check()["rotate_early"], "fewer than 20 decisions must not trigger")
        view = anchor.merged_view(
            [{"role": "ghost.mandela", "component_hash": CODE_HASH,
              "payload": {"signal": "meaning"}}],
            [{"role": "ghost.matrix", "component_hash": "c" * 64,
              "payload": {"signal": "mechanism"}}])
        self.assertEqual(len(view), 2)
        self.assertNotIn("ghost.", str(view))
        with self.assertRaises(PermissionError):
            anchor.approve({"role": "ghost.mandela", "_label": "review"}, human_decision="approve", rationale="Reviewed evidence supports approval")
        for item in view:
            self.assertNotIn("role", item)
            self.assertNotIn("component_hash", item)
            self.assertIn(item["_label"], {"review", "second look"})
        for i in range(19):
            anchor.approve(view[0], human_decision="approve", rationale="Reviewed evidence supports approval")
        anchor.approve(view[1], human_decision="reject", rationale="Conflicting evidence requires rejection")
        status = anchor.rubber_stamp_check()
        self.assertTrue(status["rotate_early"])
        self.assertEqual(status["window_decisions"], 20)
        with self.assertRaises(PermissionError):
            anchor.solo_recommit(slice_reopened=True, slice_reread=True,
                                 stop_button_tested=False, human_confirmation=True, sealed_log=anchor_log, now=100)
        record = anchor.solo_recommit(slice_reopened=True, slice_reread=True,
                                      stop_button_tested=True, human_confirmation=True, sealed_log=anchor_log, now=100)
        self.assertEqual(record["kind"], "solo_monthly_recommitment")
        self.assertTrue(verify_chain(anchor_log.path))
        self.assertTrue(anchor.paused, "a monthly re-commitment must not clear a separate safety pause")
        trigger = anchor.event_trigger("anchor_reports_unsafe")
        self.assertTrue(trigger["system_paused"])
        with self.assertRaises(RuntimeError):
            anchor.approve(view[0], human_decision="defer", rationale="Further review is required")
        with tempfile.TemporaryDirectory() as tmp:
            resume_log = SealedLog(Path(tmp) / "resume.jsonl")
            with self.assertRaises(PermissionError):
                anchor.resume_after_review(independent_reviewer_id="owner", owner_id="owner",
                    review_evidence_ref="review-1", owner_authorization_ref="auth-1",
                    sealed_log=resume_log)
            record = anchor.resume_after_review(independent_reviewer_id="independent-reviewer",
                owner_id="project-owner", review_evidence_ref="review-1",
                owner_authorization_ref="auth-1", sealed_log=resume_log)
            self.assertEqual(record["event"], "resume_after_independent_review")
            self.assertTrue(verify_chain(resume_log.path))
        self.assertFalse(anchor.paused)
        anchor_tmp.cleanup()

    def test_reset_uses_restarted_server_identity_endpoint(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "scratch"
            target.mkdir()
            (target / "state").write_text("old")
            calls = []
            plan = ResetPlan(
                baseline_env={"PATH": "/usr/bin"}, fixed_seed=7,
                network_block=lambda: True, network_is_blocked=lambda: True,
                restart_model_server=lambda: True, expected_weights_hash=WEIGHTS,
                query_model_identity_endpoint=lambda: calls.append("queried") or {"weights_hash": "f" * 64},
                targets={"scratch": str(target)})
            result = total_reset(plan)
            self.assertEqual(calls, ["queried"])
            self.assertFalse(result["complete"])
            self.assertFalse(result["model_hash_verified"])
            self.assertFalse(target.exists())
            good_plan = ResetPlan(
                baseline_env={"PATH": "/usr/bin"}, fixed_seed=7,
                network_block=lambda: True, network_is_blocked=lambda: True,
                restart_model_server=lambda: True, expected_weights_hash=WEIGHTS,
                query_model_identity_endpoint=lambda: {"artifact_hash": WEIGHTS}, targets={})
            good_result = total_reset(good_plan)
            self.assertTrue(good_result["model_hash_verified"])
            self.assertTrue(good_result["complete"])

    def test_fixed_suite_has_ten_prompts_and_tier_c_is_diagnostic(self):
        self.assertEqual(len(FIXED_PROMPTS), 10)
        self.assertEqual([p["id"] for p in FIXED_PROMPTS],
                         ["A01", "A02", "A03", "A04", "B01", "B02", "B03", "C01", "C02", "C03"])
        self.assertTrue(all(p["tier"] == "C" for p in FIXED_PROMPTS[-3:]))


if __name__ == "__main__":
    unittest.main()
