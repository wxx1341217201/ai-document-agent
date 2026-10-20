"""Freeze original, publicly labelled CMRC 2018 / HotpotQA development samples.

No model calls, question generation, answer rewriting, or prediction-based sampling.
Derived data retains the upstream CC BY-SA 4.0 licence (see ATTRIBUTION.md).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SOURCES = {
    "cmrc2018_dev.json": "https://raw.githubusercontent.com/ymcui/cmrc2018/master/squad-style-data/cmrc2018_dev.json",
    "cmrc2018.LICENCE": "https://raw.githubusercontent.com/ymcui/cmrc2018/master/LICENCE",
    "cmrc2018_evaluate.py": "https://raw.githubusercontent.com/ymcui/cmrc2018/master/squad-style-data/cmrc2018_evaluate.py",
    "hotpot_dev_distractor_v1.parquet": "https://huggingface.co/datasets/hotpotqa/hotpot_qa/resolve/main/distractor/validation-00000-of-00001.parquet",
    "hotpot_evaluate_v1.py": "https://raw.githubusercontent.com/hotpotqa/hotpot/master/hotpot_evaluate_v1.py",
    "hotpot.LICENSE.txt": "https://raw.githubusercontent.com/hotpotqa/hotpot/master/LICENSE.txt",
}
EXPECTED_HASHES = {"hotpot_dev_distractor_v1.parquet": "c20b638ca82b21d04fe12e14ff417ad05153d4d215a65de54497fca4e972f7c6"}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def json_bytes(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def write_jsonl(path: Path, rows: list[dict]) -> None:
    content = "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows)
    path.write_bytes(content.encode("utf-8"))


def fetch(url: str, path: Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "ai-document-agent-public-benchmark/1.0"})
    partial = path.with_suffix(path.suffix + ".part")
    with urllib.request.urlopen(request, timeout=45) as response, partial.open("wb") as output:
        while block := response.read(1024 * 1024):
            output.write(block)
    partial.replace(path)


def download(raw: Path) -> dict:
    raw.mkdir(parents=True, exist_ok=True)
    lock_path = raw / "sources.lock.json"
    old = read_json(lock_path) if lock_path.exists() else {}
    locked = {item["file"]: item for item in old.get("files", [])}
    files = []
    for name, declared_url in SOURCES.items():
        path = raw / name
        item = locked.get(name)
        if path.exists():
            digest = sha(path.read_bytes())
            if not item or digest != item["sha256"]:
                raise ValueError(f"Untracked or changed cached source: {path}")
        else:
            url = item["url"] if item else declared_url
            print(f"Downloading {name}", flush=True)
            fetch(url, path)
            digest = sha(path.read_bytes())
            if name in EXPECTED_HASHES and digest != EXPECTED_HASHES[name]:
                raise ValueError(f"Public release checksum mismatch: {name}")
            if item and digest != item["sha256"]:
                raise ValueError(f"Upstream source changed: {name}; keep this version separate")
            item = {"file": name, "url": url, "sha256": digest, "bytes": path.stat().st_size}
            # Journal each successful download so an interrupted preparation is resumable.
            locked[name] = item
            lock_path.write_bytes(json_bytes({"files": list(locked.values())}))
        if name in EXPECTED_HASHES and digest != EXPECTED_HASHES[name]:
            raise ValueError(f"Cached public release checksum mismatch: {name}")
        files.append(item)
    result = {"files": files}
    lock_path.write_bytes(json_bytes(result))
    return result


def document(dataset: str, title: str, text: str) -> dict:
    key = f"{dataset}:{sha((title + chr(0) + text).encode('utf-8'))}"
    return {"id": key, "dataset": dataset, "title": title, "text": text, "textSha256": sha(text.encode("utf-8"))}


def cmrc_sources(raw: Path) -> tuple[dict[str, dict], dict[str, dict]]:
    corpus, questions = {}, {}
    for article in read_json(raw / "cmrc2018_dev.json")["data"]:
        for paragraph in article["paragraphs"]:
            doc = document("cmrc2018", article.get("title", ""), paragraph["context"])
            corpus[doc["id"]] = doc
            for question in paragraph["qas"]:
                if question["id"] in questions:
                    raise ValueError("Duplicate upstream CMRC question id")
                evidence = []
                for answer in question["answers"]:
                    start, text = answer["answer_start"], answer["text"]
                    # Upstream also includes answer aliases with answer_start=-1.
                    # Keep every official accepted answer; only anchored annotations supply spans.
                    if start >= 0 and doc["text"][start:start + len(text)] == text:
                        evidence.append({"documentId": doc["id"], "start": start, "end": start + len(text),
                                         "text": text, "kind": "answer_span"})
                if not question["answers"]:
                    raise ValueError(f"CMRC question without accepted answers: {question['id']}")
                questions[question["id"]] = {"id": "cmrc2018:" + question["id"], "sourceId": question["id"],
                    "dataset": "cmrc2018", "split": "dev", "language": "zh", "question": question["question"],
                    "answers": list(dict.fromkeys(answer["text"] for answer in question["answers"])),
                    "answerAnnotations": question["answers"],
                    "spanEvidenceAvailable": bool(evidence),
                    "relevantDocumentIds": [doc["id"]], "evidence": evidence,
                    "answerable": True, "labelProvenance": "upstream_human_annotations"}
    return corpus, questions


def hotpot_sources(raw: Path) -> tuple[dict[str, dict], dict[str, dict]]:
    sys.path.insert(0, str(ROOT / "runtime" / "python-libs"))
    try:
        import pyarrow.parquet as parquet
    except ImportError as error:
        raise RuntimeError("Install benchmarks/public/requirements.txt into benchmarks/public/runtime/python-libs") from error
    corpus, questions = {}, {}
    for row in parquet.read_table(raw / "hotpot_dev_distractor_v1.parquet").to_pylist():
        question = {**row, "_id": row["id"],
                    "context": list(zip(row["context"]["title"], row["context"]["sentences"], strict=True)),
                    "supporting_facts": list(zip(row["supporting_facts"]["title"], row["supporting_facts"]["sent_id"], strict=True))}
        if question["_id"] in questions:
            raise ValueError("Duplicate upstream HotpotQA question id")
        context = {}
        for title, sentences in question["context"]:
            if title in context:
                raise ValueError(f"Duplicate HotpotQA context title: {question['_id']}")
            # Concatenation preserves the original sentence text and exact character spans.
            doc = document("hotpotqa", title, "".join(sentences))
            corpus[doc["id"]] = doc
            context[title] = (doc, sentences)
        evidence, evidence_issues, relevant_docs = [], [], set()
        for title, sentence_id in question["supporting_facts"]:
            if title not in context:
                evidence_issues.append({"title": title, "sentenceId": sentence_id, "issue": "SOURCE_TITLE_NOT_IN_CONTEXT"})
                continue
            doc, sentences = context[title]
            relevant_docs.add(doc["id"])
            if not 0 <= sentence_id < len(sentences):
                evidence_issues.append({"title": title, "sentenceId": sentence_id, "issue": "SENTENCE_ID_OUT_OF_BOUNDS"})
                continue
            start = sum(len(sentence) for sentence in sentences[:sentence_id])
            text = sentences[sentence_id]
            evidence.append({"documentId": doc["id"], "start": start, "end": start + len(text),
                             "text": text, "kind": "supporting_fact", "title": title, "sentenceId": sentence_id})
        questions[question["_id"]] = {"id": "hotpotqa:" + question["_id"], "sourceId": question["_id"],
            "dataset": "hotpotqa", "split": "dev_distractor", "language": "en", "question": question["question"],
            "answers": [question["answer"]], "relevantDocumentIds": sorted(relevant_docs),
            "sourceSupportingFacts": [list(pair) for pair in question["supporting_facts"]], "evidenceIssues": evidence_issues,
            "relevanceLabelsComplete": not any(issue["issue"] == "SOURCE_TITLE_NOT_IN_CONTEXT" for issue in evidence_issues),
            "evidence": evidence, "candidateDocumentIds": [doc["id"] for doc, _ in context.values()],
            "answerable": True, "type": question["type"], "level": question["level"],
            "labelProvenance": "upstream_human_annotations"}
    return corpus, questions


def select(questions: dict[str, dict], count: int, seed: int) -> list[dict]:
    if not 1 <= count <= len(questions):
        raise ValueError(f"Sample count must be 1..{len(questions)}")
    return sorted(questions.values(), key=lambda item: sha(f"{seed}|{item['id']}".encode("utf-8")))[:count]


def build(raw: Path, output: Path, cmrc_count: int, hotpot_count: int, seed: int) -> dict:
    if output.exists():
        raise ValueError(f"Output already exists; validate or choose a new directory: {output}")
    source_lock = download(raw)
    cmrc_corpus, cmrc_questions = cmrc_sources(raw)
    hotpot_corpus, hotpot_questions = hotpot_sources(raw)
    cmrc = select(cmrc_questions, cmrc_count, seed)
    hotpot = select(hotpot_questions, hotpot_count, seed)
    pooled_hotpot_ids = {key for sample in hotpot for key in sample["candidateDocumentIds"]}
    corpus = list(cmrc_corpus.values()) + [hotpot_corpus[key] for key in sorted(pooled_hotpot_ids)]
    qa = cmrc + hotpot
    retrieval = [{key: sample[key] for key in ("id", "dataset", "question", "relevantDocumentIds", "evidence")}
                 for sample in qa]
    tasks = [{"id": "agent:" + sample["id"], "sourceQaId": sample["id"], "turns": [sample["question"]],
              "modes": ["RETRIEVAL_FIRST", "REACT", "PLAN_EXECUTE_REFLECT"],
              "criterion": "completed_and_official_answer_exact_match_and_citation_to_gold_document",
              "scope": "single_turn_public_qa_task_not_full_business_agent_suite"} for sample in hotpot]
    output.mkdir(parents=True)
    for name, rows in (("corpus.jsonl", corpus), ("qa.jsonl", qa), ("retrieval.jsonl", retrieval), ("agent-tasks.jsonl", tasks)):
        write_jsonl(output / name, rows)
    (output / "sources.lock.json").write_bytes(json_bytes(source_lock))
    manifest = {"schemaVersion": "public-benchmark-v1", "createdAt": datetime.now(timezone.utc).isoformat(),
        "seed": seed, "selection": "first N by SHA256(seed|dataset:original_id); before any predictions",
        "licence": "CC-BY-SA-4.0", "rawDirectory": str(raw.resolve()),
        "cmrc2018": {"officialQuestions": len(cmrc_questions), "sampledQuestions": len(cmrc),
                     "corpusDocuments": len(cmrc_corpus), "corpusScope": "all official development contexts",
                     "unanchoredUpstreamAliases": sum(answer["answer_start"] < 0 for sample in cmrc_questions.values() for answer in sample["answerAnnotations"]),
                     "questionsWithoutAnchoredSpan": sum(not sample["evidence"] for sample in cmrc_questions.values())},
        "hotpotqa": {"officialQuestions": len(hotpot_questions), "sampledQuestions": len(hotpot),
                     "corpusDocuments": len(pooled_hotpot_ids), "corpusScope": "union of all distractor contexts for selected questions; not fullwiki",
                     "downloadSource": "hotpotqa/hotpot_qa Hugging Face Parquet release; verified SHA256; original CMU host timed out",
                     "upstreamQuestionsWithEvidenceIssues": sum(bool(sample["evidenceIssues"]) for sample in hotpot_questions.values()),
                     "sampledQuestionsWithEvidenceIssues": sum(bool(sample["evidenceIssues"]) for sample in hotpot)},
        "counts": {"qa": len(qa), "retrieval": len(retrieval), "agentSourceTasks": len(tasks),
                   "agentExecutionsAllModes": len(tasks) * 3, "corpusDocuments": len(corpus)},
        "limitations": ["Adapted public development subsets, not official leaderboard evaluations.",
                         "All upstream samples are answerable; no refusal or multi-turn labels are invented.",
                         "Document relevance is distinct from production chunk Recall@5.",
                         "Public QA tasks do not measure business tool failure or session recovery.",
                         "No model run, quality score, latency or target attainment is implied by preparation."],
        "files": {path.name: sha(path.read_bytes()) for path in sorted(output.iterdir()) if path.is_file()}}
    (output / "manifest.json").write_bytes(json_bytes(manifest))
    return manifest


def validate(raw: Path, output: Path) -> dict:
    manifest = read_json(output / "manifest.json")
    for name, digest in manifest["files"].items():
        if sha((output / name).read_bytes()) != digest:
            raise ValueError(f"Prepared artifact changed: {name}")
    for source in read_json(output / "sources.lock.json")["files"]:
        if sha((raw / source["file"]).read_bytes()) != source["sha256"]:
            raise ValueError(f"Original source changed: {source['file']}")
        if source["file"] in EXPECTED_HASHES and source["sha256"] != EXPECTED_HASHES[source["file"]]:
            raise ValueError("Published release checksum differs")
    original_corpus, cmrc = cmrc_sources(raw)
    hotpot_corpus, hotpot = hotpot_sources(raw)
    originals = {sample["id"]: sample for sample in [*cmrc.values(), *hotpot.values()]}
    original_corpus.update(hotpot_corpus)
    load = lambda name: [json.loads(line) for line in (output / name).read_text(encoding="utf-8").splitlines()]
    corpus_rows = load("corpus.jsonl")
    corpus = {row["id"]: row for row in corpus_rows}
    if len(corpus) != len(corpus_rows):
        raise ValueError("Duplicate corpus ids")
    for key, row in corpus.items():
        if row != original_corpus[key]:
            raise ValueError(f"Changed original context: {key}")
    samples = load("qa.jsonl")
    if len({sample["id"] for sample in samples}) != len(samples):
        raise ValueError("Duplicate question ids")
    for sample in samples:
        if sample != originals[sample["id"]]:
            raise ValueError(f"Changed original question, answer or evidence: {sample['id']}")
        if not set(sample["relevantDocumentIds"]) <= corpus.keys():
            raise ValueError("Missing gold source document")
        for span in sample["evidence"]:
            if corpus[span["documentId"]]["text"][span["start"]:span["end"]] != span["text"]:
                raise ValueError("Source evidence mismatch")
    expected = select(cmrc, manifest["cmrc2018"]["sampledQuestions"], manifest["seed"]) + select(hotpot, manifest["hotpotqa"]["sampledQuestions"], manifest["seed"])
    if samples != expected:
        raise ValueError("Sample selection changed")
    retrieval = load("retrieval.jsonl")
    if retrieval != [{key: sample[key] for key in ("id", "dataset", "question", "relevantDocumentIds", "evidence")} for sample in samples]:
        raise ValueError("Retrieval labels differ from original QA evidence")
    expected_corpus = set(cmrc_sources(raw)[0]) | {key for sample in samples if sample["dataset"] == "hotpotqa" for key in sample["candidateDocumentIds"]}
    if set(corpus) != expected_corpus:
        raise ValueError("Distractor corpus changed")
    if len(load("agent-tasks.jsonl")) != manifest["counts"]["agentSourceTasks"]:
        raise ValueError("Agent task count mismatch")
    return {"status": "VALIDATED", "counts": manifest["counts"], "manifestSha256": sha((output / "manifest.json").read_bytes()),
            "sourceLabels": "original public annotations; questions/answers/evidence unchanged", "metricsStatus": "NOT_RUN"}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["prepare", "validate"])
    parser.add_argument("--raw", type=Path, default=ROOT / "raw")
    parser.add_argument("--output", type=Path, default=ROOT / "prepared" / "cmrc200-hotpot100-seed20261019")
    parser.add_argument("--cmrc-samples", type=int, default=200)
    parser.add_argument("--hotpot-samples", type=int, default=100)
    parser.add_argument("--seed", type=int, default=20261019)
    args = parser.parse_args()
    if args.mode == "prepare":
        build(args.raw, args.output, args.cmrc_samples, args.hotpot_samples, args.seed)
    print(json.dumps(validate(args.raw, args.output), ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
