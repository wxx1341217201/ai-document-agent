import unittest

from recheck_recovery import FAULTS, compare_summary, summarize, target_status


def cases(count=1000):
    faults = sorted(FAULTS)
    return [{"sample": {"fault": faults[index % len(faults)]}, "attempted": True,
             "faultVerified": True, "recoveryMillis": index + 1,
             "evaluation": {"outcome": "RECOVERED"}} for index in range(count)]


LIVE = {"mode": "LIVE", "realStorageVerified": True}


class RecoveryRecheckTest(unittest.TestCase):
    def test_unknown_recovery_blocks_zero_loss_claim(self):
        rows = cases()
        rows[0]["evaluation"]["outcome"] = "RECOVERY_FAILED"
        rows[0]["faultVerified"] = False
        summary = summarize(rows)
        self.assertIsNone(summary["sessionLossRate"])
        self.assertEqual(0, summary["observedLossLowerBound"])
        self.assertAlmostEqual(99.9, summary["recoverySuccessRate"])
        self.assertEqual("NOT_EVALUABLE", target_status(rows, LIVE))

    def test_strict_half_percent_boundary(self):
        rows = cases()
        for row in rows[:4]:
            row["evaluation"]["outcome"] = "SESSION_LOST"
        self.assertEqual(.4, summarize(rows)["sessionLossRate"])
        self.assertEqual("MET", target_status(rows, LIVE))
        rows[4]["evaluation"]["outcome"] = "SESSION_LOST"
        self.assertEqual(.5, summarize(rows)["sessionLossRate"])
        self.assertEqual("NOT_MET", target_status(rows, LIVE))

    def test_unrun_keeps_denominator_and_excludes_nonattempt_duration(self):
        rows = cases()
        rows[0].update(attempted=False, faultVerified=False, recoveryMillis=None)
        rows[0]["evaluation"]["outcome"] = "NOT_RUN"
        summary = summarize(rows)
        self.assertEqual(1000, summary["planned"])
        self.assertEqual(999, summary["attempted"])
        self.assertEqual(1, summary["notRun"])
        self.assertEqual(951, summary["p95Millis"])
        self.assertIsNone(summary["sessionLossRate"])
        self.assertEqual("NOT_EVALUABLE", target_status(rows, LIVE))

    def test_smoke_or_unverified_environment_cannot_certify_target(self):
        self.assertEqual("NOT_EVALUABLE", target_status(cases(7), LIVE))
        self.assertEqual("NOT_EVALUABLE", target_status(cases(), {**LIVE, "realStorageVerified": False}))
        rows = cases()
        rows[0]["faultVerified"] = False
        self.assertEqual("NOT_EVALUABLE", target_status(rows, LIVE))

    def test_reported_zero_instead_of_unknown_is_integrity_error(self):
        issues = []
        compare_summary({"sessionLossRate": None}, {"sessionLossRate": 0}, "overall", issues)
        self.assertEqual(1, len(issues))


if __name__ == "__main__":
    unittest.main()
