#!/usr/bin/env python3
"""Tests that patch_bindings.py patches exactly once and fails closed. Run by ffi.sh in preflight.

Usage: test_patch_bindings.py
"""
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True  # importing the script must not leave a __pycache__ in scripts/
HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "patch_bindings.py")
spec = importlib.util.spec_from_file_location("patch_bindings", SCRIPT)
assert spec is not None and spec.loader is not None
patch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patch)

GENERATED = "// generated\n%s\n// more generated\n"


class PatchBindings(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def file(self, name, text):
        path = os.path.join(self.dir.name, name)
        with open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(text)
        return path

    def read(self, path):
        with open(path, encoding="utf-8", newline="") as handle:
            return handle.read()

    def run_patch(self, *paths):
        return subprocess.run([sys.executable, "-I", SCRIPT, *paths], capture_output=True, text=True)

    def test_patches_each_file_once(self):
        a = self.file("a.kt", GENERATED % patch.OLD)
        b = self.file("b.kt", GENERATED % patch.OLD)
        done = self.run_patch(a, b)
        self.assertEqual(done.returncode, 0, done.stderr)
        for path in (a, b):
            text = self.read(path)
            self.assertEqual(text, GENERATED % patch.NEW)
            self.assertEqual(text.count("freeFunc(rustFuture)"), 1, "exactly one free in the function")

    def test_refuses_text_that_is_already_patched(self):
        a = self.file("a.kt", GENERATED % patch.NEW)
        done = self.run_patch(a)
        self.assertEqual(done.returncode, 1)
        self.assertEqual(self.read(a), GENERATED % patch.NEW)

    def test_refuses_text_that_changed(self):
        changed = patch.OLD.replace("uniffiRustCallWithError(errorHandler)", "uniffiRustCallWithErrorV2(errorHandler)")
        a = self.file("a.kt", GENERATED % changed)
        done = self.run_patch(a)
        self.assertEqual(done.returncode, 1)
        self.assertEqual(self.read(a), GENERATED % changed)

    def test_refuses_a_function_that_appears_twice(self):
        a = self.file("a.kt", GENERATED % (patch.OLD + "\n" + patch.OLD))
        done = self.run_patch(a)
        self.assertEqual(done.returncode, 1)

    def test_writes_neither_file_when_one_is_refused(self):
        good = self.file("good.kt", GENERATED % patch.OLD)
        bad = self.file("bad.kt", GENERATED % "// no function here")
        done = self.run_patch(good, bad)
        self.assertEqual(done.returncode, 1)
        self.assertEqual(self.read(good), GENERATED % patch.OLD)

    def test_needs_a_file(self):
        self.assertEqual(self.run_patch().returncode, 1)


if __name__ == "__main__":
    unittest.main()
