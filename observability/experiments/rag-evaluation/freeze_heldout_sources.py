"""Freeze new test source documents only after the candidate strategy is frozen.

Source/theme separation is checked against expanded dev. This is still authored
by the same Agent and is not an independent reviewer or random real-user sample.
"""
import json
from datetime import datetime, timezone
from pathlib import Path

import requests
from bs4 import BeautifulSoup

from fact_gold import digest, load_gold

ROOT = Path(__file__).resolve().parents[3]
PACK = ROOT / "data/local/fact-gold-heldout-20261002"
FROZEN = ROOT / "observability/experiments/rag-evaluation/runs/expanded-dev-20261002-r1/frozen-focused-r1"
SOURCES = [
    ("python313-queue", "python-thread-queues", "3.13", "https://docs.python.org/3.13/library/queue.html", "div.body"),
    ("python313-contextvars", "python-context-local-state", "3.13", "https://docs.python.org/3.13/library/contextvars.html", "div.body"),
    ("java21-http-client", "java-http-client-networking", "21", "https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html", "main"),
    ("java21-files", "java-nio-file-operations", "21", "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html", "main"),
]


def verify_strategy():
    hashes = json.loads((FROZEN / "manifest.json").read_text(encoding="utf-8"))
    for path, item in hashes.items():
        if digest(ROOT / path) != item["sha256"] or digest(FROZEN / item["file"]) != item["sha256"]:
            raise ValueError("Candidate source changed after pre-test strategy freeze")
    return digest(FROZEN / "strategy-freeze.json")


def main():
    strategy_sha = verify_strategy()
    dev = load_gold(ROOT / "data/local/fact-gold-expanded-dev-20261002/manifest.json", "agent_verified")
    PACK.mkdir(exist_ok=False)
    documents = []
    for doc_id, topic, version, url, selector in SOURCES:
        if topic in {doc["topicGroup"] for doc in dev.documents.values()} or url in {doc["sourceUrl"] for doc in dev.documents.values()}:
            raise ValueError("Source/theme overlaps dev")
        response = requests.get(url, timeout=(15, 45))
        response.raise_for_status()
        if len(response.content) > 3_000_000:
            raise ValueError("Unexpected public source size")
        raw = PACK / (doc_id + ".html")
        raw.write_bytes(response.content)
        soup = BeautifulSoup(response.content, "html.parser")
        body = soup.select_one(selector)
        if body is None:
            raise ValueError("Missing primary content selector; keep raw response")
        for node in body.select("script,style,nav,footer,aside"):
            node.decompose()
        text = body.get_text("\n", strip=True).replace("\r\n", "\n") + "\n"
        if len(text) < 1000:
            raise ValueError("Suspicious source extraction")
        path = PACK / (doc_id + ".txt")
        path.write_text(text, encoding="utf-8", newline="\n")
        if digest(path) in {doc["sourceTextSha256"] for doc in dev.documents.values()}:
            raise ValueError("Canonical source bytes overlap dev")
        documents.append({"documentId": doc_id, "rawPath": raw.name, "canonicalPath": path.name,
                          "documentSha256": digest(raw), "sourceTextSha256": digest(path),
                          "sourceType": "public_primary_document", "sourceUrl": url, "resolvedUrl": response.url,
                          "sourceVersion": version, "parserVersion": "bs4-html-main-v1:" + selector,
                          "topicGroup": topic, "split": "test", "canonicalCharacters": len(text),
                          "retrievedAt": datetime.now(timezone.utc).isoformat()})
        (PACK / "sources.manifest.json").write_text(json.dumps({"documents": documents,
            "strategyFreezeSha256": strategy_sha, "devManifestSha256": digest(dev.manifest_path),
            "sourceThemeSeparated": True, "independentReviewer": False}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"documentId": doc_id, "characters": len(text)}), flush=True)


if __name__ == "__main__":
    main()
