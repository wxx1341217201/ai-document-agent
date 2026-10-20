"""Exercise the real Java-client wire format (chunked POST), using only fake scoring."""
import http.client
import json
import math
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import socket
import subprocess
import sys
import time
import unittest


class ModelServerWireTest(unittest.TestCase):
    def test_chunked_post_returns_scores_for_every_original_id(self):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            port = listener.getsockname()[1]
        process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('local_model_server.py')),
                                    '--mode', 'FAKE_FIXED_LATENCY', '--port', str(port)],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            for _ in range(60):
                try:
                    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=2)
                    connection.request('GET', '/health')
                    response = connection.getresponse()
                    response.read()
                    connection.close()
                    if response.status == 200:
                        break
                except OSError:
                    time.sleep(.05)
            else:
                self.fail('Server not ready')
            body = json.dumps({'query': 'retrieval', 'documents': [
                {'id': '101', 'text': 'hybrid retrieval'}, {'id': '102', 'text': 'upload'}]}).encode()
            connection = http.client.HTTPConnection('127.0.0.1', port, timeout=2)
            connection.request('POST', '/rerank', body=iter([body[:11], body[11:]]),
                               headers={'Content-Type': 'application/json'}, encode_chunked=True)
            response = connection.getresponse()
            self.assertEqual(response.status, 200)
            scores = json.loads(response.read())['results']
            self.assertEqual([r['id'] for r in scores], ['101', '102'])
            self.assertGreater(scores[0]['score'], scores[1]['score'])
            connection.close()
            # Ten clients exercise connection reuse/churn, matching the benchmark concurrency.
            def embed(index):
                client = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
                payload = json.dumps({'input': ['中文检索编号 ' + str(index)]}).encode()
                try:
                    client.request('POST', '/embeddings', body=payload,
                                   headers={'Content-Type': 'application/json'})
                    response = client.getresponse()
                    decoded = json.loads(response.read())
                    self.assertEqual(response.status, 200)
                    values = decoded['data'][0]['embedding']
                    self.assertEqual(len(values), 1024)
                    self.assertTrue(all(math.isfinite(value) for value in values))
                finally:
                    client.close()
            with ThreadPoolExecutor(max_workers=10) as clients:
                list(clients.map(embed, range(200)))
        finally:
            process.terminate()
            process.wait(timeout=5)


if __name__ == '__main__':
    unittest.main()
