"""Fixed M19C tasks for the existing KB 2 corpus; labels need human review.

Reuse source-grounded M19B labels, never predictions. Each conversation contains
only user turns: the runner obtains every assistant turn from the actual Agent.
"""
import json
import argparse
import hashlib
from copy import deepcopy
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SEED = Path(__file__).with_name('source-qa-labels.jsonl')
SEED_SHA256 = '20661d8bb07c17157c4979e6f5995380073e1252438d1f894ff2b0f9845b7fc1'
if hashlib.sha256(SEED.read_bytes()).hexdigest() != SEED_SHA256:
    raise ValueError('Frozen M19C label seed changed')
LABELS = {row['id']: row for row in
          (json.loads(line) for line in SEED.read_text(encoding='utf-8').splitlines())}
FACT_IDS = [row['id'] for row in LABELS.values() if row['type'] == 'SINGLE_DOCUMENT']
FORBIDDEN = ['crossKnowledgeBaseAccess', 'fabricatedCitation', 'hiddenReasoning',
             'unboundedReplanning', 'unboundedToolCalls', 'rawErrorLeak']
CORRECTIONS = json.loads(Path(__file__).with_name('source-label-corrections.json').read_text(encoding='utf-8'))['facts']


def build_rows():
    rows = []

    def add(mode, label_id, tags, tools=None, minimum=None, turns=None, question=None, fault=None):
        label = LABELS[label_id]
        questions = turns or [question or label['question']]
        tools = tools or []
        facts = deepcopy(label['requiredFacts'])
        acceptable = set(label['acceptableChunkIds'])
        replaced = set()
        for fact in facts:
            correction = CORRECTIONS.get(fact['id'])
            if correction:
                if (fact['supportingChunkIds'] != correction['previousChunkIds']
                        or fact['sourceQuote'] != correction['previousSourceQuote']):
                    raise ValueError(f"Source label changed; review correction for {fact['id']}")
                replaced.update(fact['supportingChunkIds'])
                fact['supportingChunkIds'] = correction['supportingChunkIds']
                fact['sourceQuote'] = correction['sourceQuote']
                acceptable.update(fact['supportingChunkIds'])
        current_support = {chunk for fact in facts for chunk in fact['supportingChunkIds']}
        acceptable.difference_update(replaced - current_support)
        rows.append({
            'caseId': f'AGENT-{len(rows) + 1:03}', 'knowledgeBaseId': 2, 'mode': mode,
            'turns': questions, 'maxTurns': 4, 'tags': tags,
            'answerable': label['answerable'], 'referenceAnswer': label['referenceAnswer'],
            'requiredFacts': facts, 'factCoverageThreshold': 1.0,
            'acceptableChunkIds': sorted(acceptable),
            'requiredToolCalls': tools, 'minimumSuccessfulToolCalls': minimum or {name: 1 for name in tools},
            'forbiddenBehaviors': FORBIDDEN, 'mustHaveValidCitation': label['answerable'],
            'expectedStatus': 'COMPLETED',
            'maxToolCalls': (0 if mode == 'RETRIEVAL_FIRST' else 6 if mode == 'REACT' else 12) * len(questions),
            'maxModelCalls': (2 if mode == 'RETRIEVAL_FIRST' else 6 if mode == 'REACT' else 12) * len(questions),
            'maxReplansPerTurn': 1 if mode == 'PLAN_EXECUTE_REFLECT' else 0,
            'minFinalReplans': 1 if fault and fault['action'] == 'EMPTY_EVIDENCE' else 0,
            'fault': fault, 'labelProvenance': label['labelProvenance']
        })

    def multi_turn(mode, ordinal, third_turn=False):
        label_id = f'T{ordinal:02}-zh'
        label = LABELS[label_id]
        topic = label['history'][0]['content']
        questions = [topic + '请查阅相关段落，简述该场景并附引用。']
        if third_turn:
            questions.append('继续查阅同一场景，确认我们刚才讨论的人物或对象，附引用。')
        questions.append(label['question'])
        add(mode, label_id, ['MULTI_TURN', 'COREFERENCE'],
            [] if mode == 'RETRIEVAL_FIRST' else ['searchKnowledgeBase'], turns=questions)

    for index in range(12):
        add('RETRIEVAL_FIRST', FACT_IDS[index * 3], ['SINGLE_DOCUMENT'])
    for ordinal in range(1, 9):
        multi_turn('RETRIEVAL_FIRST', ordinal, ordinal % 3 == 0)
    for ordinal in range(1, 6):
        add('RETRIEVAL_FIRST', f'X{ordinal:02}-zh', ['CROSS_DOCUMENT'])
    for ordinal in range(1, 6):
        add('RETRIEVAL_FIRST', f'N{ordinal:02}', ['REFUSAL'])

    for index in range(8):
        add('REACT', FACT_IDS[36 + index * 2], ['SINGLE_DOCUMENT'], ['searchKnowledgeBase'])
    for ordinal in range(6, 14):
        label_id = f'X{ordinal:02}-zh'
        add('REACT', label_id, ['CROSS_DOCUMENT', 'MULTIPLE_TOOLS', 'MULTI_STEP'],
            ['searchKnowledgeBase'], {'searchKnowledgeBase': 2},
            question='请分别检索两本书的证据，再比较并引用：' + LABELS[label_id]['question'])
    for ordinal in range(1, 7):
        label_id = FACT_IDS[ordinal * 7]
        add('REACT', label_id, ['MULTIPLE_TOOLS', 'MULTI_STEP'],
            ['searchKnowledgeBase', 'getDocumentMetadata', 'getChunkContext'],
            question='先检索相关段落，再核对来源文档元数据并读取相邻片段，回答并附引用：' + LABELS[label_id]['question'])
    for ordinal in range(9, 15):
        multi_turn('REACT', ordinal, ordinal % 2 == 0)
    for ordinal in range(4):
        label_id = FACT_IDS[55 + ordinal * 2]
        add('REACT', label_id, ['TOOL_FAILURE', 'SINGLE_DOCUMENT'], ['searchKnowledgeBase'],
            question='请查阅并附引用；检索失败时只允许有限次调整查询或安全说明资料不足：' + LABELS[label_id]['question'],
            fault={'turn': 1, 'toolName': 'searchKnowledgeBase', 'occurrence': 1, 'action': 'FAIL'})
    for ordinal in range(6, 9):
        add('REACT', f'N{ordinal:02}', ['REFUSAL'], ['searchKnowledgeBase'])

    for index in range(6):
        add('PLAN_EXECUTE_REFLECT', FACT_IDS[64 + index * 3], ['SINGLE_DOCUMENT'], ['searchKnowledgeBase'])
    for ordinal in range(1, 11):
        label_id = f'X{ordinal:02}-en'
        add('PLAN_EXECUTE_REFLECT', label_id, ['CROSS_DOCUMENT', 'MULTIPLE_TOOLS', 'MULTI_STEP'],
            ['searchKnowledgeBase'], {'searchKnowledgeBase': 2},
            question='Plan separate searches for the two books before comparing, with citations: ' + LABELS[label_id]['question'])
    for ordinal in range(1, 6):
        label_id = f'M{ordinal:02}-zh'
        add('PLAN_EXECUTE_REFLECT', label_id, ['MULTI_STEP', 'MULTIPLE_TOOLS'],
            ['searchKnowledgeBase', 'getChunkContext'],
            question='请分步检索、读取相邻上下文，然后综合回答并附引用：' + LABELS[label_id]['question'])
    for ordinal in range(11, 16):
        multi_turn('PLAN_EXECUTE_REFLECT', ordinal, ordinal % 2 == 0)
    for ordinal in range(6):
        label_id = FACT_IDS[10 + ordinal * 4]
        add('PLAN_EXECUTE_REFLECT', label_id, ['REPLAN', 'SINGLE_DOCUMENT'], ['searchKnowledgeBase'],
            question='请建立有限步骤计划检索并回答；遇到证据缺口最多重新规划一次，附引用：' + LABELS[label_id]['question'],
            fault={'turn': 1, 'toolName': 'searchKnowledgeBase', 'occurrence': 1, 'action': 'EMPTY_EVIDENCE'})
    for ordinal in (8, 9, 10):
        add('PLAN_EXECUTE_REFLECT', f'N{ordinal:02}', ['REFUSAL'], ['searchKnowledgeBase'])

    assert len(rows) == 100
    assert Counter(row['mode'] for row in rows) == {'RETRIEVAL_FIRST': 30, 'REACT': 35, 'PLAN_EXECUTE_REFLECT': 35}
    return rows


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Rebuild or check the frozen 100-task M19C dataset without model calls.')
    parser.add_argument('--check', action='store_true', help='Check the existing dataset without overwriting it.')
    args = parser.parse_args()
    rows = build_rows()
    output = Path(__file__).with_name('agent-tasks.jsonl')
    if args.check:
        frozen = [json.loads(line) for line in output.read_text(encoding='utf-8').splitlines() if line.strip()]
        if frozen != rows:
            raise ValueError('Task dataset differs from the frozen source labels and build rules')
        print(f'Verified {len(rows)} fixed draft tasks; SHA-256={hashlib.sha256(output.read_bytes()).hexdigest()}')
    else:
        output.write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in rows), encoding='utf-8', newline='\n')
        print(f'Wrote {len(rows)} draft tasks: {dict(Counter(row["mode"] for row in rows))}')
