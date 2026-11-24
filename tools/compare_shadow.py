#!/usr/bin/env python3
"""Compare every document in the shadow index with the same id in the main index.

Usage: python3 compare_shadow.py [opensearch_url] [shadow_index] [main_index]
Exit 0 = all equal, 1 = differences found. Timing fields are ignored, lists compare as sets.
Set IGNORE_CONTENT=1 when the shadow runs with content indexing disabled.
"""
import json
import os
import sys
import urllib.request

URL, SHADOW, MAIN = (sys.argv[1:] + ["http://localhost:9200", "alfresco-live-shadow", "alfresco"][len(sys.argv) - 1:])[:3]
IGNORED = ("_INDEXING_LAST_UPDATE", "reindexingStartTime")
CONTENT = ("cm%3Acontent",)


def norm(v):
    return sorted(map(json.dumps, v)) if isinstance(v, list) else v


def get(path, body=None):
    req = urllib.request.Request(URL + path, data=json.dumps(body).encode() if body else None,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as r:
        return json.load(r)


def diff(a, b):
    keys = {k for k in set(a) | set(b) if not k.endswith(IGNORED)}
    if os.environ.get("IGNORE_CONTENT") == "1":
        keys = {k for k in keys if not k.startswith(CONTENT)}
    return sorted(k for k in keys if norm(a.get(k)) != norm(b.get(k)))


def main():
    hits = get(f"/{SHADOW}/_search", {"size": 10000, "query": {"match_all": {}}})["hits"]["hits"]
    docs = get(f"/{MAIN}/_mget", {"ids": [h["_id"] for h in hits]})["docs"] if hits else []
    main_by_id = {d["_id"]: d.get("_source") for d in docs if d.get("found")}
    bad = 0
    for h in hits:
        other = main_by_id.get(h["_id"])
        fields = ["<missing in main>"] if other is None else diff(h["_source"], other)
        if fields:
            bad += 1
            print(f"DIFF {h['_id']}: {fields[:15]}")
    print(f"compared={len(hits)} equal={len(hits) - bad} different={bad}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
