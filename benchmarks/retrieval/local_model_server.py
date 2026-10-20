"""Loopback-only real ONNX Cross-Encoder or clearly marked deterministic fake models.

Never imports a benchmark dataset or reads expectedChunkIds. Every inference is
performed on the request's query and candidate text; there is no result cache.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def tokens(text):
    return re.findall(r'[a-z0-9_]+|[\u4e00-\u9fff]', text.lower())


def fake_embedding(text, dimensions):
    vector = [0.0] * dimensions
    for token in tokens(text):
        digest = hashlib.sha256(token.encode('utf-8')).digest()
        index = int.from_bytes(digest[:4], 'little') % dimensions
        vector[index] += 1.0 if digest[4] & 1 else -1.0
    norm = math.sqrt(sum(v * v for v in vector)) or 1.0
    return [v / norm for v in vector]


class Models:
    def __init__(self, args):
        self.args = args
        self.slots = threading.BoundedSemaphore(args.parallelism)
        self.metadata = dict(profile=args.mode, cacheEnabled=False, cpu=platform.processor(),
                             instanceId=args.instance_id,
                             logicalProcessors=os.cpu_count(), python=platform.python_version(),
                             maxLength=args.max_length, batchSize=args.batch_size,
                             intraOpThreads=args.threads, parallelism=args.parallelism)
        if args.mode == 'LOCAL_REAL':
            if args.dependencies:
                sys.path.insert(0, str(Path(args.dependencies).resolve()))
            import numpy as np
            import onnxruntime as ort
            from tokenizers import Tokenizer
            self.np = np
            options = ort.SessionOptions()
            options.intra_op_num_threads = args.threads
            options.inter_op_num_threads = 1
            options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
            model = Path(args.model_directory) / 'model.onnx'
            self.session = ort.InferenceSession(str(model), sess_options=options, providers=['CPUExecutionProvider'])
            self.tokenizer = Tokenizer.from_file(str(Path(args.model_directory) / 'tokenizer.json'))
            self.tokenizer.enable_truncation(max_length=args.max_length, strategy='longest_first')
            self.tokenizer.enable_padding(pad_id=1, pad_token='<pad>')
            self.metadata.update(model='cross-encoder/mmarco-mMiniLMv2-L12-H384-v1',
                                 revision='1427fd652930e4ba29e8149678df786c240d8825',
                                 precision='ONNX quint8 avx2', onnxruntime=ort.__version__,
                                 numpy=np.__version__,
                                 tokenizers=__import__('tokenizers').__version__,
                                 modelSha256=hashlib.sha256(model.read_bytes()).hexdigest(),
                                 tokenizerSha256=hashlib.sha256((Path(args.model_directory) / 'tokenizer.json').read_bytes()).hexdigest())
        else:
            self.metadata.update(model='FAKE lexical overlap; not a Cross-Encoder',
                                 embeddingModel='FAKE signed token hashing; not a semantic model',
                                 embeddingDelayMs=args.embedding_delay_ms, rerankDelayMs=args.rerank_delay_ms)

    def rerank(self, query, documents):
        if self.args.mode == 'FAKE_FIXED_LATENCY':
            time.sleep(self.args.rerank_delay_ms / 1000)
            q = set(tokens(query))
            return [len(q.intersection(tokens(d['text']))) / max(len(q), 1) for d in documents]
        scores = []
        with self.slots:
            # The queue for this semaphore is part of the observed HTTP latency.
            for start in range(0, len(documents), self.args.batch_size):
                batch = documents[start:start + self.args.batch_size]
                encoded = self.tokenizer.encode_batch([(query, d['text']) for d in batch])
                features = dict(input_ids=self.np.array([e.ids for e in encoded], dtype=self.np.int64),
                                attention_mask=self.np.array([e.attention_mask for e in encoded], dtype=self.np.int64),
                                token_type_ids=self.np.array([e.type_ids for e in encoded], dtype=self.np.int64))
                inputs = {i.name: features[i.name] for i in self.session.get_inputs()}
                logits = self.session.run(None, inputs)[0].reshape(-1)
                scores.extend(float(x) for x in logits)
        return scores


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--mode', choices=['LOCAL_REAL', 'FAKE_FIXED_LATENCY'], default='LOCAL_REAL')
    parser.add_argument('--port', type=int, default=8098)
    parser.add_argument('--instance-id', default='manual')
    parser.add_argument('--model-directory', default='target/retrieval-benchmark/models/mmarco')
    parser.add_argument('--dependencies', default='target/retrieval-benchmark/python')
    parser.add_argument('--max-length', type=int, default=256)
    parser.add_argument('--batch-size', type=int, default=8)
    parser.add_argument('--threads', type=int, default=2)
    parser.add_argument('--parallelism', type=int, default=2)
    parser.add_argument('--dimensions', type=int, default=1024)
    parser.add_argument('--embedding-delay-ms', type=int, default=5)
    parser.add_argument('--rerank-delay-ms', type=int, default=20)
    args = parser.parse_args()
    if min(args.threads, args.parallelism, args.batch_size, args.max_length, args.dimensions) < 1:
        parser.error('Model sizes and concurrency must be positive')
    models = Models(args)

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *_):
            pass  # No query text, documents, authentication headers or provider response bodies in logs.

        def respond(self, status, value):
            body = json.dumps(value, ensure_ascii=False).encode('utf-8')
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_GET(self):
            if self.path in ('/health', '/metadata'):
                self.respond(200, models.metadata)
            else:
                self.respond(404, {'error': 'NOT_FOUND'})

        def do_POST(self):
            try:
                if 'chunked' in self.headers.get('Transfer-Encoding', '').lower():
                    body = bytearray()
                    while True:
                        size = int(self.rfile.readline(128).split(b';')[0].strip(), 16)
                        if size == 0:
                            while self.rfile.readline(8192).strip():
                                pass
                            break
                        if len(body) + size > 2_000_000:
                            raise ValueError('Request too large')
                        block = self.rfile.read(size)
                        if len(block) != size or self.rfile.read(2) != b'\r\n':
                            raise ValueError('Invalid chunk')
                        body.extend(block)
                else:
                    length = int(self.headers.get('Content-Length', '0'))
                    if length <= 0 or length > 2_000_000:
                        raise ValueError('Invalid length')
                    body = self.rfile.read(length)
                request = json.loads(body)
                if self.path == '/rerank':
                    query, documents = request['query'], request['documents']
                    if not isinstance(query, str) or not isinstance(documents, list) or len(documents) > 100:
                        raise ValueError('Invalid request')
                    scores = models.rerank(query, documents)
                    self.respond(200, {'results': [dict(id=d['id'], score=score)
                                                  for d, score in zip(documents, scores, strict=True)]})
                elif self.path in ('/v1/embeddings', '/embeddings') and args.mode == 'FAKE_FIXED_LATENCY':
                    values = request['input']
                    if isinstance(values, str):
                        values = [values]
                    time.sleep(args.embedding_delay_ms / 1000)
                    self.respond(200, dict(object='list', model='fake-token-hash-v1',
                                           data=[dict(object='embedding', index=i, embedding=fake_embedding(t, args.dimensions))
                                                 for i, t in enumerate(values)],
                                           usage=dict(prompt_tokens=0, total_tokens=0)))
                else:
                    self.respond(404, {'error': 'NOT_FOUND'})
            except Exception as error:
                self.respond(500, {'error': type(error).__name__})

    class LoopbackServer(ThreadingHTTPServer):
        request_queue_size = 128

    models.metadata.update(httpProtocol=Handler.protocol_version, requestBacklog=LoopbackServer.request_queue_size)
    print(json.dumps(models.metadata), flush=True)
    with LoopbackServer(('127.0.0.1', args.port), Handler) as server:
        server.serve_forever()


if __name__ == '__main__':
    main()
