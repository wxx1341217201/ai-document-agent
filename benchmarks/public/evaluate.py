"""Independently score public labels and observed production results; never rerun a model.

CMRC EM is a Python 3 port of upstream v5 normalization. HotpotQA answer
EM/F1 uses its upstream normalization/token-overlap definition. Citation
removal and the additional source-citation checks are explicit adaptations.
This does not certify the existing human-final QA / business Agent metrics.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import string
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

import prepare

CITATION = re.compile(r"\[(C[1-9]\d*(?:\s*[,，]\s*C[1-9]\d*)*)\]")
CMRC_PUNCTUATION = set(['-', ':', '_', '*', '^', '/', '\\', '~', '`', '+', '=',
    '，', '。', '：', '？', '！', '“', '”', '；', '’', '《', '》', '……', '·', '、',
    '「', '」', '（', '）', '－', '～', '『', '』'])


def load_rows(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def answer_without_citations(answer: str) -> str:
    return CITATION.sub("", answer).strip()


def cmrc_normalize(answer: str) -> str:
    return "".join(char for char in answer.lower().strip() if char not in CMRC_PUNCTUATION)


def hotpot_normalize(answer: str) -> str:
    answer = "".join(char for char in answer.lower() if char not in string.punctuation)
    return " ".join(re.sub(r"\b(a|an|the)\b", " ", answer).split())


def answer_scores(sample: dict, answer: str) -> dict:
    prediction = answer_without_citations(answer)
    if sample["dataset"] == "cmrc2018":
        return {"prediction": prediction, "exactMatch": any(cmrc_normalize(prediction) == cmrc_normalize(gold) for gold in sample["answers"]),
                "f1": None, "policy": "cmrc2018_v5_em_python3_port_after_citation_removal"}
    normalized = hotpot_normalize(prediction)
    em, f1 = False, 0.0
    for answer in sample["answers"]:
        gold = hotpot_normalize(answer)
        em |= normalized == gold
        if ((normalized in ("yes", "no", "noanswer") or gold in ("yes", "no", "noanswer")) and normalized != gold):
            continue
        predicted_tokens, gold_tokens = normalized.split(), gold.split()
        overlap = sum((Counter(predicted_tokens) & Counter(gold_tokens)).values())
        if overlap:
            precision, recall = overlap / len(predicted_tokens), overlap / len(gold_tokens)
            f1 = max(f1, 2 * precision * recall / (precision + recall))
    return {"prediction": prediction, "exactMatch": em, "f1": f1, "policy": "hotpotqa_v1_answer_em_f1_after_citation_removal"}


def binding_maps(binding: dict) -> tuple[dict[int, dict], dict[int, dict]]:
    documents, chunks = {}, {}
    for document in binding["documents"]:
        document_id = document["documentId"]
        if document_id in documents:
            raise ValueError("Duplicate binding document id")
        documents[document_id] = document
        for chunk in document["chunks"]:
            if chunk["chunkId"] in chunks or prepare.sha(chunk["content"].encode("utf-8")) != chunk["contentHash"]:
                raise ValueError("Invalid binding chunk")
            chunks[chunk["chunkId"]] = {**chunk, "documentId": document_id, "sourceId": document["sourceId"],
                                       "knowledgeBaseId": document["knowledgeBaseId"]}
    return documents, chunks


def validate_hit(hit: dict, chunks: dict[int, dict], kb_id: int) -> dict:
    expected = chunks.get(hit["chunkId"])
    if not expected or hit["documentId"] != expected["documentId"] or kb_id != expected["knowledgeBaseId"]:
        raise ValueError("Unknown, cross-document or cross-knowledge-base evidence")
    if "knowledgeBaseId" in hit and hit["knowledgeBaseId"] != kb_id:
        raise ValueError("Returned knowledge-base scope mismatch")
    if "content" in hit and hit["content"] != expected["content"]:
        raise ValueError("Returned chunk differs from frozen content")
    return expected


def retrieval_score(sample: dict, row: dict, chunks: dict[int, dict], kb_id: int) -> dict:
    hits = row.get("top5", [])
    if len(hits) > 5 or len({hit["chunkId"] for hit in hits}) != len(hits):
        raise ValueError("Top-5 result depth or uniqueness mismatch")
    expected_hits = [validate_hit(hit, chunks, kb_id) for hit in hits]
    gold_docs = set(sample["relevantDocumentIds"])
    retrieved_docs = {hit["sourceId"] for hit in expected_hits}
    expected_chunks = {chunk_id for chunk_id, chunk in chunks.items()
                       if any(span["documentId"] == chunk["sourceId"] and span["text"] in chunk["content"] for span in sample["evidence"])}
    retrieved_chunk_ids = {hit["chunkId"] for hit in hits}
    ranks = [index + 1 for index, hit in enumerate(expected_hits) if hit["sourceId"] in gold_docs]
    error = bool(row.get("errorCode"))
    return {"documentRecallAt5": len(gold_docs & retrieved_docs) / len(gold_docs) if gold_docs and not error else 0.0,
            "documentHitAt5": bool(gold_docs & retrieved_docs) and not error,
            "allGoldDocumentsAt5": bool(gold_docs) and gold_docs <= retrieved_docs and not error,
            "chunkHitAt5": bool(expected_chunks & retrieved_chunk_ids) and not error if expected_chunks else None,
            "chunkLabelsAvailable": bool(expected_chunks), "mrrAt5": 1 / min(ranks) if ranks and not error else 0.0,
            "expectedChunkIds": sorted(expected_chunks), "retrievedSourceIds": sorted(retrieved_docs), "error": error}


def qa_score(sample: dict, row: dict, chunks: dict[int, dict], kb_id: int, agent: bool = False) -> dict:
    answer = row.get("answer", "") or ""
    answer_result = answer_scores(sample, answer)
    cited_ids = {name for match in CITATION.findall(answer) for name in re.split(r"\s*[,，]\s*", match)}
    citations = row.get("citations", [])
    citation_names = [citation["citationId"] for citation in citations]
    valid = bool(cited_ids) and len(set(citation_names)) == len(citation_names) and cited_ids <= set(citation_names)
    cited_sources = set()
    for citation in citations:
        chunk = validate_hit(citation, chunks, kb_id)
        if citation["citationId"] in cited_ids:
            cited_sources.add(chunk["sourceId"])
    gold_cited = set(sample["relevantDocumentIds"]) <= cited_sources
    error = bool(row.get("errorCode"))
    completed = not agent or row.get("status") == "COMPLETED"
    passed = answer_result["exactMatch"] and valid and gold_cited and not error and completed
    return {**answer_result, "exactMatch": bool(answer_result["exactMatch"]) and not error,
            "f1": 0.0 if error and answer_result["f1"] is not None else answer_result["f1"],
            "citationsValid": valid, "allGoldDocumentsCited": gold_cited,
            "citedSourceIds": sorted(cited_sources), "sourceCitedExactMatch": passed,
            "taskPassed": passed if agent else None, "error": error}


def mean(values: list) -> float | None:
    numeric = [value for value in values if value is not None]
    return sum(numeric) / len(numeric) if numeric else None


def latency_statistics(rows: list[dict]) -> dict:
    measured = [row for row in rows if row["phase"] == "latency"]
    successful = sorted(row["durationNanos"] / 1_000_000 for row in measured if not row.get("errorCode"))
    percentile = lambda fraction: successful[max(0, math.ceil(len(successful) * fraction) - 1)] if successful else None
    return {"requests": len(measured), "successful": len(successful), "errors": len(measured) - len(successful),
            "p50Ms": percentile(.50), "p95Ms": percentile(.95), "p99Ms": percentile(.99),
            "meanMs": mean(successful), "maxMs": max(successful) if successful else None,
            "percentilePolicy": "nearest-rank on successful requests; all errors retained; errors invalidate target claim"}


def aggregate_bucket(members: list[dict], metrics: tuple[str, ...]) -> dict:
    pending = sum(row.get("notRun", False) for row in members)
    bucket = {"samples": len(members), "attempted": len(members) - pending, "notRun": pending,
              "errors": sum(row["error"] and not row.get("notRun", False) for row in members)}
    for metric in metrics:
        values = [row.get(metric) for row in members]
        bucket[metric] = None if pending else mean(values)
    if pending:
        bounds = {}
        for metric in metrics:
            labelled = [row for row in members if row.get(metric) is not None]
            if labelled:
                confirmed = sum(row[metric] for row in labelled if not row.get("notRun", False))
                unrun = sum(row.get("notRun", False) for row in labelled)
                bounds[metric] = {"lowerBound": confirmed / len(labelled), "upperBound": (confirmed + unrun) / len(labelled)}
        bucket["scoreBoundsWithUnrunSamples"] = bounds
    return bucket


def evaluate_report(path: Path, prepared: Path, binding: dict, chunks: dict[int, dict], samples: dict[str, dict]) -> dict:
    report = prepare.read_json(path)
    if report["manifestSha256"] != prepare.sha((prepared / "manifest.json").read_bytes()) or report["bindingSha256"] != prepare.sha((prepared / "binding.json").read_bytes()):
        raise ValueError("Observation report is bound to different frozen data")
    phase = report["phase"]
    observations = report["observations"]
    if phase == "latency":
        ordinals = [(row["phase"], row["ordinal"]) for row in observations]
        if len(ordinals) != len(set(ordinals)):
            raise ValueError("Duplicate latency/warmup observations")
        for row in observations:
            sample = samples[row["caseId"]]
            kb_id = binding["knowledgeBases"][sample["dataset"]]
            if row["knowledgeBaseId"] != kb_id or row["durationNanos"] <= 0:
                raise ValueError("Invalid latency scope/duration")
            for hit in row.get("top5", []):
                validate_hit(hit, chunks, kb_id)
        statistics = latency_statistics(observations)
        warmups = [row for row in observations if row["phase"] == "warmup"]
        valid = (report.get("corpusStable") is True and report.get("warmup", 0) >= 50 and len(warmups) == report["warmup"]
                 and all(not row.get("errorCode") for row in warmups) and statistics["requests"] >= 500
                 and statistics["requests"] == report["samples"] and statistics["errors"] == 0 and report["rerank"] is True)
        return {"phase": phase, "source": str(path), "configuration": report["configuration"], "concurrency": report["concurrency"],
                "statistics": statistics, "targetP95Ms": 490, "targetStatus": "MET" if valid and statistics["p95Ms"] <= 490 else "NOT_MET" if valid else "NOT_EVALUABLE",
                "definition": "query embedding + parallel vector/BM25 + RRF + rerank; actual wall time; excludes ChatModel and benchmark worker queue"}
    expected = list(samples.values())
    if phase == "agent":
        expected = [sample for sample in expected if sample["dataset"] == "hotpotqa"]
    limit = report["limitPerDataset"]
    if limit:
        counts, selected = defaultdict(int), []
        for sample in expected:
            if counts[sample["dataset"]] < limit:
                selected.append(sample); counts[sample["dataset"]] += 1
        expected = selected
    modes = ("RETRIEVAL_FIRST", "REACT", "PLAN_EXECUTE_REFLECT") if phase == "agent" else ("",)
    keys = {(sample["id"], mode) for sample in expected for mode in modes}
    if {(row["caseId"], row["mode"]) for row in observations} != keys or len(observations) != len(keys):
        raise ValueError("Missing, duplicate or unexpected samples; complete fixed denominator required")
    scored = []
    for row in observations:
        sample = samples[row["caseId"]]
        kb_id = binding["knowledgeBases"][sample["dataset"]]
        if row.get("knowledgeBaseId", kb_id) != kb_id:
            raise ValueError("Case knowledge-base scope mismatch")
        score = retrieval_score(sample, row, chunks, kb_id) if phase == "retrieval" else qa_score(sample, row, chunks, kb_id, phase == "agent")
        scored.append({"caseId": row["caseId"], "dataset": sample["dataset"], "mode": row["mode"],
                       "notRun": row.get("errorCode", "").startswith("NOT_RUN"), **score})
    metrics = ("documentRecallAt5", "documentHitAt5", "allGoldDocumentsAt5", "chunkHitAt5", "mrrAt5") if phase == "retrieval" else ("exactMatch", "f1", "sourceCitedExactMatch", "taskPassed")
    buckets = {}
    for dataset in ("cmrc2018", "hotpotqa"):
        for mode in modes:
            members = [row for row in scored if row["dataset"] == dataset and row["mode"] == mode]
            if not members:
                continue
            bucket = aggregate_bucket(members, metrics)
            if phase == "retrieval":
                bucket["chunkLabelledSamples"] = sum(row["chunkLabelsAvailable"] for row in members)
            buckets[dataset + (":" + mode if mode else "")] = bucket
    status = "INVALID_CORPUS_CHANGED" if not report.get("corpusStable") else "INCOMPLETE_DEPENDENCY_FAILURE" if any(row.get("errorCode", "").startswith("NOT_RUN") for row in observations) else "MEASURED_WITH_ERRORS" if any(row.get("errorCode") for row in observations) else "MEASURED"
    return {"phase": phase, "source": str(path), "configuration": report["configuration"], "limitPerDataset": limit,
            "status": status, "byDatasetAndMode": buckets,
            "requestedBusinessTargetStatus": "PUBLIC_BASELINE_ONLY; definitions/scope differ from existing human-final metrics",
            "cases": scored}


def markdown(report: dict) -> str:
    lines = ["# 公开数据基准评测", "", f"生成时间：{report['createdAt']}", "",
             "数据：CMRC 2018 开发集固定抽样 200 题；HotpotQA distractor 验证集固定抽样 100 题。原始问题、接受答案与证据标注均保留。", "",
             "CMRC 使用全部 848 篇开发集原文；HotpotQA 使用所抽问题的 988 篇原文/干扰文档。两个知识库分别检索。", "",
             "这是经过格式适配的公开开发集基准。问答 EM 与公开 QA 任务通过率分别报告；原有人工最终 QA Accuracy、完整业务 Agent TaskSuccessRate 仍需其对应规则验收。", ""]
    for phase in report["phases"]:
        lines += [f"## {phase['phase']}", "", f"原始记录：`{phase['source']}`", ""]
        if phase["phase"] == "latency":
            lines += [f"P95：{phase['statistics']['p95Ms']} ms；490ms 目标：{phase['targetStatus']}。", "",
                      "```json", json.dumps(phase["statistics"], ensure_ascii=False, indent=2), "```", ""]
        else:
            lines += ["| 数据/模式 | 样本数 | 错误数 | 指标 |", "|---|---:|---:|---|"]
            for name, values in phase["byDatasetAndMode"].items():
                details = "未执行 " + str(values["notRun"]) + " 题；指标 N/A，见 JSON 中的上下界" if values["notRun"] else "; ".join(f"{key}={value * 100:.2f}%" for key, value in values.items() if key not in ("samples", "attempted", "notRun", "errors", "chunkLabelledSamples") and isinstance(value, (int, float)))
                lines.append(f"| {name} | {values['samples']} | {values['errors']} | {details} |")
            lines.append("")
    lines += ["## 边界与剩余指标", "", "- 生成答案仅移除 `[C#]` 引用后按上游归一化进行 EM 评分，不进行模型辅助答案提取。", 
              "- CMRC 中文 F1 未实现；HotpotQA 的 F1 采用上游 token-overlap 规则。", 
              "- 文档 Recall@5、文档 Hit@5 与 chunk Hit@5 分开计算，chunk Hit@5 仅对存在可映射原文证据的样本有定义。", 
              "- 公开 QA 任务为单轮问答及来源引用检查，不包含项目特有的多轮、工具故障和重规划完整验收。", 
              "- SessionLossRate、上传 ACK 等待和摄取吞吐使用独立故障/负载实验，不能从这些问答数据直接推导。", 
              "- 数据来源、许可、原始文件和派生数据 SHA-256 见配对的 manifest / sources.lock 与 benchmarks/public/ATTRIBUTION.md。", ""]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("observations", type=Path, nargs="+")
    parser.add_argument("--prepared", type=Path, default=prepare.ROOT / "prepared" / "cmrc200-hotpot100-seed20261019")
    parser.add_argument("--report-directory", type=Path, default=prepare.ROOT.parents[1] / "reports" / "public-benchmark")
    args = parser.parse_args()
    prepare.validate(prepare.ROOT / "raw", args.prepared)
    binding = prepare.read_json(args.prepared / "binding.json")
    _, chunks = binding_maps(binding)
    samples = {sample["id"]: sample for sample in load_rows(args.prepared / "qa.jsonl")}
    report = {"schemaVersion": "public-evaluation-v2", "createdAt": datetime.now(timezone.utc).isoformat(),
              "gradingPolicy": "upstream answer rules; unrun samples make final rates unknown with explicit bounds",
              "gradingSourceSha256": prepare.sha(Path(__file__).read_bytes()),
              "manifestSha256": prepare.sha((args.prepared / "manifest.json").read_bytes()),
              "phases": [evaluate_report(path, args.prepared, binding, chunks, samples) for path in args.observations]}
    args.report_directory.mkdir(parents=True, exist_ok=True)
    grading_snapshots = args.report_directory / "code-snapshots"
    grading_snapshots.mkdir(exist_ok=True)
    grading_snapshot = grading_snapshots / (report["gradingSourceSha256"] + "-evaluate.py")
    if not grading_snapshot.exists():
        with grading_snapshot.open("xb") as output:
            output.write(Path(__file__).read_bytes())
    stem = datetime.now(timezone.utc).strftime("summary-%Y%m%dT%H%M%S%fZ")
    json_path, md_path = args.report_directory / (stem + ".json"), args.report_directory / (stem + ".md")
    with json_path.open("x", encoding="utf-8") as output:
        json.dump(report, output, ensure_ascii=False, indent=2)
        output.write("\n")
    with md_path.open("x", encoding="utf-8") as output:
        output.write(markdown(report))
    print(json.dumps({"json": str(json_path), "markdown": str(md_path), "phases": [{key: value for key, value in phase.items() if key not in ("cases", "configuration")} for phase in report["phases"]]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
