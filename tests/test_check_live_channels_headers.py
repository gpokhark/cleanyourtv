"""tools/check-live-channels.py reads the request headers a playlist asks for, as the Live TV app does.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
from pathlib import Path
import importlib.util
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("check_live_channels", ROOT / "tools" / "check-live-channels.py")

PLAYLIST = """#EXTM3U
#EXTINF:-1 tvg-id="A.in@SD",A (1080p)
#EXTVLCOPT:http-referrer=https://ex.example/
#EXTVLCOPT:http-user-agent=Mozilla/5.0 X
https://a.example/a.m3u8
#EXTINF:-1,B (1080p)
https://b.example/b.m3u8|Referer=https%3A%2F%2Fr.example%2F&User-Agent=UA2
#EXTINF:-1,C (1080p)
https://c.example/c.m3u8?x=1|2
#EXTINF:-1,D (1080p)
#EXTVLCOPT:http-referrer=https://old.example/
#EXTINF:-1,E (1080p)
https://e.example/e.m3u8
"""


class HeadersTest(unittest.TestCase):
    def setUp(self):
        argv, sys.argv = sys.argv, ["check-live-channels.py"]
        try:
            self.mod = importlib.util.module_from_spec(SPEC)
            SPEC.loader.exec_module(self.mod)
        finally:
            sys.argv = argv
        self.entries = self.mod.parse_m3u(PLAYLIST)

    def test_vlc_options_become_headers(self):
        self.assertEqual(self.mod.HEADERS["https://a.example/a.m3u8"],
                         {"Referer": "https://ex.example/", "User-Agent": "Mozilla/5.0 X"})

    def test_a_pipe_suffix_is_cut_off_the_url_and_decoded(self):
        self.assertIn("https://b.example/b.m3u8", [link for _, link in self.entries])
        self.assertEqual(self.mod.HEADERS["https://b.example/b.m3u8"],
                         {"Referer": "https://r.example/", "User-Agent": "UA2"})

    def test_a_bar_that_names_no_header_stays_in_the_url(self):
        self.assertIn("https://c.example/c.m3u8?x=1|2", [link for _, link in self.entries])
        self.assertNotIn("https://c.example/c.m3u8?x=1|2", self.mod.HEADERS)

    def test_options_do_not_leak_to_the_next_channel(self):
        self.assertNotIn("https://e.example/e.m3u8", self.mod.HEADERS)


if __name__ == "__main__":
    unittest.main()
