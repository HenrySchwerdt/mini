"""Focused command-construction tests for the benchmark runner."""
import json
import os
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock

import run


class MiniRunnerTests(unittest.TestCase):
    def test_backend_ids_map_to_current_cli_backends(self):
        self.assertEqual(run.mini_build_options("mini-managed"), ("c", ["-O2", "--backend", "c"]))
        self.assertEqual(run.mini_build_options("mini-managed-llvm"), ("llvm-api", ["-O2", "--backend", "llvm-api"]))
        self.assertEqual(run.mini_build_options("mini-arena-llvm"), ("llvm-api", ["-O2", "--backend", "llvm-api"]))

    def test_minic_environment_override_is_used(self):
        with mock.patch.dict(os.environ, {"MINIC": "/tmp/custom-minic"}):
            self.assertEqual(run.Tools().minic, "/tmp/custom-minic")

    def test_build_uses_supported_flags_and_records_metadata(self):
        tools = SimpleNamespace(minic="/custom/minic")
        with tempfile.TemporaryDirectory() as outdir, mock.patch.object(run, "sh", return_value=(0, "")) as sh:
            result = run.build(tools, "nbody", "mini-managed-llvm", "/bench/nbody.kt", outdir)

            command = sh.call_args.args[0]
            self.assertEqual(
                command,
                ["/custom/minic", "build", "/bench/nbody.kt", "-o", os.path.join(outdir, "nbody"), "-O2", "--backend", "llvm-api"],
            )
            self.assertNotIn("--cflag", command)
            self.assertNotIn("--stats", command)
            self.assertIsNone(result[2])
            with open(os.path.join(outdir, "nbody.bench.json")) as f:
                metadata = json.load(f)
            self.assertEqual(metadata["backend"], "llvm-api")
            self.assertEqual(metadata["compiler_flags"], ["-O2", "--backend", "llvm-api"])
            self.assertEqual(metadata["compiler"], "minic")
            self.assertEqual(metadata["compiler_source"], "repository wrapper")

    def test_requested_stats_are_explicitly_unavailable(self):
        stats = run.mini_stats_unavailable()
        self.assertEqual(stats["status"], "unavailable")
        self.assertIn("MK_STATS", stats["reason"])
        self.assertIn("unavailable", run.markdown({"host": {"cpu": "test", "cores": 1, "os": "test"}, "date": "2026-01-01", "reps": 1, "results": [
            {"bench": "nbody", "lang": "mini-managed", "status": "ok", "stats": stats, "time_best_s": 1.0,
             "rss_kb": 10, "size_bytes": 20, "time_best_s": 1.0}
        ], "startup": {}}))


if __name__ == "__main__":
    unittest.main()
