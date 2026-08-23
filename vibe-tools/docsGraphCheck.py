#!/usr/bin/env python3
# VibeIDEA docs graph gate (port of the VibeIDE vibe-docs-graph contract, stdlib only):
#   1) dead links  — a relative md-link target does not exist;
#   2) unindexed   — a file under docs/vibe/knowledge/** has no line in knowledge/README.md;
#   3) unreachable — a doc is not reachable from docs/vibe/README.md via links.
# Wikilinks [[name]] are resolved against knowledge entry basenames. Links inside
# code blocks are ignored. _template* and specs/ are exempt. Exit 1 on any defect.
import re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs" / "vibe"
INDEX = DOCS / "knowledge" / "README.md"
ENTRY = DOCS / "README.md"
EXTRA_ROOTS = [ROOT / "CLAUDE.md", ROOT / "FORK_CHANGES.md"]

def md_files():
    return [p for p in DOCS.rglob("*.md")]

def strip_code(text: str) -> str:
    return re.sub(r"```.*?```", "", text, flags=re.S)

def links_of(path: Path):
    text = strip_code(path.read_text(encoding="utf-8"))
    out = []
    for m in re.finditer(r"\]\(([^)#\s]+?\.md)(?:#[^)]*)?\)", text):
        out.append(("rel", m.group(1)))
    for m in re.finditer(r"\[\[([A-Za-z0-9._-]+)(?:#[^\]|]*)?(?:\|[^\]]*)?\]\]", text):
        out.append(("wiki", m.group(1)))
    return out

def main() -> int:
    files = md_files()
    by_base = {}
    for f in files:
        by_base.setdefault(f.stem, []).append(f)
    problems = []

    # 1) dead links (+ resolve graph edges)
    edges = {f: set() for f in files + [p for p in EXTRA_ROOTS if p.exists()]}
    for f in list(edges):
        for kind, target in links_of(f):
            if kind == "rel":
                dest = (f.parent / target).resolve()
                if not dest.exists():
                    problems.append(f"DEAD LINK: {f.relative_to(ROOT)} -> {target}")
                elif dest.suffix == ".md":
                    edges[f].add(dest)
            else:
                candidates = by_base.get(target, [])
                if not candidates:
                    problems.append(f"DEAD WIKILINK: {f.relative_to(ROOT)} -> [[{target}]]")
                else:
                    edges[f].add(candidates[0].resolve())

    # 2) unindexed knowledge entries
    index_text = INDEX.read_text(encoding="utf-8") if INDEX.exists() else ""
    for f in (DOCS / "knowledge").rglob("*.md"):
        if f == INDEX or f.name.startswith("_template"):
            continue
        rel = f.relative_to(DOCS / "knowledge").as_posix()
        if rel not in index_text:
            problems.append(f"UNINDEXED: docs/vibe/knowledge/{rel} нет в knowledge/README.md")

    # 3) reachability from docs/vibe/README.md
    seen = set()
    stack = [ENTRY.resolve()]
    while stack:
        cur = stack.pop()
        if cur in seen:
            continue
        seen.add(cur)
        for dest in edges.get(Path(cur), edges.get(cur, set())):
            stack.append(Path(dest))
    for f in files:
        if f.name.startswith("_template") or "specs" in f.parts:
            continue
        if f.resolve() not in seen:
            problems.append(f"UNREACHABLE: {f.relative_to(ROOT)} недостижим от docs/vibe/README.md")

    for p in sorted(problems):
        print(p)
    print(f"docs-graph: files={len(files)} problems={len(problems)}")
    return 1 if problems else 0

if __name__ == "__main__":
    sys.exit(main())
