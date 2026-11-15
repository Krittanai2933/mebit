#!/usr/bin/env python3
"""Check the agent context: AGENTS.md, the CLAUDE.md stub, skills, briefs and the .agents/skills link.

  python3 -I scripts/check-agent-context.py [root]    check a checkout (default: this repo)
  python3 -I scripts/check-agent-context.py --selftest    run the built-in cases
"""
import glob
import os
import re
import sys
import tempfile

SLUG = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
KEY = re.compile(r"^([A-Za-z0-9_-]+):(.*)$")
CITE = re.compile(r"`([^`\s<>*]*\.claude/(?:skills/[^`/\s<>*]+/SKILL|agents/[^`/\s<>*]+)\.md)`")
SKIP = {".git", "target", "build", "node_modules"}  # build/ holds thousands of copies made by the verify trials


def read(path):
    with open(path, encoding="utf-8-sig") as f:
        return f.read()


def fail(root, path, problem, fix):
    return "FAIL %s: %s. Fix: %s" % (os.path.relpath(path, root), problem, fix)


def scan(root):
    """Return the skills, briefs and nested AGENTS.md files below root."""
    skills, briefs, nested = [], [], []
    for d, dirs, files in os.walk(root):
        # a directory with its own .git is another checkout (a clone or a Claude Code worktree)
        dirs[:] = sorted(x for x in dirs if x not in SKIP and not os.path.exists(os.path.join(d, x, ".git")))
        if "AGENTS.md" in files and d != root:
            nested.append(os.path.join(d, "AGENTS.md"))
        if os.path.basename(d) == ".claude":
            skills += glob.glob(os.path.join(d, "skills", "*", "SKILL.md"))
            briefs += glob.glob(os.path.join(d, "agents", "*.md"))
    return sorted(skills), sorted(briefs), sorted(nested)


def frontmatter(path):
    """Top-level `key: value` lines of the --- block, each as [first line, indented lines that follow]."""
    lines = read(path).split("\n")
    ends = [i for i, line in enumerate(lines[1:], 1) if line.rstrip() == "---"]
    if lines[0].rstrip() != "---" or not ends:
        return None
    fields, key = {}, None
    for line in lines[1:ends[0]]:
        m = KEY.match(line)
        if m:
            key = m.group(1)
            fields[key] = [m.group(2).strip()]
        elif key and line.startswith(" "):
            fields[key].append(line.strip())
    return fields


def value(parts):
    """The scalar's text. A > or | header is dropped and the indented lines are joined."""
    first = [] if parts[0][:1] in (">", "|") else parts[:1]
    return " ".join(first + parts[1:]).strip("\"' ")


def plain_problem(parts):
    """Why a strict YAML parser would reject this value, or None. Only quoted, block and flow values pass untested."""
    first = parts[0]
    if not first or first[0] in ">|" or (len(first) > 1 and first[0] + first[-1] in ('""', "''", "[]", "{}")):
        return None
    body = " ".join(parts)
    if not first[0].isalnum():
        return "starts with %r, which a plain YAML value cannot" % first[0]
    if ": " in body or body.endswith(":"):
        return "holds ': ', which strict YAML parsers reject"
    if " #" in body:
        return "holds ' #', which starts a YAML comment and cuts the value short"
    return None


def check_instructions(root):
    agents, claude = os.path.join(root, "AGENTS.md"), os.path.join(root, "CLAUDE.md")
    if os.path.islink(agents) or not os.path.isfile(agents) or os.path.getsize(agents) == 0:
        return [fail(root, agents, "missing, empty or a symlink", "make AGENTS.md a regular non-empty file")]
    out = []
    if any(line.startswith("@") for line in read(agents).split("\n")):
        out.append(fail(root, agents, "a line starts with @, which Codex and Kimi do not expand", "write the content or the path out"))
    stub = read(claude).split("\n") if os.path.isfile(claude) else []
    if next((line for line in stub if line.strip()), "") != "@AGENTS.md":
        out.append(fail(root, claude, "first line is not @AGENTS.md", "start CLAUDE.md with the line @AGENTS.md"))
    return out


def check_files(root, paths, kind):
    out = []
    for p in paths:
        fm = frontmatter(p)
        if fm is None:
            out.append(fail(root, p, "no frontmatter block", "start the file with a --- block that holds name and description"))
            continue
        skill = kind == "skill"
        stem = os.path.basename(os.path.dirname(p)) if skill else os.path.basename(p)[:-3]
        name, desc = value(fm.get("name", [""])), value(fm.get("description", [""]))
        if not (SLUG.match(name) and len(name) <= 64 and name == stem):
            out.append(fail(root, p, "name %r is not a lowercase slug equal to %r" % (name, stem), "make the name match the %s name" % ("folder" if skill else "file")))
        if not desc:
            out.append(fail(root, p, "description is empty", "say when to use it"))
        elif skill and len(desc) > 1024:
            out.append(fail(root, p, "description is over 1024 characters", "shorten it"))
        for key, parts in fm.items():
            why = plain_problem(parts)
            if why:
                out.append(fail(root, p, "the value of %r %s" % (key, why), "quote it, or write %s: >- and put the text on an indented line" % key))
    return out


def check_listed(root, paths):
    """Every skill and brief on disk is cited in AGENTS.md by exact path, and every cited path exists."""
    agents = os.path.join(root, "AGENTS.md")
    cited = set(CITE.findall(read(agents))) if os.path.isfile(agents) else set()
    on_disk = {os.path.relpath(p, root).replace(os.sep, "/") for p in paths}
    out = [fail(root, agents, "does not cite %s" % p, "add `%s` to the briefs table or the skills list" % p) for p in sorted(on_disk - cited)]
    return out + [fail(root, agents, "cites %s, which does not exist" % p, "fix or remove the path") for p in sorted(cited) if not os.path.isfile(os.path.join(root, p))]


def check_alias(root):
    link, target = os.path.join(root, ".agents", "skills"), os.path.join(root, ".claude", "skills")
    if os.path.isfile(link):  # git writes a symlink as a text file when core.symlinks is false
        return ["WARN .agents/skills: a text file, so this checkout has no symlink support. AGENTS.md still lists every skill"]
    if os.path.realpath(link) != os.path.realpath(target):
        return [fail(root, link, "does not resolve to .claude/skills", "run: mkdir -p .agents && ln -sfn ../.claude/skills .agents/skills (move a real folder out of the way first)")]
    return []


def run(root):
    skills, briefs, nested = scan(root)
    lines = check_instructions(root) + check_files(root, skills, "skill") + check_files(root, briefs, "brief")
    lines += check_listed(root, skills + briefs) + check_alias(root)
    lines += [fail(root, p, "nested AGENTS.md, which Claude Code ignores", "move its rules into the root AGENTS.md") for p in nested]
    return lines, len(skills), len(briefs)


GOOD = {
    "AGENTS.md": "# x\n| `.claude/agents/a.md` | module |\n- `.claude/skills/s/SKILL.md`\n",
    "CLAUDE.md": "@AGENTS.md\n",
    ".claude/agents/a.md": "---\nname: a\ndescription: Use for a.\n---\n",
    ".claude/skills/s/SKILL.md": "---\nname: s\ndescription: Use for s.\n---\n",
}
SKILL = ".claude/skills/s/SKILL.md"
CASES = [  # (change to the good tree, text one line must hold or None, whether it must fail)
    ({}, None, False),
    ({SKILL: "---\nname: t\ndescription: Use for s.\n---\n"}, "name 't'", True),
    ({SKILL: "---\nname: s\ndescription: Use for s: now.\n---\n"}, "holds ': '", True),
    ({SKILL: "---\nname: s\ndescription: Use for s:\n---\n"}, "holds ': '", True),
    ({SKILL: "---\nname: s\ndescription: `s` helps.\n---\n"}, "starts with '`'", True),
    ({SKILL: "---\nname: s\ndescription: >-\n  " + "x" * 1030 + "\n---\n"}, "over 1024", True),
    ({SKILL: "---\nname: s\ndescription: >-\n  Use for s: now.\n---\n"}, None, False),
    ({SKILL: "name: s\n"}, "no frontmatter", True),
    ({"AGENTS.md": "# x\n- `.claude/skills/s/SKILL.md`\n"}, "does not cite .claude/agents/a.md", True),
    ({"AGENTS.md": GOOD["AGENTS.md"] + "- `.claude/skills/gone/SKILL.md`\n"}, "does not exist", True),
    ({"AGENTS.md": GOOD["AGENTS.md"] + "@docs/x.md\n"}, "starts with @", True),
    ({"CLAUDE.md": "hello\n"}, "first line is not @AGENTS.md", True),
    ({"sub/AGENTS.md": "# nested\n"}, "nested AGENTS.md", True),
    ({".claude/worktrees/w/.git": "gitdir: x\n", ".claude/worktrees/w/.claude/skills/bad/SKILL.md": "name: bad\n"}, None, False),
    ({".agents/skills": None}, "does not resolve", True),
    ({".agents/skills": "../.claude/skills"}, "no symlink support", False),
]


def selftest():
    for change, want, fatal in CASES:
        with tempfile.TemporaryDirectory() as root:
            files = dict(GOOD)
            files.update(change)
            for rel, text in files.items():
                if text is not None:
                    os.makedirs(os.path.dirname(os.path.join(root, rel)), exist_ok=True)
                    with open(os.path.join(root, rel), "w", encoding="utf-8") as f:
                        f.write(text)
            if ".agents/skills" not in change:
                os.makedirs(os.path.join(root, ".agents"))
                try:
                    os.symlink("../.claude/skills", os.path.join(root, ".agents", "skills"))
                except OSError:
                    return print("selftest skipped: this machine cannot create symlinks")
            lines, _, _ = run(root)
            fails = [line for line in lines if line.startswith("FAIL")]
            assert (any(want in line for line in lines) if want else not lines) and bool(fails) == fatal, (change, want, lines)
    print("selftest OK, %d cases" % len(CASES))


def main(root):
    lines, skills, briefs = run(root)
    for line in lines:
        print(line, file=sys.stderr)
    if any(line.startswith("FAIL") for line in lines):
        sys.exit(1)
    print("OK %d skills, %d briefs" % (skills, briefs))


if __name__ == "__main__":
    if sys.argv[1:] == ["--selftest"]:
        selftest()
    else:
        main(os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
