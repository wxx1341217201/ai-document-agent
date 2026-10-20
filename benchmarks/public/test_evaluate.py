"""Boundary tests for grading mistakes that could inflate reported metrics."""

import unittest

from evaluate import aggregate_bucket, answer_scores, binding_maps, latency_statistics, qa_score, retrieval_score


class PublicGradingTest(unittest.TestCase):
    def setUp(self):
        import hashlib
        self.binding = {"knowledgeBases": {"hotpotqa": 7}, "documents": [
            {"documentId": i, "sourceId": "s" + str(i), "knowledgeBaseId": 7,
             "chunks": [{"chunkId": i, "content": text, "contentHash": hashlib.sha256(text.encode()).hexdigest()}]}
            for i, text in [(1, "Paris is in France."), (2, "The mentioned capital is Paris.")]]}
        _, self.chunks = binding_maps(self.binding)
        self.sample = {"dataset": "hotpotqa", "answers": ["France"], "relevantDocumentIds": ["s1", "s2"],
                       "evidence": [{"documentId": "s1", "text": "Paris is in France."},
                                    {"documentId": "s2", "text": "The mentioned capital is Paris."}]}
        self.citations = [{"citationId": "C" + str(i), "chunkId": i, "documentId": i} for i in (1, 2)]

    def test_exact_answer_without_both_sources_does_not_pass(self):
        score = qa_score(self.sample, {"answer": "France [C1]", "citations": self.citations[:1]}, self.chunks, 7)
        self.assertTrue(score["exactMatch"])
        self.assertFalse(score["sourceCitedExactMatch"])

    def test_invented_citation_does_not_pass(self):
        score = qa_score(self.sample, {"answer": "France [C9]", "citations": self.citations}, self.chunks, 7)
        self.assertTrue(score["exactMatch"])
        self.assertFalse(score["citationsValid"])

    def test_complete_public_qa_task_can_pass(self):
        score = qa_score(self.sample, {"answer": "France [C1][C2]", "citations": self.citations, "status": "COMPLETED"}, self.chunks, 7, True)
        self.assertTrue(score["taskPassed"])

    def test_agent_failed_status_cannot_pass(self):
        score = qa_score(self.sample, {"answer": "France [C1][C2]", "citations": self.citations, "status": "TIMED_OUT"}, self.chunks, 7, True)
        self.assertFalse(score["taskPassed"])

    def test_execution_error_is_zero_even_with_an_answer(self):
        score = qa_score(self.sample, {"answer": "France [C1][C2]", "citations": self.citations, "errorCode": "TIMEOUT"}, self.chunks, 7)
        self.assertFalse(score["exactMatch"])
        self.assertEqual(score["f1"], 0)

    def test_cross_scope_evidence_rejected(self):
        with self.assertRaises(ValueError):
            qa_score(self.sample, {"answer": "France [C1]", "citations": self.citations}, self.chunks, 8)

    def test_one_hop_is_half_recall_and_not_full_coverage(self):
        score = retrieval_score(self.sample, {"top5": [{"chunkId": 1, "documentId": 1}]}, self.chunks, 7)
        self.assertEqual(score["documentRecallAt5"], .5)
        self.assertTrue(score["documentHitAt5"])
        self.assertFalse(score["allGoldDocumentsAt5"])

    def test_explanation_is_not_silently_extracted_as_correct(self):
        self.assertFalse(answer_scores(self.sample, "The answer is France [C1]")["exactMatch"])

    def test_cmrc_aliases_and_punctuation(self):
        sample = {"dataset": "cmrc2018", "answers": ["MySQL", "关系数据库"]}
        self.assertTrue(answer_scores(sample, "**MySQL** [C1]")["exactMatch"])
        self.assertFalse(answer_scores(sample, "历史保存在 MySQL [C1]")["exactMatch"])

    def test_nearest_rank_and_error_accounting(self):
        rows = [{"phase": "latency", "durationNanos": i * 1_000_000, "errorCode": ""} for i in range(1, 501)]
        rows.append({"phase": "latency", "durationNanos": 90_000_000_000, "errorCode": "TIMEOUT"})
        score = latency_statistics(rows)
        self.assertEqual(score["p95Ms"], 475)
        self.assertEqual(score["requests"], 501)
        self.assertEqual(score["errors"], 1)

    def test_unrun_samples_make_final_score_unknown_not_zero(self):
        bucket = aggregate_bucket([{"exactMatch": True, "error": False, "notRun": False},
                                   {"exactMatch": False, "error": True, "notRun": True}], ("exactMatch",))
        self.assertIsNone(bucket["exactMatch"])
        self.assertEqual(bucket["attempted"], 1)
        self.assertEqual(bucket["notRun"], 1)
        self.assertEqual(bucket["errors"], 0)
        self.assertEqual(bucket["scoreBoundsWithUnrunSamples"]["exactMatch"], {"lowerBound": .5, "upperBound": 1})


if __name__ == "__main__":
    unittest.main()
