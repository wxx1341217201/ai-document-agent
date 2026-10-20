"""Recheck existing recovery evidence and recorded component fingerprints, without injecting faults."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


FAULTS = {"REDIS_KEY_LOSS", "REDIS_TTL_EXPIRY", "REDIS_RESTART", "APPLICATION_RESTART",
          "AGENT_STEP_INTERRUPTION", "WORKER_REDELIVERY", "WEBSOCKET_RECONNECT"}


def summarize(cases: list[dict]) -> dict:
    """Recompute rates without treating unknown recovery outcomes as zero loss."""
    outcomes = Counter(case["evaluation"]["outcome"] for case in cases)
    attempted = sum(case["attempted"] for case in cases)
    measured = (attempted > 0 and outcomes["NOT_RUN"] == 0 and outcomes["RECOVERY_FAILED"] == 0
                and all(case["faultVerified"] for case in cases))
    times = sorted(case["recoveryMillis"] for case in cases
                   if case["attempted"] and case["recoveryMillis"] is not None)
    percentile = lambda fraction: times[math.ceil(len(times) * fraction) - 1] if times else None
    return {"planned": len(cases), "attempted": attempted, "recovered": outcomes["RECOVERED"],
            "sessionLost": outcomes["SESSION_LOST"], "recoveryFailed": outcomes["RECOVERY_FAILED"],
            "notRun": outcomes["NOT_RUN"],
            "sessionLossRate": outcomes["SESSION_LOST"] * 100 / attempted if measured else None,
            "recoverySuccessRate": outcomes["RECOVERED"] * 100 / attempted if attempted else None,
            "observedLossLowerBound": outcomes["SESSION_LOST"] * 100 / attempted if attempted else None,
            "p50Millis": percentile(.5), "p95Millis": percentile(.95), "p99Millis": percentile(.99),
            "maxMillis": times[-1] if times else None}


def target_status(cases: list[dict], environment: dict) -> str:
    summary = summarize(cases)
    evaluable = (environment.get("mode") == "LIVE" and environment.get("realStorageVerified") is True
                 and len(cases) >= 1000 and summary["notRun"] == 0 and summary["recoveryFailed"] == 0
                 and all(case["attempted"] and case["faultVerified"] for case in cases)
                 and FAULTS <= {case["sample"]["fault"] for case in cases})
    return ("MET" if summary["sessionLost"] * 200 < summary["attempted"] else "NOT_MET") if evaluable else "NOT_EVALUABLE"


def compare_summary(expected: dict, actual: dict, label: str, issues: list[str]) -> None:
    for name, value in expected.items():
        observed = actual.get(name)
        equal = value == observed if value is None or observed is None else abs(value - observed) < 1e-5
        if not equal:
            issues.append(label + ": reported " + name + " differs from original cases")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=REPO / "reports/recovery-20261011T074905084Z-28dd7a89.json")
    parser.add_argument("--output-directory", type=Path, default=REPO / "reports/public-benchmark")
    args = parser.parse_args()
    source = json.loads(args.source.read_text(encoding="utf-8"))
    issues, changed_classes, changed_configuration = [], [], []
    for name, expected in source["environment"]["codeSha256"].items():
        path = REPO / "target/classes" / (name.replace(".", "/") + ".class")
        if not path.exists() or digest(path) != expected:
            changed_classes.append(name)
    for name, expected in source["environment"]["configurationSha256"].items():
        path = REPO / ("target/classes/" + name if name.startswith("application-") else name)
        if not path.exists() or digest(path) != expected:
            changed_configuration.append(name)
    plan = json.loads(Path(source["environment"]["planSnapshot"]).read_text(encoding="utf-8"))
    cases = source["cases"]
    if [case["sample"] for case in cases] != plan["samples"]:
        issues.append("Frozen sample plan differs from recorded cases")
    journal_path = args.source.parent / ("recovery-" + source["runId"] + "-observations.jsonl")
    journal = [json.loads(line) for line in journal_path.read_text(encoding="utf-8").splitlines() if line]
    recorded = {case["sample"]["caseId"]: case for case in cases}
    if len(recorded) != len(cases) or len(journal) != len(cases) or any(recorded.get(case["sample"]["caseId"]) != case for case in journal):
        issues.append("Journal, case identities or full denominator differ")
    outcomes = Counter(case["evaluation"]["outcome"] for case in cases)
    faults = Counter(case["sample"]["fault"] for case in cases)
    conversations, findings = set(), []
    execution_count, messages = 0, 0
    for case in cases:
        case_id = case["sample"]["caseId"]
        before, after = case["before"], case["after"]
        outcome = case["evaluation"]["outcome"]
        if outcome not in {"RECOVERED", "SESSION_LOST", "RECOVERY_FAILED", "NOT_RUN"}:
            issues.append(case_id + ": unknown outcome")
        if before is not None:
            identity = (case["faultEvidence"].get("environmentRunId"), before["conversationId"])
            if identity in conversations:
                issues.append(case_id + ": conversation reused within one environment")
            conversations.add(identity)
        if outcome != "RECOVERED":
            findings.append({"caseId": case_id, "fault": case["sample"]["fault"], "outcome": outcome,
                             "attempted": case["attempted"], "faultVerified": case["faultVerified"],
                             "hasBefore": before is not None, "hasAfter": after is not None,
                             "reasons": case["evaluation"]["reasons"]})
            if outcome == "NOT_RUN" and case["attempted"]:
                issues.append(case_id + ": attempted case incorrectly reported as NOT_RUN")
            continue
        if not case["attempted"] or not case["faultVerified"] or before is None or after is None:
            issues.append(case_id + ": success claimed without verified attempt/authority")
            continue
        for field in ("conversationId", "knowledgeBaseId", "lastMessageSequence", "messages", "memory"):
            if before[field] != after[field]:
                issues.append(case_id + ": authoritative state mismatch: " + field)
        if not after["cachePresent"] or case["evaluation"]["reasons"]:
            issues.append(case_id + ": cache/evaluation verification failed")
        if [message["sequenceNo"] for message in after["messages"]] != list(range(1, after["lastMessageSequence"] + 1)):
            issues.append(case_id + ": message sequence is incomplete")
        messages += len(before["messages"])
        checkpoint, recovered = case["checkpoint"], case["recoveredExecution"]
        if checkpoint is not None:
            execution_count += 1
            if recovered is None or recovered["status"] != "SUCCEEDED":
                issues.append(case_id + ": execution not recovered")
                continue
            if (checkpoint["executionId"] != recovered["executionId"] or recovered["knowledgeBaseId"] != before["knowledgeBaseId"]
                    or recovered["conversationId"] != before["conversationId"]):
                issues.append(case_id + ": execution scope changed")
            if checkpoint["resultSha256"] is not None and checkpoint["resultSha256"] != recovered["resultSha256"]:
                issues.append(case_id + ": committed result changed")
            for step, status in checkpoint["steps"].items():
                if status in ("SUCCEEDED", "COMPLETED") and recovered["steps"].get(step) != status:
                    issues.append(case_id + ": committed step missing")
    summary = summarize(cases)
    attempted = summary["attempted"]
    compare_summary(summary, source["overall"], "overall", issues)
    for fault in FAULTS:
        members = [case for case in cases if case["sample"]["fault"] == fault]
        compare_summary(summarize(members), source["byFault"].get(fault, {}), fault, issues)
    expected_target = target_status(cases, source["environment"])
    if expected_target != source["targetStatus"]:
        issues.append("Reported target differs from completeness, authority and strict <0.5% rule")
    report = {"schemaVersion": "recorded-recovery-recheck-v3", "createdAt": datetime.now(timezone.utc).isoformat(),
              "status": "ISSUES_FOUND" if issues else "VERIFIED_RECORDED_EVIDENCE_WITH_FAILURES" if findings else "VERIFIED_RECORDED_EVIDENCE",
              "integrityAuditStatus": "FAILED" if issues else "PASSED", "source": str(args.source),
              "sourceCreatedAt": source["createdAt"], "sourceSha256": digest(args.source), "journalSha256": digest(journal_path),
              "cases": len(cases), "attempted": attempted, "outcomes": dict(outcomes), "byFaultCounts": dict(faults),
              "sessionLossRatePercent": summary["sessionLossRate"], "observedLossLowerBoundPercent": summary["observedLossLowerBound"],
              "recoverySuccessRatePercent": summary["recoverySuccessRate"], "recoveryTargetStatus": expected_target,
              "recoveryFindings": findings,
              "verifiedPersistedMessages": messages, "executionCheckpointCases": execution_count,
              "recordedExecutionCheckpointCases": sum(case["checkpoint"] is not None for case in cases),
              "caseVerificationScope": "Authority and checkpoint checks cover RECOVERED cases; non-success classifications and their missing evidence are retained in recoveryFindings, not reclassified or re-injected.",
              "checkedRecordedComponents": len(source["environment"]["codeSha256"]), "changedRecordedComponents": changed_classes,
              "changedRecordedConfiguration": changed_configuration,
              "recordedComponentContinuity": not changed_classes and not changed_configuration,
              "newFaultInjectionsByRecheck": 0,
              "gradingSourceSha256": digest(Path(__file__)),
              "scope": "Offline integrity and statistics recheck of the referenced report's controlled-Agent real-storage/process-fault evidence, dated "
                       + source["createdAt"] + "; the recheck itself injects no faults and does not establish full transitive-code equivalence.",
              "issues": issues}
    args.output_directory.mkdir(parents=True, exist_ok=True)
    snapshots = args.output_directory / "code-snapshots"
    snapshots.mkdir(exist_ok=True)
    snapshot = snapshots / (report["gradingSourceSha256"] + "-recheck-recovery.py")
    if not snapshot.exists():
        with snapshot.open("xb") as output:
            output.write(Path(__file__).read_bytes())
    path = args.output_directory / datetime.now(timezone.utc).strftime("recovery-recheck-%Y%m%dT%H%M%S%fZ.json")
    with path.open("x", encoding="utf-8") as output:
        json.dump(report, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(json.dumps({"report": str(path), **report}, ensure_ascii=False, indent=2))
    if issues:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
