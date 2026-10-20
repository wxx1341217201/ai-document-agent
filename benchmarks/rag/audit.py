"""Independently audit M19B frozen evidence, denominators and final verdicts.

Uses only the Python standard library. An audit never supplies human judgments.
Usage: python benchmarks/rag/audit.py reports/rag-....json
"""

import argparse
import hashlib
import json
import math
import re
import unicodedata
from collections import Counter
from pathlib import Path


TYPES = ("SINGLE_DOCUMENT", "MULTI_CHUNK", "CROSS_DOCUMENT", "MULTI_TURN", "UNANSWERABLE")


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def jsonl(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def normalize(text):
    text = unicodedata.normalize("NFKC", text).lower()
    return " ".join("".join(" " if unicodedata.category(ch)[0] in "PZ" or ch.isspace() else ch
                            for ch in text).split())


def paired_path(report_path, recorded):
    local = report_path.parent / recorded.replace("\\", "/").rsplit("/", 1)[-1]
    return local if local.exists() else Path(recorded)


def citation_failures(sample, observation):
    answer = observation["answer"]
    if not answer or not answer.strip():
        return ["EMPTY_VISIBLE_ANSWER"]
    failures, context, chunk_ids = [], {}, set()
    for evidence in observation["evidence"]:
        citation = evidence["citation"]
        cid = citation["citationId"]
        if (evidence["knowledgeBaseId"] != sample["knowledgeBaseId"] or not cid
                or not re.fullmatch(r"C[1-9]\d*", cid) or cid in context or citation["chunkId"] in chunk_ids):
            failures.append("INVALID_OR_FOREIGN_CONTEXT")
        context.setdefault(cid, evidence)
        chunk_ids.add(citation["chunkId"])
    used = {item["citationId"]: item for item in observation["citations"]}
    if len(used) != len(observation["citations"]):
        failures.append("DUPLICATE_CITATION_METADATA")
    tokens = {token[1:-1] for token in re.findall(r"\[C[1-9]\d*]", answer)}
    if any(not re.fullmatch(r"\[C[1-9]\d*]", token) for token in re.findall(r"\[C[^]]*]", answer, re.I)):
        failures.append("MALFORMED_CITATION_TOKEN")
    if tokens != used.keys():
        failures.append("ANSWER_CITATION_METADATA_MISMATCH")
    for citation in observation["citations"]:
        cid = citation["citationId"]
        if cid not in context or context[cid]["citation"] != citation:
            failures.append("CITATION_NOT_IN_CURRENT_CONTEXT:" + str(cid))
        if sample["answerable"] and citation["chunkId"] not in sample["acceptableChunkIds"]:
            failures.append("CITATION_OUTSIDE_ACCEPTABLE_RANGE:" + str(cid))
    if sample["answerable"] and not used:
        failures.append("ANSWERABLE_REQUIRES_REAL_CITATION")
    return list(dict.fromkeys(failures))


def verdict(report, case):
    sample, observation = case["sample"], case["observation"]
    review = case["evaluation"]["humanReview"]
    if review:
        require(review["runId"] == report["runId"] and review["datasetSha256"] == report["dataset"]["sha256"]
                and review["caseId"] == sample["id"] and review["resultSha256"] == case["resultSha256"], "UNBOUND_REVIEW")
        require(bool(review["reviewer"].strip()) and bool(review["reviewedAt"]), "INCOMPLETE_REVIEW")
        facts = {fact["factId"]: fact for fact in review["facts"]}
        require(len(facts) == len(review["facts"]) and facts.keys() == {fact["id"] for fact in sample["requiredFacts"]}
                and all(isinstance(fact["covered"], bool) for fact in facts.values()), "INCOMPLETE_FACT_REVIEW")
    if observation["errorCode"] is not None:
        return "ERROR" if observation["executed"] else "NOT_RUN", [observation["errorCode"]]
    require(observation["executed"], "UNEXECUTED_WITHOUT_REASON")
    failures = citation_failures(sample, observation)
    incorrect = "INCORRECT" if sample["answerable"] else "REFUSAL_INCORRECT"
    if failures:
        return incorrect, failures
    if not review or review["referenceVerified"] is not True:
        return "PENDING_REVIEW", ["HUMAN_REVIEW_REQUIRED" if not review else "REFERENCE_LABELS_NOT_VERIFIED"]
    if not sample["answerable"]:
        return ("REFUSAL_CORRECT", []) if review["refusalCorrect"] is True else ("REFUSAL_INCORRECT", ["REFUSAL_INCORRECT"])
    used = {item["citationId"]: item for item in observation["citations"]}
    covered = 0
    for fact in sample["requiredFacts"]:
        assessment = facts[fact["id"]]
        if not assessment["covered"]:
            require(not assessment["citationIds"], "UNCOVERED_FACT_WITH_CITATIONS")
            continue
        if (not assessment["citationIds"] or any(cid not in used or used[cid]["chunkId"] not in fact["supportingChunkIds"]
                                               for cid in assessment["citationIds"])):
            failures.append("FACT_CITATION_UNSUPPORTED:" + fact["id"])
        else:
            covered += 1
    coverage = covered / len(sample["requiredFacts"])
    require(case["evaluation"]["reviewedFactCoverage"] == coverage, "WRONG_REVIEWED_COVERAGE")
    if coverage < sample["factCoverageThreshold"]:
        failures.append("REQUIRED_FACT_COVERAGE_BELOW_THRESHOLD")
    if review["criticalConflictFree"] is not True:
        failures.append("CRITICAL_FACT_CONFLICT")
    if review["coreClaimsSupported"] is not True:
        failures.append("CORE_CLAIMS_NOT_SUPPORTED_BY_CITATIONS")
    return ("INCORRECT", failures) if failures else ("CORRECT", [])


def summarize(cases):
    counts = Counter(case["evaluation"]["status"] for case in cases)
    qa = [case for case in cases if case["sample"]["answerable"]]
    refusal = [case for case in cases if not case["sample"]["answerable"]]

    def pending(case):
        review = case["evaluation"]["humanReview"]
        observation = case["observation"]
        return (case["evaluation"]["status"] == "PENDING_REVIEW" or observation["executed"]
                and observation["errorCode"] is None and (not review or review["referenceVerified"] is not True))

    def accuracy(group, correct_status):
        if not group or any(pending(case) or case["evaluation"]["status"] == "NOT_RUN" for case in group):
            return None
        return sum(case["evaluation"]["status"] == correct_status for case in group) / len(group)

    undecided = sum(case["evaluation"]["status"] in ("PENDING_REVIEW", "NOT_RUN") for case in qa)
    return {"samples": len(cases), "answerable": len(qa), "correct": counts["CORRECT"],
            "qaAccuracy": accuracy(qa, "CORRECT"), "qaAccuracyLowerBound": counts["CORRECT"] / len(qa) if qa else None,
            "qaAccuracyUpperBound": (counts["CORRECT"] + undecided) / len(qa) if qa else None,
            "unanswerable": len(refusal), "refusalCorrect": counts["REFUSAL_CORRECT"],
            "refusalAccuracy": accuracy(refusal, "REFUSAL_CORRECT"),
            "humanReviewed": sum(bool(case["evaluation"]["humanReview"] and case["evaluation"]["humanReview"]["referenceVerified"] is True)
                                 for case in cases), "pendingReview": sum(pending(case) for case in cases),
            "incorrect": counts["INCORRECT"], "refusalIncorrect": counts["REFUSAL_INCORRECT"],
            "errors": counts["ERROR"], "notRun": counts["NOT_RUN"],
            "degraded": sum(case["observation"]["degraded"] for case in cases)}


def audit(report_path):
    report = json.loads(report_path.read_text(encoding="utf-8"))
    require(report["schemaVersion"] == "m19b-report-v1" and report["policyVersion"] == "m19b-human-final-v2", "UNSUPPORTED_VERSION")
    config = report["configuration"]
    dataset_path = paired_path(report_path, config["datasetSnapshotPath"])
    require(sha(dataset_path) == report["dataset"]["sha256"], "DATASET_HASH_MISMATCH")
    labels = jsonl(dataset_path)
    require(len(labels) >= 200 and len(labels) == report["dataset"]["samples"], "DATASET_DENOMINATOR")
    require({sample["type"] for sample in labels} == set(TYPES), "MISSING_QUESTION_TYPE")
    corpus = {}
    if "corpusSnapshotPath" in config:
        corpus_path = paired_path(report_path, config["corpusSnapshotPath"])
        require(sha(corpus_path) == config["corpusSha256"], "CORPUS_HASH_MISMATCH")
        sources = jsonl(corpus_path)
        fingerprint = hashlib.sha256(json.dumps(sources, ensure_ascii=False, separators=(",", ":")).encode("utf-8")).hexdigest()
        require(fingerprint == report["dataset"]["referencedChunkFingerprint"], "CORPUS_FINGERPRINT_MISMATCH")
        corpus = {source["chunkId"]: source for source in sources}
        require(len(corpus) == len(sources) == config["corpusChunks"], "CORPUS_DUPLICATE_OR_COUNT")
        require(all(source["knowledgeBaseId"] == report["dataset"]["knowledgeBaseId"] for source in sources), "CORPUS_SCOPE")
        for sample in labels:
            require(set(sample["acceptableChunkIds"]) <= corpus.keys(), "MISSING_LABELED_CHUNK")
            for fact in sample["requiredFacts"]:
                require(all(normalize(fact["sourceQuote"]) in normalize(corpus[cid]["content"])
                            for cid in fact["supportingChunkIds"]), "MISLABELED_SOURCE_QUOTE")
    cases = report["cases"]
    require(len(cases) == len(labels) and len({case["sample"]["id"] for case in cases}) == len(labels), "CASE_DENOMINATOR")
    require({case["sample"]["id"]: case["sample"] for case in cases} == {sample["id"]: sample for sample in labels}, "LABEL_MISMATCH")
    for case in cases:
        observation = case["observation"]
        require(math.isfinite(observation["elapsedMillis"]) and observation["elapsedMillis"] >= 0, "INVALID_DURATION")
        trace = observation.get("retrievalTrace")
        if trace:
            require(trace["strategyVersion"] == config["queryExpansionVersion"]
                    and 1 <= len(trace["queries"]) <= config["maxRetrievalQueries"] <= 3
                    and len(set(trace["queries"])) == len(trace["queries"])
                    and all(isinstance(query, str) and 0 < len(query) <= 2000 for query in trace["queries"])
                    and 0 <= trace["failedQueries"] <= len(trace["queries"])
                    and (trace["failedQueries"] == 0 or observation["degraded"]), "INVALID_RETRIEVAL_TRACE")
        status, failures = verdict(report, case)
        require(case["evaluation"]["status"] == status and case["evaluation"]["failureReasons"] == failures,
                "VERDICT_MISMATCH:" + case["sample"]["id"])
        if observation["errorCode"] is None:
            require(bool(corpus), "EXECUTED_WITHOUT_FROZEN_CORPUS")
            for evidence in observation["evidence"]:
                citation = evidence["citation"]
                source = corpus.get(citation["chunkId"])
                require(source and evidence["knowledgeBaseId"] == source["knowledgeBaseId"] == case["sample"]["knowledgeBaseId"]
                        and source["documentId"] == citation["documentId"]
                        and hashlib.sha256(source["content"].encode("utf-8")).hexdigest() == evidence["sourceContentSha256"]
                        and bool(evidence["contextText"]) and source["content"].startswith(evidence["contextText"])
                        and bool(citation["quote"]) and source["content"].startswith(citation["quote"])
                        and source["pageFrom"] == citation["pageFrom"] and source["pageTo"] == citation["pageTo"], "UNVERIFIED_EVIDENCE")
    expected = summarize(cases)
    require(report["overall"] == expected, "OVERALL_METRIC_MISMATCH")
    for kind in TYPES:
        require(report["byType"][kind] == summarize([case for case in cases if case["sample"]["type"] == kind]), "TYPE_METRIC_MISMATCH:" + kind)
    invalid = expected["errors"] or expected["notRun"] or expected["degraded"] or config.get("corpusStable") is False
    status = "INVALID_OR_INCOMPLETE_RUN" if invalid else "NEEDS_HUMAN_REVIEW" if expected["pendingReview"] else "COMPLETE"
    target = ("NOT_EVALUABLE" if status != "COMPLETE" or config["mode"] != "LIVE" or expected["qaAccuracy"] is None
              else "MET" if expected["qaAccuracy"] >= 0.83 else "NOT_MET")
    require(report["status"] == status and report["targetStatus"] == target, "TARGET_MISMATCH")
    failures = [{"caseId": case["sample"]["id"], "question": case["sample"]["question"], "type": case["sample"]["type"],
                 "answer": case["observation"]["answer"], "citations": case["observation"]["citations"],
                 "status": case["evaluation"]["status"], "failureReasons": case["evaluation"]["failureReasons"]}
                for case in cases if case["evaluation"]["status"] not in ("CORRECT", "REFUSAL_CORRECT", "PENDING_REVIEW")]
    require(report["failureDetails"] == failures, "FAILURE_DETAIL_MISMATCH")
    failure_path = report_path.with_name(report_path.stem + "-failures.jsonl")
    require(jsonl(failure_path) == failures, "FAILURE_EXPORT_MISMATCH")
    journal_path = report_path.parent / ("rag-" + report["runId"] + "-observations.jsonl")
    if journal_path.exists():
        journal_rows = jsonl(journal_path)
        journal = {case["sample"]["id"]: case for case in journal_rows}
        require(len(journal_rows) == len(journal) == len(labels) and journal.keys() == {sample["id"] for sample in labels}, "JOURNAL_DENOMINATOR")
        for case in cases:
            require(all(case[field] == journal[case["sample"]["id"]][field] for field in ("sample", "observation", "resultSha256")), "JOURNAL_OBSERVATION_MISMATCH")
    return {"auditStatus": "PASSED", "runId": report["runId"], "report": report_path.name,
            "policyVersion": report["policyVersion"], "corpusChunks": len(corpus), "overall": expected,
            "targetStatus": target, "note": "Evidence and arithmetic audit; human judgments are not supplied or independently certified."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--output", type=Path)
    options = parser.parse_args()
    try:
        result = audit(options.report)
    except (ValueError, KeyError, OSError, TypeError) as error:
        result = {"auditStatus": "FAILED", "reason": str(error)}
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    path = options.output or options.report.with_name(options.report.stem + "-audit.json")
    path.write_text(output, encoding="utf-8", newline="\n")
    print(output)
    raise SystemExit(0 if result["auditStatus"] == "PASSED" else 1)
