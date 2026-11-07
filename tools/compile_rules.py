#!/usr/bin/env python3
"""Compiles EasyList-style filter lists into the bundled pattern rule set.

Writes two assets. rules.txt keeps the network rules the app's RuleEngine understands and that
the compact host set cannot express (paths, wildcards, resource types, domain scopes,
exceptions). cosmetic.txt keeps element-hiding rules and the scriptlet calls the bundled
scriptlet library implements. Plain host rules belong in hosts.bin: run tools/compile_hosts.py
with the same sources.

Usage: python3 tools/compile_rules.py [source ...]
Sources are URLs or files; the defaults are EasyList, EasyPrivacy and the uBlock lists.
"""
import re
import sys
import urllib.request
from pathlib import Path

DEFAULT_SOURCES = [
  "https://easylist.to/easylist/easylist.txt",
  "https://easylist.to/easylist/easyprivacy.txt",
  "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
  "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/quick-fixes.txt",
  "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt",
  "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt",
]
ASSETS = Path(__file__).resolve().parent.parent / "app/src/main/assets"
OUTPUT = ASSETS / "rules.txt"
COSMETIC_OUTPUT = ASSETS / "cosmetic.txt"

# Must match the names handled by assets/scriptlets.js and CosmeticRules.kt.
SCRIPTLETS = {
  "set-constant", "set", "abort-on-property-read", "aopr", "abort-on-property-write", "aopw",
  "abort-current-script", "acs", "abort-current-inline-script", "acis", "json-prune",
  "json-prune-fetch-response", "json-prune-xhr-response", "trusted-replace-fetch-response",
  "trusted-replace-xhr-response", "no-setTimeout-if", "nostif", "setTimeout-defuser",
  "no-setInterval-if", "nosiif", "nano-setTimeout-booster", "nano-stb",
  "nano-setInterval-booster", "nano-sib", "addEventListener-defuser", "aeld",
  "no-window-open-if", "nowoif", "window.open-defuser", "no-fetch-if", "prevent-fetch",
  "no-xhr-if", "prevent-xhr", "remove-attr", "ra", "remove-class", "rc", "remove-node-text",
  "rmnt", "replace-node-text", "rpnt", "noeval", "noeval-if", "nowebrtc", "nobab", "nofab",
  "popads-dummy", "set-cookie", "set-local-storage-item", "set-session-storage-item",
  "remove-cookie", "cookie-remover", "href-sanitizer", "no-cookie-if",
  "trusted-rpfr", "trusted-rpnt", "trusted-replace-node-text", "trusted-set",
  "trusted-set-constant", "trusted-set-cookie", "trusted-set-local-storage-item",
  "trusted-set-session-storage-item", "trusted-prevent-xhr", "trusted-prevent-fetch",
  "prevent-setTimeout", "prevent-setInterval", "prevent-addEventListener",
  "prevent-window-open", "prevent-eval-if", "adjust-setTimeout", "adjust-setInterval",
  "no-requestAnimationFrame-if", "norafif", "set-attr", "refresh-defuser", "prevent-refresh",
  "disable-newtab-links", "set-cookie-reload",
}
# Conditions in `!#if` blocks that hold for Wren (a Chromium-based mobile uBlock, in list terms).
TRUE_CONDITIONS = {"env_chromium", "env_mobile", "ext_ublock", "true"}
MAX_INCLUDES = 12
PROCEDURAL = re.compile(
  r":(has-text|matches-css|matches-css-before|matches-css-after|xpath|upward|remove|"
  r"min-text-length|matches-path|watch-attr|matches-attr|matches-prop|others|if|if-not|"
  r"style|remove-attr|remove-class|matches-media|shadow|contains|-abp-\w+)\b",
)

TYPES = {
  "script", "image", "stylesheet", "css", "xmlhttprequest", "xhr", "subdocument", "frame",
  "font", "media", "object", "ping", "beacon", "websocket", "other",
}
PLAIN = {"third-party", "3p", "first-party", "1p", "important", "match-case", "badfilter"}
VALUED = {"domain", "from", "redirect", "redirect-rule"}
# Exception-only options the app's RuleEngine understands: whole-page and generic-hiding exemptions.
HOST_EXCEPTIONS = {"document", "doc", "generichide", "ghide", "elemhide", "ehide"}
HOST_RULE = re.compile(r"^\|\|[a-z0-9.-]+\^?$")
HOST_ONLY_OPTIONS = {"third-party", "3p"}


def is_cosmetic(line: str) -> bool:
  i = line.find("#")
  if i < 0:
    return False
  nxt = line[i + 1:i + 2]
  if nxt == "#":
    return True
  return nxt in "@?$%" and line[i + 2:i + 3] == "#"


def cosmetic_line(raw: str):
  """Returns the line when it is a hiding rule or scriptlet the app can apply, else None."""
  line = raw.strip()
  if not line or line[0] in "![":
    return None
  for separator in ("#@#", "##"):
    i = line.find(separator)
    if i < 0:
      continue
    domains, body = line[:i], line[i + len(separator):]
    if not body or "*" in domains:
      return None
    if body.startswith("+js("):
      if not body.endswith(")"):
        return None
      name = body[4:-1].split(",", 1)[0].strip()
      if name.endswith(".js"):
        name = name[:-3]
      return line if (name in SCRIPTLETS or name == "") and domains else None
    if body.startswith("^") or PROCEDURAL.search(body) or "\n" in body:
      return None
    return line
  return None


def classify(raw: str):
  """Returns 'host', 'rule' or None."""
  line = raw.strip()
  if not line or line[0] in "![" or is_cosmetic(line):
    return None
  exception = line.startswith("@@")
  body = line[2:] if exception else line
  options = ""
  dollar = body.rfind("$")
  if dollar >= 0:
    options, body = body[dollar + 1:], body[:dollar]
  if len(body) > 1 and body.startswith("/") and body.endswith("/"):
    return None
  option_names = []
  for option in options.split(",") if options else []:
    name = option.lstrip("~")
    key, _, value = name.partition("=")
    if exception and key in ("redirect", "redirect-rule"):
      return None
    if key in TYPES or key in PLAIN:
      option_names.append(name)
    elif key in VALUED and value:
      option_names.append(name)
    elif exception and key in HOST_EXCEPTIONS:
      option_names.append(name)
    else:
      return None
  if not exception and HOST_RULE.match(body.lower()) and set(option_names) <= HOST_ONLY_OPTIONS:
    return "host"
  if not body and not option_names:
    return None
  return "rule"


def evaluate(expression: str) -> bool:
  cleaned = expression.replace("(", " ").replace(")", " ")
  if "||" in cleaned:
    return any(evaluate(part) for part in cleaned.split("||"))
  if "&&" in cleaned:
    return all(evaluate(part) for part in cleaned.split("&&"))
  term = cleaned.strip()
  if term.startswith("!"):
    return not evaluate(term[1:])
  return term in TRUE_CONDITIONS


def preprocess(lines):
  """Applies `!#if` blocks the way the app does, so the bundled output matches a live fetch."""
  stack = []
  for line in lines:
    stripped = line.strip()
    if stripped.startswith("!#if "):
      stack.append(evaluate(stripped[5:]))
      continue
    if stripped.startswith("!#else"):
      if stack:
        stack[-1] = not stack[-1]
      continue
    if stripped.startswith("!#endif"):
      if stack:
        stack.pop()
      continue
    if all(stack):
      yield line


def read_source(source: str):
  """One list plus whatever it `!#include`s, one level deep, from the same place."""
  lines = fetch(source)
  includes = [l[len("!#include "):].strip() for l in lines if l.startswith("!#include ")]
  for name in includes[:MAX_INCLUDES]:
    if not name or " " in name or "/" in name or name.startswith("."):
      continue
    base = source.rsplit("/", 1)[0] + "/" if "/" in source else ""
    try:
      lines.append(f"! --- included: {name} ---")
      lines.extend(fetch(base + name))
    except Exception as error:  # noqa: BLE001 - a missing include must not sink the whole list
      print(f"include {name} of {source} failed: {error}", file=sys.stderr)
  return list(preprocess(lines))


def fetch(source: str):
  if source.startswith("https://"):
    # easylist.to answers the default urllib agent with 403; name the tool instead.
    request = urllib.request.Request(source, headers={"User-Agent": "wren-filter-compiler/1.0"})
    with urllib.request.urlopen(request, timeout=60) as response:
      return response.read().decode("utf-8", "replace").splitlines()
  with open(source, encoding="utf-8", errors="replace") as handle:
    return handle.read().splitlines()

def main() -> int:
  sources = sys.argv[1:] or DEFAULT_SOURCES
  kept, cosmetic, hosts, dropped = [], [], 0, 0
  seen = set()
  for source in sources:
    for raw in read_source(source):
      line = cosmetic_line(raw)
      if line is not None:
        if line not in seen:
          seen.add(line)
          cosmetic.append(line)
        continue
      kind = classify(raw)
      if kind == "rule":
        line = raw.strip()
        if line not in seen:
          seen.add(line)
          kept.append(line)
      elif kind == "host":
        hosts += 1
      else:
        dropped += 1
  OUTPUT.write_text("\n".join(kept) + "\n", encoding="utf-8")
  COSMETIC_OUTPUT.write_text("\n".join(cosmetic) + "\n", encoding="utf-8")
  print(f"{len(kept)} pattern rules -> {OUTPUT} ({OUTPUT.stat().st_size // 1024} KB); "
        f"{len(cosmetic)} cosmetic rules -> {COSMETIC_OUTPUT} "
        f"({COSMETIC_OUTPUT.stat().st_size // 1024} KB); "
        f"{hosts} plain host rules left to hosts.bin; {dropped} lines unsupported or not network")
  return 0


if __name__ == "__main__":
  sys.exit(main())
