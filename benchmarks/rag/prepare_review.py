"""Write a source-bound, readable human review packet without making judgments.

Usage: python benchmarks/rag/prepare_review.py reports/rag-....json
Edit the paired review-template.jsonl, then apply it with the Java Review mode.
"""

import argparse
import json
import re
from pathlib import Path

from audit import audit, paired_path


def block(value):
    value = "" if value is None else str(value)
    fence = "`" * max(3, 1 + max((len(run) for run in re.findall(r"`+", value)), default=0))
    return fence + "text\n" + value + "\n" + fence + "\n\n"


def packet(path):
    audit(path)
    report = json.loads(path.read_text(encoding="utf-8"))
    corpus_path = paired_path(path, report["configuration"]["corpusSnapshotPath"])
    result = ["# M19B 人工复核材料\n\n",
              f"运行 `{report['runId']}`；判定版本 `{report['policyVersion']}`。\n\n",
              "本文件只整理已执行问题的可见答案与真实证据，没有代填人工结论。\n\n",
              f"填写同目录的 `{path.stem}-review-template.jsonl`，另存为自己的复核文件。",
              "逐题确认参考标注、每项事实的 covered 和实际支撑引用，再判断关键冲突、核心陈述支撑或正确拒答。",
              "引用 quote 是显示用短引文；答案支撑必须根据本次实际截断后的 contextText 判断。\n\n",
              f"无答案标签需检查[完整冻结语料]({corpus_path.name})。语料完整性审计不能代替人工语义判断。\n\n",
              f"全部样本 {report['overall']['samples']}，已执行 {sum(c['observation']['executed'] for c in report['cases'])}，",
              f"未执行 {report['overall']['notRun']}；未执行样本仍在正式分母中。\n\n"]
    for case in report["cases"]:
        sample, observation, evaluation = case["sample"], case["observation"], case["evaluation"]
        if not observation["executed"] or observation["errorCode"] is not None:
            continue
        result.extend([f"## {sample['id']} — {sample['type']}\n\n",
                       f"可回答：{sample['answerable']}；事实覆盖阈值：{sample['factCoverageThreshold']}；",
                       f"当前结构判定：`{evaluation['status']}`；检索降级：{observation['degraded']}。\n\n",
                       "问题：\n\n", block(sample["question"])])
        if sample["history"]:
            result.extend(["固定可见历史：\n\n", block("\n".join(turn["role"] + ": " + turn["content"] for turn in sample["history"]))])
        if observation.get("retrievalTrace"):
            result.extend(["本次实际检索计划：\n\n", block(json.dumps(observation["retrievalTrace"], ensure_ascii=False, indent=2))])
        result.extend(["参考答案：\n\n", block(sample["referenceAnswer"]), "必需事实与标注证据：\n\n"])
        for fact in sample["requiredFacts"]:
            result.extend([f"- `{fact['id']}`；允许 chunks：{fact['supportingChunkIds']}\n\n",
                           block(fact["statement"]), "标注原文：\n\n", block(fact["sourceQuote"])])
        result.extend(["实际答案：\n\n", block(observation["answer"]),
                       f"模型：`{observation['modelName']}`；提示词 SHA-256：`{observation['promptSha256']}`。\n\n",
                       "答案中返回的引用：\n\n", block(json.dumps(observation["citations"], ensure_ascii=False, indent=2)),
                       "本次模型实际收到的证据（contextText）：\n\n"])
        for evidence in observation["evidence"]:
            citation = evidence["citation"]
            result.extend([f"### {sample['id']}/{citation['citationId']} — document {citation['documentId']}, chunk {citation['chunkId']}\n\n",
                           f"源内容 SHA-256：`{evidence['sourceContentSha256']}`。\n\n", block(evidence["contextText"])])
        if evaluation["failureReasons"]:
            result.extend(["现有失败/待复核原因：\n\n", block("\n".join(evaluation["failureReasons"]))])
    return "".join(result)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    output = args.report.with_name(args.report.stem + "-review-packet.md")
    output.write_text(packet(args.report), encoding="utf-8", newline="\n")
    print(output)
