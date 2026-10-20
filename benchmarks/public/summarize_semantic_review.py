"""Audit and summarize explicit per-case AI semantic judgments; never judge by code.

The decision batches are written after reading the questions, predictions and
frozen evidence. This script only verifies provenance and recomputes statistics.
Official labels, model observations and exact-match grades remain unchanged.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path

import evaluate


VERDICTS = ("CORRECT", "PARTIAL", "INCORRECT", "REFUSAL", "DISPUTED")
GROUNDING = ("SUPPORTS", "PARTIAL", "NOT_SUPPORTED", "UNCERTAIN", "NOT_APPLICABLE")
ZH = {"CORRECT": "正确", "PARTIAL": "部分正确", "INCORRECT": "错误",
      "REFUSAL": "未回答/拒答", "DISPUTED": "争议",
      "SUPPORTS": "上下文支持", "NOT_SUPPORTED": "上下文不支持",
      "UNCERTAIN": "支持情况不确定", "NOT_APPLICABLE": "无所问答案，不适用"}
GROUND_ZH = {"SUPPORTS": "充分支持", "PARTIAL": "部分支持", "NOT_SUPPORTED": "不支持",
             "UNCERTAIN": "不确定", "NOT_APPLICABLE": "无所问答案，不适用"}


def read(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def digest_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def statistics(cases: list[dict]) -> dict:
    counts = Counter(case["verdict"] for case in cases)
    count = len(cases)
    correct = counts["CORRECT"]
    disputed = counts["DISPUTED"]
    grounded = sum(case["verdict"] == "CORRECT" and case["grounding"] == "SUPPORTS" for case in cases)
    return {
        "samples": count, "reviewed": count, "pending": 0,
        "verdictCounts": {key: counts[key] for key in VERDICTS},
        "aiConfirmedSemanticCorrect": correct,
        "aiConfirmedSemanticCorrectRate": correct / count if count else None,
        "disputedCasesNotCredited": disputed,
        "disputeOnlyUpperBound": (correct + disputed) / count if count else None,
        "groundedSemanticCorrect": grounded,
        "groundedSemanticCorrectRate": grounded / count if count else None,
        "originalExactMatchCorrect": sum(case["originalScore"]["exactMatch"] for case in cases),
        "originalExactMatchRate": sum(case["originalScore"]["exactMatch"] for case in cases) / count if count else None,
        "nonExactMatchButSemanticallyCorrect": sum(not case["originalScore"]["exactMatch"] and case["verdict"] == "CORRECT" for case in cases),
        "exactMatchWithoutConfirmedSemanticCorrectness": sum(case["originalScore"]["exactMatch"] and case["verdict"] != "CORRECT" for case in cases),
        "correctWithGroundingGap": sum(case["verdict"] == "CORRECT" and case["grounding"] != "SUPPORTS" for case in cases),
        "labelIssueCases": sum(case["labelIssue"] is not None for case in cases),
    }


def percent(value: float) -> str:
    return f"{value:.2%}"


def escape(value: str) -> str:
    return value.replace("|", "\\|").replace("\n", " ")


def quoted(value: str) -> str:
    return "\n".join("> " + line for line in value.splitlines())


def detail(case: dict) -> str:
    lines = [f"## Case {case['index']:03d}", "", f"`{case['caseId']}`", "",
             f"**问题：** {case['question']}", "",
             "**原始接受答案：** " + "；".join(case["acceptedAnswers"]), "",
             "**实际回答：**", "", quoted(case["prediction"]), "",
             f"**AI语义判定：** {ZH[case['verdict']]}；**实际上下文支持：** {GROUND_ZH[case['grounding']]}；"
             f"**原EM：** {int(case['originalScore']['exactMatch'])}", "",
             "**理由：** " + case["rationale"], ""]
    if case["labelIssue"]:
        lines.extend([f"**题目/标注问题：** `{case['labelIssue']}`", ""])
    lines.extend(["**原始标准证据（原文，非新增答案）：**", ""])
    seen = set()
    for span in case["referenceEvidence"]:
        key = (span["documentId"], span["text"])
        if key not in seen:
            seen.add(key)
            lines.extend([quoted(span["text"]), ""])
    references = case["visibleEvidenceReferences"]
    lines.extend(["**实际上下文定位：** " + "; ".join(
        f"{ref['citationId']} / chunk {ref['chunkId']}" + ("（已引用）" if ref["cited"] else "")
        for ref in references), "",
        "完整原始段落、问题和观察记录见同目录 `review-packets.json` 对应 index；来源及段落 SHA-256 见 `cases.json`。", ""])
    return "\n".join(lines)


def build(root: Path) -> dict[str, str]:
    rubric = read(root / "rubric.json")
    clarifications = read(root / "clarifications.json")
    source_paths = {name: Path(entry["path"]) for name, entry in rubric["sources"].items()}
    for name, path in source_paths.items():
        if sha(path) != rubric["sources"][name]["sha256"]:
            raise ValueError(f"Frozen source changed: {name}")
    observations = read(source_paths["observations"])
    observed = {row["caseId"]: row for row in observations["observations"]}
    samples = {row["id"]: row for row in evaluate.load_rows(source_paths["labels"])}
    binding = read(source_paths["binding"])
    documents, chunks = evaluate.binding_maps(binding)
    packets = read(root / "review-packets.json")
    if len(packets) != 300 or len(observed) != 300 or set(samples) != set(observed):
        raise ValueError("This review requires all 300 frozen QA samples")
    decisions = {}
    batch_files = sorted(root.glob("decisions-*.json"))
    for path in batch_files:
        for decision in read(path):
            if decision["index"] in decisions:
                raise ValueError("Duplicate initial decision")
            decisions[decision["index"]] = dict(decision)
    amendments = []
    amendment_files = sorted(root.glob("amendments-*.json"))
    for path in amendment_files:
        for amendment in read(path):
            previous = decisions[amendment["index"]]
            if previous["caseId"] != amendment["caseId"] or not amendment["reason"].strip():
                raise ValueError("Amendment scope/reason invalid")
            for key, value in amendment["previous"].items():
                if previous[key] != value:
                    raise ValueError("Amendment previous value mismatch")
            previous.update(amendment["updated"])
            previous["reviewedAt"] = amendment["reviewedAt"]
            amendments.append(amendment)
    if sorted(decisions) != list(range(1, 301)):
        raise ValueError("Missing or unexpected decisions")
    cases = []
    for packet in packets:
        index, sample, row = packet["index"], packet["sample"], packet["observation"]
        decision = decisions[index]
        if packet["caseId"] != row["caseId"] or packet["caseId"] != sample["id"]:
            raise ValueError("Packet case identity mismatch")
        if row != observed[packet["caseId"]] or sample != samples[packet["caseId"]]:
            raise ValueError("Packet differs from frozen observation/label")
        if decision["caseId"] != packet["caseId"] or decision["reviewedBy"] != "AI_ASSISTANT":
            raise ValueError("Decision identity/reviewer mismatch")
        if decision["verdict"] not in VERDICTS or decision["grounding"] not in GROUNDING or not decision["rationale"].strip():
            raise ValueError("Incomplete explicit semantic decision")
        if row.get("errorCode"):
            raise ValueError("Unexpected failed observation in completed review")
        for item in row["evidence"]:
            chunk = evaluate.validate_hit(item["citation"], chunks, row["knowledgeBaseId"])
            if item["sourceContentSha256"] != digest_text(chunk["content"]) or item["contextText"] not in chunk["content"]:
                raise ValueError("Visible evidence differs from frozen content")
        candidates = row["retrievalTrace"]["candidates"]
        for candidate in candidates:
            evaluate.validate_hit(candidate, chunks, row["knowledgeBaseId"])
        full_candidates = all(any(chunks[hit["chunkId"]]["sourceId"] == span["documentId"] and span["text"] in chunks[hit["chunkId"]]["content"] for hit in candidates) for span in sample["evidence"])
        full_context = all(any(documents[item["citation"]["documentId"]]["sourceId"] == span["documentId"] and span["text"] in item["contextText"] for item in row["evidence"]) for span in sample["evidence"])
        cited = {name for group in evaluate.CITATION.findall(row["answer"]) for name in re.split(r"\s*[,，]\s*", group)}
        score = evaluate.qa_score(sample, row, chunks, row["knowledgeBaseId"])
        cases.append({**decision, "dataset": sample["dataset"], "question": sample["question"],
                      "acceptedAnswers": sample["answers"], "prediction": row["answer"],
                      "originalScore": score, "referenceEvidence": sample["evidence"],
                      "allGoldEvidenceInCandidates": full_candidates,
                      "allGoldEvidenceInFinalContext": full_context,
                      "visibleEvidenceReferences": [{"citationId": item["citation"]["citationId"],
                          "chunkId": item["citation"]["chunkId"], "documentId": item["citation"]["documentId"],
                          "sourceId": documents[item["citation"]["documentId"]]["sourceId"],
                          "sourceContentSha256": item["sourceContentSha256"],
                          "visibleContextSha256": digest_text(item["contextText"]),
                          "cited": item["citation"]["citationId"] in cited} for item in row["evidence"]]})
    cases.sort(key=lambda case: case["index"])
    if len({case["caseId"] for case in cases}) != 300:
        raise ValueError("Duplicate case IDs")
    overall = statistics(cases)
    if overall["originalExactMatchCorrect"] != 123:
        raise ValueError("Original EM changed")
    by_dataset = {name: statistics([case for case in cases if case["dataset"] == name]) for name in ("cmrc2018", "hotpotqa")}
    context_buckets = {name: {str(flag).lower(): statistics([case for case in cases if case["dataset"] == name and case["allGoldEvidenceInFinalContext"] == flag]) for flag in (True, False)} for name in ("cmrc2018", "hotpotqa")}
    review_files = [root / "rubric.json", root / "clarifications.json", root / "review-packets.json", *batch_files, *amendment_files]
    summary = {"schemaVersion": "public-qa-ai-semantic-review-v1", "reviewerType": "AI_ASSISTANT",
               "status": "AI_REVIEW_COMPLETE_WITH_DISPUTES" if overall["disputedCasesNotCredited"] else "AI_REVIEW_COMPLETE",
               "completedAt": max(case["reviewedAt"] for case in cases), "statistics": overall,
               "byDataset": by_dataset, "byFinalAnnotatedEvidenceCompleteness": context_buckets,
               "primaryDefinition": "AI-confirmed fully semantically correct answers / all 300 frozen QA questions. No partial credit; disputed questions stay in denominator and receive no confirmed-correct credit.",
               "groundingDefinition": clarifications["groundingDefinition"],
               "humanFinalQAAccuracy": None, "business83PercentTargetStatus": "NOT_CERTIFIED_BY_THIS_PUBLIC_AI_REVIEW",
               "originalSources": rubric["sources"],
               "reviewFileSha256": {path.name: sha(path) for path in review_files},
               "summaryBuilderSha256": sha(Path(__file__)), "amendmentsApplied": len(amendments),
               "limits": ["AI review, not independent human final adjudication or the original M19B business QA metric.",
                   "Judgments use the frozen sources and historical scope; they are not a fresh external fact check of every source.",
                   "Cases marked DISPUTED are completed review findings, not certified correct answers.",
                   "Observed associations with missing context do not constitute an intervention proving the size of a future improvement.",
                   "Integrity auditing verifies provenance, completeness and arithmetic, not the truth of every semantic judgment."]}
    headings = ["# QA逐题语义复核", "", "本轮300题均由AI助手逐题阅读问题、实际回答和冻结证据后给出判定。没有重新调用被测模型，没有修改原始接受答案、实际输出或EM。", "",
                "计分：只有完整正确计入分子；部分正确、错误、拒答和争议均留在分母，争议暂不计正确。下表是AI确认正确比例，上界仅表示争议题全部被确认正确时的可能值。", "",
                "| 数据集 | 题数 | 语义确认正确 | 确认正确率 | 争议数 | 仅争议产生的上界 | 原EM |", "|---|---:|---:|---:|---:|---:|---:|"]
    for name, item in [*by_dataset.items(), ("合计", overall)]:
        headings.append(f"| {name} | {item['samples']} | {item['aiConfirmedSemanticCorrect']} | {percent(item['aiConfirmedSemanticCorrectRate'])} | {item['disputedCasesNotCredited']} | {percent(item['disputeOnlyUpperBound'])} | {percent(item['originalExactMatchRate'])} |")
    headings.extend(["", "| 判定 | CMRC | HotpotQA | 合计 |", "|---|---:|---:|---:|"])
    for verdict in VERDICTS:
        headings.append(f"| {ZH[verdict]} | {by_dataset['cmrc2018']['verdictCounts'][verdict]} | {by_dataset['hotpotqa']['verdictCounts'][verdict]} | {overall['verdictCounts'][verdict]} |")
    headings.extend(["", f"其中，语义正确且得到实际模型可见上下文充分支持的答案为 **{overall['groundedSemanticCorrect']}/300={percent(overall['groundedSemanticCorrectRate'])}**。另有{overall['correctWithGroundingGap']}个语义正确答案的可见证据支持不足，见关注案例。这个支持判定允许最小充分或替代证据，不机械要求全部标准文档都出现。", "",
                     f"原EM不通过但语义正确：{overall['nonExactMatchButSemanticallyCorrect']}题；原EM通过但未确认语义正确：{overall['exactMatchWithoutConfirmedSemanticCorrectness']}题。引用ID合法性和标准来源覆盖使用原评分脚本单独记录，不等同于所有引用均经过独立语义蕴含审计。", "",
                     "## 英文题的上下文与结果", "", "| 最终上下文 | 题数 | 正确 | 部分正确 | 错误 | 拒答 | 争议 |", "|---|---:|---:|---:|---:|---:|---:|"])
    for key, label in (("true", "包含全部标准证据"), ("false", "未包含全部标准证据")):
        item = context_buckets["hotpotqa"][key]
        headings.append(f"| {label} | {item['samples']} | " + " | ".join(str(item["verdictCounts"][v]) for v in VERDICTS) + " |")
    headings.extend(["", "这是原运行的逐题关联诊断。原文证据不齐时仍可能存在替代证据或模型记忆，不能只用完整性标记自动决定语义正确性。", "",
                     "## 可核对材料", "", "- [全部300题、答案、判定和理由](cases.md)", "- [错误、部分正确、拒答、争议及证据支持不足案例](attention-cases.md)",
                     "- [逐题结构化结果](cases.json)", "- [统计及来源摘要](summary.json)", "- [原始复核材料包](review-packets.json)",
                     "- [事前评分规则](rubric.json)及[证据支持口径说明](clarifications.json)", "- [判定一致性复查修订记录](amendments-001.json)", "- [完整性与算术核验](audit.json)", "",
                     "AI语义复核与原公开集EM并列报告。它不认证原业务集83%目标，也不代表独立人工最终验收完成。"])
    all_details = "# 全部300题的AI逐题语义复核\n\n原观察、接受答案和EM均保持不变；完整规则见 [复核报告](review.md)。\n\n" + "\n".join(detail(case) for case in cases)
    attention = [case for case in cases if case["verdict"] != "CORRECT" or case["grounding"] != "SUPPORTS" or case["labelIssue"]]
    attention_details = "# 需关注的复核案例\n\n包含错误、部分正确、拒答、争议、可见证据不足及题目/标注问题。被列入本页不自动意味着回答错误。\n\n" + "\n".join(detail(case) for case in attention)
    result = {"summary.json": json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
              "cases.json": json.dumps(cases, ensure_ascii=False, indent=2) + "\n",
              "review.md": "\n".join(headings) + "\n", "cases.md": all_details,
              "attention-cases.md": attention_details}
    audit = {"status": "PASSED_INTEGRITY_AND_ARITHMETIC_ONLY", "semanticJudgmentCertification": False,
             "checks": {"all300DecisionsExplicit": True, "indicesAndCaseIdsUnique": True,
                        "frozenSourcesUnchanged": True, "packetsEqualOriginalLabelsAndObservations": True,
                        "evidenceScopeAndHashesValid": True, "originalEM123Of300Preserved": True,
                        "amendmentsHaveMatchingPriorValues": True, "allCategoriesRetainedInDenominator": True},
             "reviewed": len(cases), "statistics": overall,
             "outputsSha256": {name: digest_text(text) for name, text in result.items()}}
    result["audit.json"] = json.dumps(audit, ensure_ascii=False, indent=2) + "\n"
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--review-dir", required=True, type=Path)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    outputs = build(args.review_dir.resolve())
    if args.verify_only:
        for name, content in outputs.items():
            if (args.review_dir / name).read_text(encoding="utf-8") != content:
                raise ValueError(f"Summary no longer matches explicit review evidence: {name}")
        print(json.dumps({"status": "VERIFIED_INTEGRITY_AND_ARITHMETIC_ONLY", "files": len(outputs)}))
        return
    if any((args.review_dir / name).exists() for name in outputs):
        raise FileExistsError("Summary exists; use --verify-only or a separate review directory")
    for name, content in outputs.items():
        (args.review_dir / name).write_text(content, encoding="utf-8", newline="\n")
    print(json.dumps({"status": "WRITTEN", "reviewDirectory": str(args.review_dir.resolve()), "files": list(outputs)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
