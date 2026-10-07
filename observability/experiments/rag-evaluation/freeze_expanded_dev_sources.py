"""Freeze four additional official dev sources and reuse the exact old four.

This prepares development material only. It never reads retrieval results or
test material, and refuses to overwrite an existing source snapshot.
"""
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path

import requests
from bs4 import BeautifulSoup

from fact_gold import digest, load_gold

ROOT = Path(__file__).resolve().parents[3]
OLD = ROOT / "data/local/fact-gold-v1-20261001-r2/manifest.json"
PACK = ROOT / "data/local/fact-gold-expanded-dev-20261002"
SOURCES = [
    ("redis-xautoclaim", "redis-consumer-groups", "retrieved-2026-10-02",
     "https://redis.io/docs/latest/commands/xautoclaim/", "main"),
    ("spring-transaction-propagation", "spring-transactions", "retrieved-2026-10-02",
     "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html", "article.doc"),
    ("java21-completable-future", "java-completion-stages", "21",
     "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html", "main"),
    ("java21-executor-service", "java-executor-services", "21",
     "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html", "main"),
]


def main():
    old = load_gold(OLD, "agent_verified")
    PACK.mkdir(exist_ok=False)
    documents = []
    for doc in old.documents.values():
        for key in ("rawPath", "canonicalPath"):
            shutil.copyfile(OLD.parent / doc[key], PACK / doc[key])
        documents.append({**doc, "reuseManifestSha256": digest(OLD)})
    manifest = {"kind": "expanded-official-development-sources", "documents": documents,
                "previousGoldSha256": digest(OLD), "testMaterialsPrepared": False,
                "reviewStatus": "source-extraction-only-no-Gold-or-scores"}
    for doc_id, topic, version, url, selector in SOURCES:
        response = requests.get(url, timeout=(15, 45))
        response.raise_for_status()
        if len(response.content) > 3_000_000:
            raise ValueError("Unexpected public document size")
        raw = PACK / f"{doc_id}.html"
        raw.write_bytes(response.content)
        soup = BeautifulSoup(response.content, "html.parser")
        content = soup.select_one(selector)
        if content is None:
            raise ValueError(f"Missing source selector: {doc_id}; retain raw response")
        for removed in content.select("script, style, nav, footer, aside"):
            removed.decompose()
        text = content.get_text("\n", strip=True).replace("\r\n", "\n") + "\n"
        if len(text) < 1000:
            raise ValueError(f"Suspicious source content: {doc_id}")
        canonical = PACK / f"{doc_id}.txt"
        canonical.write_text(text, encoding="utf-8", newline="\n")
        documents.append({"documentId": doc_id, "rawPath": raw.name,
                          "canonicalPath": canonical.name, "documentSha256": digest(raw),
                          "sourceTextSha256": digest(canonical), "sourceType": "public_primary_document",
                          "sourceUrl": url, "resolvedUrl": response.url, "sourceVersion": version,
                          "parserVersion": "bs4-html-main-v1:" + selector, "topicGroup": topic,
                          "split": "dev", "canonicalCharacters": len(text),
                          "retrievedAt": datetime.now(timezone.utc).isoformat()})
        (PACK / "sources.manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"documentId": doc_id, "characters": len(text)}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
