"""Offline diagnostics only. Never changes labels, reviews, observations or task scores."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re
import unicodedata


def normalized(text):
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", text or "")).replace("’", "'").casefold().strip()


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def analyze(report_path, output_directory, baseline_path=None):
    report = read(report_path)
    corpus = read(report["configuration"]["corpusSnapshotPath"])
    sources = corpus["sources"]
    diagnostic_path = report["configuration"].get("diagnosticJournalPath")
    stages = {}
    if diagnostic_path and Path(diagnostic_path).exists():
        for line in Path(diagnostic_path).read_text(encoding="utf-8-sig").splitlines():
            item = json.loads(line)
            stages[(item["caseId"], item["turn"])] = item["diagnostics"]
    rows, all_rows = [], []
    for case in report["cases"]:
        task = case["task"]
        turns = case["observation"]["turns"]
        if not turns:
            continue
        turn = turns[-1]
        visible = turn["evidence"]
        facts = task["requiredFacts"]
        diagnostics = stages.get((task["caseId"], turn["turn"]), [])
        generation_inputs = [stage["details"] for stage in diagnostics if stage["stage"] == "model_input"
                             and stage["details"].get("phase") in ("RF_DRAFT", "RF_VERIFY_AND_REPAIR", "REACT", "SYNTHESIZE", "RETRIEVAL_FIRST")]
        actual_input = generation_inputs[-1] if generation_inputs else None
        actual_passages = None
        if actual_input is not None and "evidenceSnapshots" in actual_input:
            actual_passages = []
            for fingerprint in actual_input["evidenceSnapshots"]:
                if fingerprint["kind"] != "SOURCE_PREFIX":
                    continue
                source = sources[str(fingerprint["chunkId"])]
                prefix = source["content"].encode("utf-16-le")[:fingerprint["characters"] * 2].decode("utf-16-le")
                if hashlib.sha256(prefix.encode("utf-8")).hexdigest() != fingerprint["sha256"]:
                    raise ValueError(f"Actual input snapshot hash mismatch: {task['caseId']}")
                actual_passages.append(prefix)
        retrieval_stages = [stage["details"] for stage in diagnostics if stage["stage"] == "retrieval"]
        candidates = {candidate["chunkId"] for stage in retrieval_stages
                      for candidate in stage.get("trace", {}).get("candidates", [])}
        selected = {chunk for stage in retrieval_stages for chunk in stage.get("selectedChunkIds", [])}
        visible_ids = {item["citation"]["chunkId"] for item in visible}
        fact_rows = []
        for fact in facts:
            expected = set(fact["supportingChunkIds"])
            quote = normalized(fact["sourceQuote"])
            fact_rows.append({"factId": fact["id"], "sourceQuote": fact["sourceQuote"],
                              "supportingChunkIds": sorted(expected),
                              "candidateHit": bool(expected & candidates) if diagnostics else None,
                              "selectedHit": bool(expected & selected) if diagnostics else None,
                              "registeredHit": bool(expected & visible_ids),
                              "literalQuoteVisible": any(quote in normalized(item["contextText"]) for item in visible),
                              "literalQuoteInFinalGenerationInput": None if actual_passages is None else any(quote in normalized(text) for text in actual_passages),
                              "sameDocumentEquivalentCandidates": [int(chunk_id) for chunk_id, source in sources.items()
                                  if int(chunk_id) not in expected and quote in normalized(source["content"])
                                  and any(source["documentId"] == sources[str(chunk)]["documentId"] for chunk in expected)]})
        answer = turn.get("result") or {}
        bound_stages = [stage["details"] for stage in diagnostics
                        if stage["stage"] in ("claim_binding", "claim_verification_repair") and stage["details"].get("valid")]
        outside = [citation for citation in answer.get("citations", []) if citation["chunkId"] not in task["acceptableChunkIds"]]
        reasons = case["evaluation"]["failureReasons"]
        all_quotes = bool(facts) and all(fact["literalQuoteVisible"] for fact in fact_rows)
        if all_quotes:
            root = "CITATION_SELECTION_OR_EXTRA_CLAIM" if all(fact["registeredHit"] for fact in fact_rows) else "EQUIVALENT_SOURCE_REQUIRES_REVIEW"
        elif diagnostics and any(fact["candidateHit"] and not fact["selectedHit"] for fact in fact_rows):
            root = "CANDIDATE_DROPPED_BEFORE_CONTEXT"
        elif diagnostics and any(fact["selectedHit"] and not fact["literalQuoteVisible"] for fact in fact_rows):
            root = "CONTEXT_TRUNCATION_OR_EVICTION"
        elif diagnostics:
            root = "CANDIDATE_MISS_OR_WRONG_SCENE"
        else:
            root = "SUPPORT_NOT_VISIBLE_STAGE_UNOBSERVED"
        row = {"caseId": task["caseId"], "mode": task["mode"], "question": task["turns"][-1],
               "status": case["evaluation"]["status"], "primaryDiagnostic": root,
               "diagnosticOnly": True, "semanticReview": "PENDING_INDEPENDENT_HUMAN_REVIEW",
               "failureReasons": reasons, "answer": answer.get("answer"),
               "actualCitations": answer.get("citations", []),
               "lastValidDraftBindings": bound_stages[-1].get("bindings", []) if bound_stages else [],
               "draftBindingsAreFinalProof": False,
               "visibleChunkIds": sorted(visible_ids), "outsideCitations": outside, "facts": fact_rows,
               "allReferenceQuotesVisible": all_quotes, "stageObservationsAvailable": bool(diagnostics)}
        row["allReferenceQuotesInFinalGenerationInput"] = None if actual_passages is None else bool(facts) and all(
            fact["literalQuoteInFinalGenerationInput"] for fact in fact_rows)
        all_rows.append(row)
        if any("CITATION_OUTSIDE_ACCEPTABLE_RANGE" in reason for reason in reasons):
            rows.append(row)
    output = Path(output_directory)
    output.mkdir(parents=True, exist_ok=True)
    totals = {"reportPath": str(Path(report_path).resolve()), "runId": report["runId"],
              "datasetSha256": report["dataset"]["sha256"], "policyVersion": report["policyVersion"],
              "corpusFingerprint": corpus["fingerprint"], "citationRangeFailures": len(rows),
              "literalQuoteVisibility": {"visible": sum(row["allReferenceQuotesVisible"] for row in all_rows),
                                         "answerableExecuted": sum(bool(row["facts"]) for row in all_rows)},
              "finalGenerationInputVisibility": {
                  "visible": sum(row["allReferenceQuotesInFinalGenerationInput"] is True for row in all_rows),
                  "answerableObserved": sum(bool(row["facts"]) and row["allReferenceQuotesInFinalGenerationInput"] is not None for row in all_rows)},
              "rootCounts": dict(collections.Counter(row["primaryDiagnostic"] for row in rows)),
              "limitations": ["Literal overlap is diagnostic, not semantic correctness or a passing score.",
                              "No label changes are made. Equivalent sources require independent human verification.",
                              "literalQuoteVisibility is the union of registered snapshots; finalGenerationInputVisibility uses exact hashed model-input prefixes.",
                              "Older reports lack candidate/model-input stage observations; do not infer unseen stages."],
              "cases": rows}
    if baseline_path:
        baseline = read(baseline_path)
        old = {case["task"]["caseId"]: case for case in baseline["cases"]}
        gained, lost = [], []
        for case in report["cases"]:
            case_id = case["task"]["caseId"]
            before = old[case_id]["evaluation"]["status"] in ("PENDING_REVIEW", "SUCCEEDED")
            after = case["evaluation"]["status"] in ("PENDING_REVIEW", "SUCCEEDED")
            if after and not before:
                gained.append(case_id)
            if before and not after:
                lost.append(case_id)
        totals["comparison"] = {"sameDataset": baseline["dataset"]["sha256"] == report["dataset"]["sha256"],
                                "samePolicy": baseline["policyVersion"] == report["policyVersion"],
                                "sameCorpus": baseline["dataset"]["referencedChunkFingerprint"] == report["dataset"]["referencedChunkFingerprint"],
                                "gainedAutomaticGates": gained, "lostAutomaticGates": lost,
                                "baselineOverall": baseline["overall"], "currentOverall": report["overall"]}
        current_by_id = {row["caseId"]: row for row in all_rows}
        crosswalk = []
        for case in baseline["cases"]:
            if not any("CITATION_OUTSIDE_ACCEPTABLE_RANGE" in reason for reason in case["evaluation"]["failureReasons"]):
                continue
            current = current_by_id.get(case["task"]["caseId"])
            crosswalk.append({"caseId": case["task"]["caseId"], "baselineRunId": baseline["runId"],
                              "currentRunId": report["runId"], "current": current,
                              "limitation": "Current stage evidence does not retroactively prove an unobserved baseline retrieval stage."})
        (output / "baseline-39-crosswalk.jsonl").write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in crosswalk), encoding="utf-8")
    (output / "evidence-diagnostics.json").write_text(json.dumps(totals, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "all-task-evidence-diagnostics.jsonl").write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in all_rows), encoding="utf-8")
    lines = ["# 引用范围诊断台账", "", "仅为来源/字符串/阶段诊断，语义结论均待独立人工复核。", "",
             f"runId: `{report['runId']}`；引用范围失败：{len(rows)}。", "",
             "| Case | 模式 | 主要诊断 | 标注原文全部可见 | 范围外 chunks |", "|---|---|---|---|---|"]
    lines.extend(f"| {row['caseId']} | {row['mode']} | {row['primaryDiagnostic']} | {row['allReferenceQuotesVisible']} | "
                 + ", ".join(str(citation["chunkId"]) for citation in row["outsideCitations"]) + " |" for row in rows)
    (output / "evidence-ledger.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in totals.items() if key != "cases"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report")
    parser.add_argument("output")
    parser.add_argument("--baseline")
    args = parser.parse_args()
    analyze(args.report, args.output, args.baseline)
