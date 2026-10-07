"""Freeze primary documentation for a new development-only fact Gold pack.

This copies original documentation, not rewritten learning cards. No test set
is generated or opened here. Raw downloads and canonical text stay local.
"""
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

import requests
from bs4 import BeautifulSoup


ROOT = Path(__file__).resolve().parents[3]
DESTINATION = ROOT / "data/local/fact-gold-v1-20261001"
SOURCES = [
    ("postgres17-isolation", "postgres-isolation", "17", "https://www.postgresql.org/docs/17/transaction-iso.html", "div.sect1"),
    ("postgres17-trigrams", "postgres-trigrams", "17", "https://www.postgresql.org/docs/17/pgtrgm.html", "div.sect1"),
    ("redis-xreadgroup", "redis-consumer-groups", "retrieved-2026-10-01", "https://redis.io/docs/latest/commands/xreadgroup/", "main"),
    ("spring-transaction-annotations", "spring-transactions", "retrieved-2026-10-01", "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html", "article.doc"),
]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    if DESTINATION.exists():
        raise ValueError("Frozen source pack already exists; inspect it, do not silently overwrite it")
    DESTINATION.mkdir(parents=True)
    documents = []
    for document_id, topic, version, url, selector in SOURCES:
        response = requests.get(url, timeout=(15, 45))
        response.raise_for_status()
        if len(response.content) > 3_000_000:
            raise ValueError(f"Unexpected source size: {document_id}")
        raw = DESTINATION / f"{document_id}.html"
        raw.write_bytes(response.content)
        soup = BeautifulSoup(response.content, "html.parser")
        element = soup.select_one(selector)
        if element is None:
            raise ValueError(f"Content selector missing: {document_id} {selector}; raw download retained")
        for removed in element.select("script, style, nav, footer, aside"):
            removed.decompose()
        text = element.get_text("\n", strip=True).replace("\r\n", "\n") + "\n"
        if len(text) < 500:
            raise ValueError(f"Unexpected content extraction: {document_id}")
        canonical = DESTINATION / f"{document_id}.txt"
        canonical.write_text(text, encoding="utf-8", newline="\n")
        documents.append({"documentId": document_id, "rawPath": raw.name,
                          "canonicalPath": canonical.name, "documentSha256": sha256(raw),
                          "sourceTextSha256": sha256(canonical), "sourceType": "public_primary_document",
                          "sourceUrl": url, "resolvedUrl": response.url, "sourceVersion": version,
                          "parserVersion": "bs4-html-main-v1:" + selector,
                          "topicGroup": topic, "split": "dev", "canonicalCharacters": len(text),
                          "retrievedAt": datetime.now(timezone.utc).isoformat()})
        print(json.dumps({"documentId": document_id, "canonicalCharacters": len(text)}, ensure_ascii=False))
    (DESTINATION / "sources.manifest.json").write_text(
        json.dumps({"kind": "new-primary-development-sources", "documents": documents,
                    "note": "Development only; source extraction and semantic fact review still require inspection."},
                   ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
