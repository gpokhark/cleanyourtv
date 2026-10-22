#!/usr/bin/env python3
"""Look for personal or device-specific data before it is committed.

This repo is public and its history is permanent, so the things that identify you or your TV must stay out of it: IP
addresses, Wi-Fi names, MAC addresses, device serials and ids, email addresses, API keys and private keys, your
coordinates, your home folder name, and the per-TV folders (my-tv/, backup/, secrets/). Keep those in a git-ignored
folder such as my-tv/ instead.

Usage:
  tools/check-privacy.py                  scan the staged changes (what the next commit would add)
  tools/check-privacy.py --all            scan every tracked file
  tools/check-privacy.py --install-hook   run the staged scan automatically before every commit in this clone
  tools/check-privacy.py --path FILE...   scan files that are not in git yet

Exit status 1 when something was found. A line that is fine on purpose (a documentation example) can end with
"privacy-ok" in a comment. It only reads files and git output; nothing is sent anywhere. Needs Python 3.
"""
import argparse
import ipaddress
import os
import re
import stat
import subprocess
import sys

# Addresses that are fine to see in public: documentation ranges, the examples this repo's docs use, public DNS resolvers.
DOC_NETS = [ipaddress.ip_network(n) for n in ("192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24", "192.168.1.0/24")]
PUBLIC_OK = {"1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9", "149.112.112.112", "94.140.14.14", "94.140.15.15"}
EMAIL_OK = re.compile(r"(@users\.noreply\.github\.com|^noreply@|@example\.(com|org|net)$|^git@github\.com$)", re.I)
COORD_OK = {"51.5", "-0.1", "0.0", "40.7", "-74.0"}   # the documentation's example places
PRIVATE_PATHS = re.compile(r"^(my-tv|my-firetv|secrets|backup)/|(^|/)adbkey(\.pub)?$|\.(key|pem|p12|jks|keystore)$")

IPV4 = re.compile(r"(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.]*\d)")
MAC = re.compile(r"(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f:])")
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
HOME_PATH = re.compile(r"(?:/Users/|/home/|C:\\Users\\)(?!Shared\b|you\b|user\b|name\b|me\b|\$|<|\.\.\.)[A-Za-z0-9._-]{2,}")
PRIVATE_KEY = re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")
ADB_KEY_BLOB = re.compile(r"(?<![A-Za-z0-9+/])QAAAA[A-Za-z0-9+/]{690,}")   # a standalone key, not a stretch inside base64 data
SECRET = re.compile(r"(?i)\b(api[_-]?key|secret|token|passwd|password|authorization)\b[\"'\s]*[:=][\"'\s]*([A-Za-z0-9_\-+/]{20,})")
DEVICE_ID = re.compile(r"(?i)\b(android_id|serialno|serial[_ ]?number|imei|ro\.serialno)\b[\"'\s]*[:=][\"'\s]*([A-Za-z0-9]{6,})")
SSID = re.compile(r"(?i)\b(ssid|wifi[_ ]?name)\b[\"']?\s*[:=]\s*[\"']([^\"']{3,})[\"']")   # only a quoted literal: code that assigns a variable is fine
VERSION_BEFORE = re.compile(r"(?i)(fire\s?os|version|firmware|build|release|\bv|\bos)\s*$")
COORD = re.compile(r"(?i)\b(?:sky_)?(latitude|longitude|lat|lon|lng)\b[\"'\s]*[:=][\"'\s]*(-?\d{1,3}\.\d+)")


def findings_in(line):
    """Yield (kind, text) for each personal-looking thing on one line."""
    if "privacy-ok" in line:
        return
    for m in IPV4.finditer(line):
        try:
            ip = ipaddress.ip_address(m.group(0))
        except ValueError:
            continue
        if ip.is_loopback or ip.is_unspecified or ip.is_multicast or ip.is_link_local or ip.is_reserved:
            continue
        if VERSION_BEFORE.search(line[:m.start()]):   # "Fire OS 6.7.1.1" is a version, not an address
            continue
        if any(ip in net for net in DOC_NETS) or str(ip) in PUBLIC_OK:
            continue
        yield ("private IP address" if ip.is_private else "public IP address", m.group(0))
    for m in MAC.finditer(line):
        if m.group(0).replace(":", "").replace("-", "").lower() not in ("000000000000", "ffffffffffff"):
            yield ("MAC address", m.group(0))
    for m in EMAIL.finditer(line):
        if not EMAIL_OK.search(m.group(0)):
            yield ("email address", m.group(0))
    for m in HOME_PATH.finditer(line):
        yield ("home folder path (names you)", m.group(0))
    if PRIVATE_KEY.search(line):
        yield ("private key", "-----BEGIN ... PRIVATE KEY-----")
    for m in ADB_KEY_BLOB.finditer(line):
        yield ("adb public key", m.group(0)[:16] + "...")
    for m in SECRET.finditer(line):
        yield ("secret or API key", m.group(1) + " = " + m.group(2)[:4] + "...")
    for m in DEVICE_ID.finditer(line):
        yield ("device id or serial", m.group(1) + " = " + m.group(2)[:4] + "...")
    for m in SSID.finditer(line):
        if m.group(2).lower() not in ("name", "of", "your", "the", "yournetwork", "network", "<name>"):
            yield ("Wi-Fi name", m.group(0)[:40])
    for m in COORD.finditer(line):
        if m.group(2) not in COORD_OK:
            yield ("coordinates", m.group(0)[:40])


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout


def staged_lines():
    """(path, line number, text) of every line the staged changes add."""
    out = git("diff", "--cached", "-U0", "--no-color", "--diff-filter=ACMR")
    path, number = None, 0
    for raw in out.splitlines():
        if raw.startswith("+++ b/"):
            path = raw[6:]
        elif raw.startswith("+++ "):
            path = None
        elif raw.startswith("@@"):
            m = re.search(r"\+(\d+)", raw)
            number = int(m.group(1)) if m else 0
        elif raw.startswith("+") and path:
            yield path, number, raw[1:]
            number += 1


def file_lines(path):
    try:
        if os.path.getsize(path) > 2_000_000:
            return
        with open(path, encoding="utf8") as f:
            for number, line in enumerate(f, 1):
                yield path, number, line.rstrip("\n")
    except (UnicodeDecodeError, OSError):
        return


def install_hook():
    hook = git("rev-parse", "--git-path", "hooks/pre-commit").strip()
    script = os.path.abspath(__file__)
    if os.path.exists(hook):
        text = open(hook).read()
        if "check-privacy" in text:
            print(f"already installed: {hook}")
            return 0
        sys.exit(f"{hook} already exists; add this line to it yourself:\n  python3 '{script}' --staged")
    with open(hook, "w") as f:
        f.write(f"#!/bin/sh\n# installed by tools/check-privacy.py --install-hook\nexec python3 '{script}' --staged\n")
    os.chmod(hook, os.stat(hook).st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    print(f"installed: {hook}\nIt scans each commit's changes. Skip it for one commit with: git commit --no-verify")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--staged", action="store_true", help="scan the staged changes (the default)")
    ap.add_argument("--all", action="store_true", help="scan every tracked file")
    ap.add_argument("--install-hook", action="store_true", help="run the staged scan before every commit")
    ap.add_argument("--path", nargs="+", metavar="FILE", help="scan these files")
    a = ap.parse_args()
    if a.install_hook:
        return install_hook()

    found = []
    if a.path:
        sources = (item for p in a.path for item in file_lines(p))
        names = a.path
    elif a.all:
        names = git("ls-files").splitlines()
        sources = (item for p in names for item in file_lines(p))
    else:
        names = git("diff", "--cached", "--name-only", "--diff-filter=ACMR").splitlines()
        sources = staged_lines()
    for p in names:
        if PRIVATE_PATHS.search(p):
            found.append((p, 0, "private file", "this path holds data about your TV or your keys: keep it out of git"))
    for path, number, text in sources:
        for kind, what in findings_in(text):
            found.append((path, number, kind, what))

    email = subprocess.run(["git", "config", "user.email"], capture_output=True, text=True).stdout.strip()
    if email and not email.endswith("@users.noreply.github.com") and not a.path:
        print(f"note: your commit email is {email}. Commits carry it publicly; GitHub's private address is "
              f"<id>+<username>@users.noreply.github.com (git config user.email ...).", file=sys.stderr)

    for path, number, kind, what in found:
        print(f"{path}:{number}: {kind}: {what}")
    if found:
        print(f"\n{len(found)} possible personal detail(s). Remove them or keep them in my-tv/. If one is a deliberate "
              f"example, end that line with a 'privacy-ok' comment.", file=sys.stderr)
        return 1
    print("nothing personal found")
    return 0


if __name__ == "__main__":
    sys.exit(main())
