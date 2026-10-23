"""tools/check-epg-coverage.py matches channels to a guide the way the Live TV app does (id first, then name).

Run from the repository root: python3 -m unittest discover -s tests -v
"""
from pathlib import Path
import importlib.util
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("check_epg_coverage", ROOT / "tools" / "check-epg-coverage.py")


def load():
    argv, sys.argv = sys.argv, ["check-epg-coverage.py"]
    try:
        mod = importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(mod)
        return mod
    finally:
        sys.argv = argv


class CoverageTest(unittest.TestCase):
    def setUp(self):
        self.m = load()

    def test_id_keys_ignore_dots_case_feed_and_hd(self):
        k = self.m.id_key
        self.assertEqual(k("AajTak.in@HD"), k("Aaj.Tak.in"))
        self.assertEqual(k("&TV.HD.in"), k("&TV.in"))

    def test_name_keys_drop_height_and_brackets(self):
        self.assertEqual(self.m.name_key("Aaj Tak HD (1080p) [Geo-blocked]"), "naajtak")

    def test_a_channel_is_matched_by_id_then_by_name(self):
        mine = {
            self.m.channel_key("Aaj Tak", "AajTak.in@HD"): ("Aaj Tak", "AajTak.in@HD"),
            self.m.channel_key("Zee & Co", ""): ("Zee & Co", ""),
            self.m.channel_key("Nobody", "Nobody.in"): ("Nobody", "Nobody.in"),
        }
        guide = ([("Aaj.Tak.in", ["x"]), ("X1", ["Zee &amp; Co HD".replace("&amp;", "&")]), ("Idle", ["Nobody"])],
                 {"Aaj.Tak.in", "X1"})   # "Idle" has no programmes, so it does not count
        hit = self.m.covered(mine, guide)
        self.assertEqual(hit, {self.m.channel_key("Aaj Tak", "AajTak.in@HD"), self.m.channel_key("Zee & Co", "")})


if __name__ == "__main__":
    unittest.main()
