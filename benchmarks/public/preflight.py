"""Check existing application/model access without exposing credentials or changing services."""

from __future__ import annotations

import argparse
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]


def configuration() -> dict[str, str]:
    values = {}
    env_file = REPO / ".env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8-sig").splitlines():
            if not line.strip() or line.lstrip().startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip().strip("\"'")
    values.update(os.environ)
    return values


def request_check(name: str, url: str, body: dict | None = None, key: str | None = None) -> dict:
    started = time.perf_counter()
    headers = {"Content-Type": "application/json", "User-Agent": "public-benchmark-preflight/1.0"}
    if key:
        headers["Authorization"] = "Bearer " + key
    result = {"name": name, "url": url, "status": "FAILED"}
    try:
        request = urllib.request.Request(url, data=json.dumps(body).encode("utf-8") if body is not None else None, headers=headers)
        local = urllib.parse.urlparse(url).hostname in ("localhost", "127.0.0.1", "::1")
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({})) if local else urllib.request.build_opener()
        with opener.open(request, timeout=25) as response:
            payload = json.loads(response.read())
            result["httpStatus"] = response.status
            if name == "embedding":
                vectors = payload.get("data", [])
                if len(vectors) != 1 or len(vectors[0].get("embedding", [])) != body["dimensions"]:
                    raise ValueError("INVALID_EMBEDDING_DIMENSIONS")
                result["dimensions"] = len(vectors[0]["embedding"])
            elif name == "chat":
                if not payload.get("choices") or not payload["choices"][0].get("message", {}).get("content"):
                    raise ValueError("MISSING_CHAT_RESPONSE")
                result["modelReturned"] = payload.get("model")
            elif name == "rerank":
                if not payload.get("output", {}).get("results"):
                    raise ValueError("MISSING_RERANK_RESULTS")
            elif payload.get("code") != "SUCCESS":
                raise ValueError("APPLICATION_UNAVAILABLE")
            result["status"] = "AVAILABLE"
    except urllib.error.HTTPError as error:
        result["httpStatus"] = error.code
        result["error"] = "AUTHENTICATION_FAILED" if error.code in (401, 403) else "RATE_LIMITED" if error.code == 429 else "HTTP_ERROR"
        try:
            payload = json.loads(error.read())
            details = payload.get("error")
            code = details.get("code", details.get("type")) if isinstance(details, dict) else payload.get("code")
            if isinstance(code, str) and re.fullmatch(r"[A-Za-z0-9_.:-]{1,100}", code):
                result["providerCode"] = code
        except (ValueError, AttributeError):
            pass
    except Exception as error:
        result["error"] = type(error).__name__
    result["durationMs"] = round((time.perf_counter() - started) * 1000, 2)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8088")
    parser.add_argument("--report-directory", type=Path, default=REPO / "reports" / "public-benchmark")
    parser.add_argument("--rerank-only", action="store_true")
    args = parser.parse_args()
    values = configuration()
    if args.rerank_only:
        result = request_check("rerank", "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank",
                               {"model": "gte-rerank-v2", "input": {"query": "Where is Paris?", "documents": ["Paris is in France.", "Tokyo is in Japan."]},
                                "parameters": {"top_n": 2, "return_documents": False}}, values.get("DASHSCOPE_API_KEY", ""))
        args.report_directory.mkdir(parents=True, exist_ok=True)
        path = args.report_directory / datetime.now(timezone.utc).strftime("rerank-preflight-%Y%m%dT%H%M%S%fZ.json")
        path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"report": str(path), **result}, indent=2))
        return
    checks = [request_check("application", args.base_url.rstrip("/") + "/api/v1/knowledge-bases?page=0&size=1")]
    embedding_key = values.get("VECTOR_EMBEDDING_API_KEY") or values.get("DASHSCOPE_API_KEY", "")
    embedding_base = values.get("VECTOR_EMBEDDING_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
    embedding_model = values.get("VECTOR_EMBEDDING_MODEL", "text-embedding-v4")
    dimensions = int(values.get("VECTOR_EMBEDDING_DIMENSIONS", "1024"))
    checks.append(request_check("embedding", embedding_base.rstrip("/") + "/embeddings",
                               {"model": embedding_model, "input": ["公开评测集连通性检查"], "dimensions": dimensions,
                                "encoding_format": "float"}, embedding_key)
                  if embedding_key else {"name": "embedding", "status": "MISSING_CREDENTIAL"})
    chat_key = values.get("RAG_CHAT_API_KEY") or values.get("OPENAI_API_KEY", "")
    chat_base = values.get("RAG_CHAT_BASE_URL", "https://api.deepseek.com")
    chat_model = values.get("RAG_CHAT_MODEL", "deepseek-flash")
    checks.append(request_check("chat", chat_base.rstrip("/") + "/chat/completions",
                               {"model": chat_model, "messages": [{"role": "user", "content": "Reply with OK."}],
                                "max_tokens": 16, "temperature": 0, "thinking": {"type": "disabled"}}, chat_key)
                  if chat_key else {"name": "chat", "status": "MISSING_CREDENTIAL"})
    report = {"schemaVersion": "public-preflight-v1", "createdAt": datetime.now(timezone.utc).isoformat(),
              "status": "READY" if all(item["status"] == "AVAILABLE" for item in checks) else "BLOCKED_DEPENDENCY",
              "embeddingModel": embedding_model, "chatModel": chat_model, "checks": checks,
              "metricsStatus": "NOT_RUN", "scope": "One harmless embedding request and one short chat request using existing local configuration; no credentials recorded."}
    args.report_directory.mkdir(parents=True, exist_ok=True)
    path = args.report_directory / (datetime.now(timezone.utc).strftime("preflight-%Y%m%dT%H%M%S%fZ.json"))
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"report": str(path), **report}, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
