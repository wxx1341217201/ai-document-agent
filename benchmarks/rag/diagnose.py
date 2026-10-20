"""Compare labeled evidence availability under unchanged M19B data and rules.

These diagnostics do not assign semantic correctness or replace human review.
Usage: python benchmarks/rag/diagnose.py new-report.json --baseline old-report.json
"""

import argparse
import json
import math
from pathlib import Path

from audit import TYPES, audit, normalize, require


def summary(cases):
    answerable = [case for case in cases if case["sample"]["answerable"]]
    succeeded = [case for case in answerable if case["observation"]["executed"]
                 and case["observation"]["errorCode"] is None]
    complete_chunks = complete_quotes = 0
    for case in succeeded:
        context = {item["citation"]["chunkId"]: normalize(item["contextText"])
                   for item in case["observation"]["evidence"]}
        facts = case["sample"]["requiredFacts"]
        complete_chunks += all(any(cid in context for cid in fact["supportingChunkIds"]) for fact in facts)
        complete_quotes += all(any(cid in context and normalize(fact["sourceQuote"]) in context[cid]
                                   for cid in fact["supportingChunkIds"]) for fact in facts)
    elapsed = sorted(case["observation"]["elapsedMillis"] for case in cases if case["observation"]["executed"])
    context_characters = [sum(len(item["contextText"]) for item in case["observation"]["evidence"]) for case in succeeded]
    return {"answerable": len(answerable), "successfulAnswerable": len(succeeded),
            "allLabeledSupportChunksPresent": complete_chunks, "allLabeledQuotesVisible": complete_quotes,
            "labeledQuoteVisibilityRate": complete_quotes / len(answerable) if answerable else None,
            "supportChunksMissing": len(succeeded) - complete_chunks,
            "labeledQuotesTrimmed": complete_chunks - complete_quotes,
            "structurallyIncorrect": sum(case["evaluation"]["status"] == "INCORRECT" for case in answerable),
            "errors": sum(case["evaluation"]["status"] == "ERROR" for case in cases),
            "notRun": sum(case["evaluation"]["status"] == "NOT_RUN" for case in cases),
            "degraded": sum(case["observation"]["degraded"] for case in cases),
            "successfulAnswerableMeanContextCharacters": sum(context_characters) / len(context_characters) if context_characters else None,
            "caseElapsedP95Millis": elapsed[math.ceil(len(elapsed) * 0.95) - 1] if elapsed else None}


def diagnose(path, baseline=None):
    audit(path)
    report = json.loads(path.read_text(encoding="utf-8"))
    result = {"diagnosticVersion": "m19b-evidence-availability-v1", "runId": report["runId"],
              "report": path.name, "overall": summary(report["cases"]),
              "byType": {kind: summary([case for case in report["cases"] if case["sample"]["type"] == kind])
                         for kind in TYPES},
              "qaAccuracy": report["overall"]["qaAccuracy"],
              "qaAccuracyUpperBound": report["overall"]["qaAccuracyUpperBound"],
              "note": "Fixed draft-label evidence availability and structural failures; not final QA accuracy."}
    if baseline:
        audit(baseline)
        previous = json.loads(baseline.read_text(encoding="utf-8"))
        require(report["policyVersion"] == previous["policyVersion"]
                and report["dataset"]["sha256"] == previous["dataset"]["sha256"]
                and report["dataset"]["referencedChunkFingerprint"] == previous["dataset"]["referencedChunkFingerprint"]
                and {case["sample"]["id"]: case["sample"] for case in report["cases"]}
                == {case["sample"]["id"]: case["sample"] for case in previous["cases"]}, "INCOMPARABLE_BASELINE")
        result["baseline"] = {"runId": previous["runId"], "report": baseline.name,
                              "overall": summary(previous["cases"]),
                              "byType": {kind: summary([case for case in previous["cases"] if case["sample"]["type"] == kind])
                                         for kind in TYPES},
                              "qaAccuracy": previous["overall"]["qaAccuracy"],
                              "qaAccuracyUpperBound": previous["overall"]["qaAccuracyUpperBound"]}
        result["comparisonStatus"] = "SAME_DATASET_CORPUS_AND_POLICY"
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--output", type=Path)
    options = parser.parse_args()
    result = diagnose(options.report, options.baseline)
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    path = options.output or options.report.with_name(options.report.stem + "-diagnostic.json")
    path.write_text(output, encoding="utf-8", newline="\n")
    print(output)
