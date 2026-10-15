#!/usr/bin/env python3
"""Query a `uiautomator dump` file. verify.sh calls this; SKILL.md documents the selectors.

  ui.py find  DUMP SELECTOR [N]  print "x y", the centre of the Nth match (default 1st)
  ui.py count DUMP SELECTOR      print how many nodes match
  ui.py list  DUMP               print every node that has text, a description or an id
  ui.py crash BUFFER PKG         read a `logcat -b crash` capture. Print "app" when PKG is in it, otherwise the
                                 names of the processes that crashed (Java "Process:" and native ">>> x <<<" forms)

SELECTOR is [KIND[~]:]VALUE. KIND is text (the default), desc or id. A "~" after
the kind means "contains" instead of "equals".
"""
import re
import sys
import xml.etree.ElementTree as ET

ATTR = {"text": "text", "desc": "content-desc", "id": "resource-id"}
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def parse_selector(sel):
    m = re.match(r"^(text|desc|id)(~?):(.*)$", sel, re.S)
    return (m.group(1), m.group(2) == "~", m.group(3)) if m else ("text", False, sel)


def visible_nodes(path):
    for node in ET.parse(path).iter("node"):
        m = BOUNDS.match(node.get("bounds", ""))
        if m and int(m.group(3)) > int(m.group(1)) and int(m.group(4)) > int(m.group(2)):
            yield node, tuple(int(g) for g in m.groups())


def matches(node, kind, contains, value):
    got = node.get(ATTR[kind], "")
    if not got:
        return False
    if contains:
        return value in got
    # a Compose testTag shows up bare; a classic view id looks like pkg:id/name
    return got == value or (kind == "id" and got.endswith("/" + value))


def describe(node, box):
    parts = [f'{k}="{node.get(a)}"' for k, a in ATTR.items() if node.get(a)]
    return " ".join(parts) + f" bounds=[{box[0]},{box[1]}][{box[2]},{box[3]}]" + (
        " clickable" if node.get("clickable") == "true" else "")


def crash(path, pkg):
    text = open(path, errors="replace").read()
    if pkg in text:
        print("app")
        return 0
    names = sorted(set(re.findall(r"Process: ([^,\s]+),", text) + re.findall(r">>> (\S+) <<<", text)))
    print(" ".join(names))
    return 0


def main(argv):
    if len(argv) == 4 and argv[1] == "crash":
        return crash(argv[2], argv[3])
    if len(argv) < 3 or argv[1] not in ("find", "count", "list"):
        sys.exit(__doc__)
    cmd, dump = argv[1], argv[2]
    nodes = list(visible_nodes(dump))
    if cmd == "list":
        for node, box in nodes:
            if any(node.get(a) for a in ATTR.values()):
                print(describe(node, box))
        return 0
    if len(argv) < 4:
        sys.exit(__doc__)
    kind, contains, value = parse_selector(argv[3])
    hits = [(n, b) for n, b in nodes if matches(n, kind, contains, value)]
    if cmd == "count":
        print(len(hits))
        return 0
    nth = int(argv[4]) if len(argv) > 4 and argv[4] else 0
    why = None
    if not hits:
        why = "no node matches"
    elif nth > len(hits):
        why = f"only {len(hits)} node(s) match, so index {nth} is out of range for"
    elif not nth and len(hits) > 1:
        why = f"{len(hits)} nodes match (pass the 1-based index) for"
    if why:
        print(f"ui.py: {why} {argv[3]!r}. On screen:", file=sys.stderr)
        for n, b in (hits or nodes):
            if any(n.get(a) for a in ATTR.values()):
                print("  " + describe(n, b), file=sys.stderr)
        return 1
    _, (left, top, right, bottom) = hits[(nth or 1) - 1]
    print((left + right) // 2, (top + bottom) // 2)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
