"""Independent recomputation from frozen labels, Top-5 IDs and raw nanosecond samples."""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path


def read_jsonl(path):
    return [json.loads(line) for line in Path(path).read_text(encoding='utf-8-sig').splitlines() if line.strip()]


def verify(report_path):
    report = json.loads(Path(report_path).read_text(encoding='utf-8-sig'))
    assert report['status'] == 'COMPLETE', f"Run is {report['status']}"
    assert report['schemaVersion'] == 'm19a-v2'
    labels_path = Path(report['dataset']['snapshot'])
    assert hashlib.sha256(labels_path.read_bytes()).hexdigest() == report['dataset']['sha256']
    labels = read_jsonl(labels_path)
    assert len(labels) == report['dataset']['cases'] >= 200
    assert len({q['id'] for q in labels}) == len(labels)
    assert sum(q['answerable'] for q in labels) == report['dataset']['answerable']
    required_tags = {'exact', 'semantic', 'chinese_synonym', 'cross_context', 'cross_document',
                     'distractor', 'unanswerable', 'error_code', 'identifier'}
    assert required_tags <= {tag for q in labels for tag in q['tags']}
    corpus_rows = read_jsonl(report['corpus']['snapshot'])
    corpus = {c['chunkId']: c for c in corpus_rows}
    assert len(corpus) == len(corpus_rows)
    assert len(corpus) == report['corpus']['chunks']
    corpus_bytes = json.dumps(sorted(corpus_rows, key=lambda c: c['chunkId']),
                              ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    assert hashlib.sha256(corpus_bytes).hexdigest() == report['corpus']['fingerprint']
    for q in labels:
        assert q['knowledgeBaseId'] == report['dataset']['knowledgeBaseId']
        assert q['answerable'] == bool(q['expectedChunkIds'])
        assert all(corpus[i]['knowledgeBaseId'] == q['knowledgeBaseId'] for i in q['expectedChunkIds'])
        if 'cross_document' in q['tags']:
            assert len({corpus[i]['documentId'] for i in q['expectedChunkIds']}) >= 2
    assert len(report['variants']) == 4
    journal = read_jsonl(report['observations'])
    for variant, result in report['variants'].items():
        assert result['status'] == 'COMPLETE' and not result['errors'], variant
        predictions = result['top5ByCase']
        assert set(predictions) == {q['id'] for q in labels}
        quality = [r['observation'] for r in journal if r['phase'] == 'QUALITY' and r['variant'] == variant]
        assert len(quality) == len(labels) and len({r['caseId'] for r in quality}) == len(labels)
        assert all(r['errorCode'] is None and predictions[r['caseId']] == r['top5'] for r in quality)
        sums = {}
        for q in labels:
            predicted = predictions[q['id']]
            assert len(predicted) <= 5 and len(set(predicted)) == len(predicted)
            assert all(corpus[i]['knowledgeBaseId'] == q['knowledgeBaseId'] for i in predicted)
            relevant = set(q['expectedChunkIds'])
            ranks = [i + 1 for i, chunk in enumerate(predicted) if chunk in relevant]
            for tag in ['all', *q['tags']]:
                bucket = sums.setdefault(tag, Counter())
                if not q['answerable']:
                    bucket['unanswerable'] += 1
                    bucket['emptyResultsForUnanswerable'] += not predicted
                    continue
                bucket['answerable'] += 1
                bucket['hitsAt5'] += bool(ranks)
                bucket['mrr'] += 1 / ranks[0] if ranks else 0
                ideal = sum(1 / math.log2(i + 2) for i in range(min(5, len(relevant))))
                bucket['ndcg'] += sum(1 / math.log2(rank + 1) for rank in ranks) / ideal
        assert set(sums) == set(result['byTag'])
        for tag, expected in sums.items():
            actual = result['byTag'][tag]
            for field in ['answerable', 'hitsAt5', 'unanswerable', 'emptyResultsForUnanswerable']:
                assert actual[field] == expected[field], (variant, tag, field)
            denominator = expected['answerable']
            for field, total in [('recallAt5', expected['hitsAt5']), ('mrrAt5', expected['mrr']), ('ndcgAt5', expected['ndcg'])]:
                value = total / denominator if denominator else 0
                assert abs(actual[field] - value) < 1e-12, (variant, tag, field)
    latency = report['latency']
    assert latency['status'] == 'COMPLETE' and latency['errors'] == latency['warmupErrors'] == 0
    assert latency['warmup'] >= 50 and latency['samples'] >= 500
    assert latency['samples'] == latency['requestedSamples'] == latency['successes']
    assert latency['concurrency'] == report['configuration']['concurrency']
    assert latency['errorRate'] == 0
    warmups = [r for r in journal if r['phase'] == 'WARMUP']
    samples = [r for r in journal if r['phase'] == 'SAMPLE']
    assert len(warmups) == latency['warmup'] and all(r['errorCode'] is None for r in warmups)
    assert {r['ordinal'] for r in warmups} == set(range(latency['warmup']))
    assert len(samples) == latency['samples']
    assert {r['ordinal'] for r in samples} == set(range(latency['samples']))
    assert len(latency['sampleRecords']) == len(samples)
    answerable_ids = {q['id'] for q in labels if q['answerable']}
    assert all(r['caseId'] in answerable_ids and r['durationNanos'] > 0 and r['rerankNanos'] >= 0
               for r in warmups + samples)
    formal_ids = {r['ordinal']: r for r in samples}
    assert all(formal_ids[r['ordinal']] == r for r in latency['sampleRecords'])
    assert max(r['startedAt'] for r in warmups) < min(r['startedAt'] for r in samples)
    times = sorted(r['durationNanos'] for r in samples if r['errorCode'] is None)
    assert len(times) == latency['successes']
    for name, quantile in [('p50', .50), ('p95', .95), ('p99', .99)]:
        expected = times[math.ceil(quantile * len(times)) - 1] / 1e6
        assert abs(latency['statistics'][name] - expected) < 1e-9, name
    assert abs(latency['statistics']['average'] - sum(times) / len(times) / 1e6) < 1e-8
    assert latency['statistics']['max'] == max(times) / 1e6
    rerank_times = sorted(r['rerankNanos'] for r in samples)
    for name, quantile in [('p50', .50), ('p95', .95), ('p99', .99)]:
        assert abs(latency['rerankStatistics'][name] - rerank_times[math.ceil(quantile * len(samples)) - 1] / 1e6) < 1e-9
    assert abs(latency['rerankStatistics']['average'] - sum(rerank_times) / len(samples) / 1e6) < 1e-8
    assert latency['rerankStatistics']['max'] == max(rerank_times) / 1e6
    assert report['targets']['recallAt5'] == report['variants']['Vector + BM25 + RRF + Cross-Encoder']['byTag']['all']['recallAt5']
    assert report['targets']['recallMet'] == (report['targets']['recallAt5'] >= .91)
    assert report['targets']['p95Met'] == (latency['statistics']['p95'] <= 490)
    assert report['evidenceUnchanged']
    if report['configuration']['profile'] == 'FAKE_FIXED_LATENCY':
        assert report['targets']['status'] == 'REGRESSION_ONLY' and not report['targets']['claimEligible']
    elif report['labelReview']['status'] != 'HUMAN_REVIEWED':
        assert report['targets']['status'] == 'PROVISIONAL_PENDING_HUMAN_LABEL_REVIEW'
        assert not report['targets']['claimEligible']
    return dict(status='PASSED', runId=report['runId'], questions=len(labels), samples=len(samples),
                profile=report['configuration']['profile'], recallAt5=report['targets']['recallAt5'],
                p95Ms=latency['statistics']['p95'], claimEligible=report['targets']['claimEligible'])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('reports', nargs='+')
    parser.add_argument('--output')
    args = parser.parse_args()
    output = [verify(path) for path in args.reports]
    serialized = json.dumps(output, indent=2)
    if args.output:
        Path(args.output).write_text(serialized + '\n', encoding='utf-8')
    print(serialized)


if __name__ == '__main__':
    main()
