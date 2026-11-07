#!/usr/bin/env python3
"""Compiles hosts-format and ||host^ filter lists into the compact binary set the browser ships.

Usage: tools/compile_hosts.py [--out app/src/main/assets/hosts.bin] [source ...]
Sources are local files or https URLs. With no sources, the unified StevenBlack hosts file
(MIT) is fetched. The bundled starter list (app/src/main/assets/blocklist.txt) is always merged.

Format (big-endian): "BLZH", u8 version (1), u32 count, then count sorted signed int64 FNV-1a
hashes of each host, hashed over UTF-16 code units so the Kotlin side hashes String.code
identically. Hosts already covered by a parent host in the set are dropped: the lookup matches
any suffix on a label boundary, so "ads.doubleclick.net" is redundant next to "doubleclick.net".
"""
import argparse
import re
import struct
import sys
import urllib.request

DEFAULT_SOURCE = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
STARTER = "app/src/main/assets/blocklist.txt"
FNV_OFFSET = 0xCBF29CE484222325
FNV_PRIME = 0x100000001B3
MASK = (1 << 64) - 1
IPV4 = re.compile(r"^\d+\.\d+\.\d+\.\d+$")
# Every cosmetic separator: ##, #@#, #?#, #$#, #%# and their combinations. A line carrying one
# names a SITE to hide something on, never a host to block; it must not reach the host set.
COSMETIC = re.compile(r"#[@?$%]*#")
# A '#' starts a hosts-file comment only at the start of a line or after whitespace.
COMMENT = re.compile(r"(?:^|\s)#")
LABEL = re.compile(r"^[a-z0-9_-]+$")
SKIP = {"localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost",
        "ip6-loopback", "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes", "ip6-allrouters",
        "ip6-allhosts", "0.0.0.0"}


def fnv1a(host: str) -> int:
  h = FNV_OFFSET
  for ch in host:
    h = ((h ^ ord(ch)) * FNV_PRIME) & MASK
  return h - (1 << 64) if h >= (1 << 63) else h


def parse_line(raw: str):
  if COSMETIC.search(raw):
    return None
  line = COMMENT.split(raw, maxsplit=1)[0].strip()
  if not line or line.startswith("!") or line.startswith("@@"):
    return None
  if line.startswith("||"):
    body = line[2:]
    end = 0
    while end < len(body) and (body[end].isalnum() or body[end] in ".-_"):
      end += 1
    line = body[:end]
    rest = body[end:]
    if rest.startswith("^"):
      rest = rest[1:]
    # Only a bare host, or one limited to third-party requests, fits a host set: the set is
    # consulted for third-party requests alone, so both mean the same thing here.
    if rest not in ("", "$third-party", "$3p"):
      return None
  else:
    parts = line.split()
    # A hosts file line is "0.0.0.0 host"; a bare domain line is one field. Anything with
    # more fields, or a filter-list line with options or wildcards, is not a host entry.
    if len(parts) > 2 or (len(parts) == 2 and parts[0] not in ("0.0.0.0", "127.0.0.1", "::1")):
      return None
    line = parts[-1]
    if "$" in line or "^" in line or "|" in line:
      return None
  host = line.lower().rstrip(".")
  if host in SKIP or "." not in host or "/" in host or "*" in host or IPV4.match(host):
    return None
  labels = host.split(".")
  if any(not LABEL.match(label) for label in labels) or labels[-1].isdigit():
    return None
  return host


def fetch(source: str):
  if source.startswith("https://"):
    # easylist.to answers the default urllib agent with 403; name the tool instead.
    request = urllib.request.Request(source, headers={"User-Agent": "wren-filter-compiler/1.0"})
    with urllib.request.urlopen(request, timeout=60) as response:
      return response.read().decode("utf-8", "replace").splitlines()
  with open(source, encoding="utf-8", errors="replace") as handle:
    return handle.read().splitlines()


def read_source(source: str):
  """One list plus whatever it `!#include`s, one level deep, from the same place."""
  lines = fetch(source)
  includes = [l[len("!#include "):].strip() for l in lines if l.startswith("!#include ")]
  for name in includes[:12]:
    if not name or " " in name or "/" in name or name.startswith("."):
      continue
    base = source.rsplit("/", 1)[0] + "/" if "/" in source else ""
    try:
      lines.extend(fetch(base + name))
    except Exception as error:  # noqa: BLE001
      print(f"include {name} of {source} failed: {error}", file=sys.stderr)
  return lines


def main() -> int:
  parser = argparse.ArgumentParser()
  parser.add_argument("--out", default="app/src/main/assets/hosts.bin")
  parser.add_argument("sources", nargs="*")
  args = parser.parse_args()
  sources = args.sources or [DEFAULT_SOURCE]

  hosts = set()
  for source in sources + [STARTER]:
    before = len(hosts)
    for raw in read_source(source):
      host = parse_line(raw)
      if host:
        hosts.add(host)
    print(f"{source}: +{len(hosts) - before} hosts", file=sys.stderr)

  # Drop anything a shorter entry already covers.
  kept = set()
  for host in sorted(hosts, key=lambda h: h.count(".")):
    labels = host.split(".")
    covered = any(".".join(labels[i:]) in kept for i in range(1, len(labels) - 1))
    if not covered:
      kept.add(host)
  print(f"{len(hosts)} hosts, {len(kept)} after suffix dedupe", file=sys.stderr)

  hashes = sorted({fnv1a(host) for host in kept})
  with open(args.out, "wb") as out:
    out.write(b"BLZH")
    out.write(struct.pack(">BI", 1, len(hashes)))
    out.write(struct.pack(f">{len(hashes)}q", *hashes))
  print(f"wrote {args.out}: {len(hashes)} entries, {8 * len(hashes) + 9} bytes", file=sys.stderr)
  return 0


if __name__ == "__main__":
  sys.exit(main())
