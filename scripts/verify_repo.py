#!/usr/bin/env python3
"""
AeonRuntime — repository consistency checks (source-level, no Android SDK needed).

What this checks
  1. package declarations match their directory path
  2. every `import com.aeoncorex.aeonruntime...` resolves to a declaration in this repo
  3. AndroidManifest component / initializer classes exist
  4. custom views referenced from layout XML exist
  5. ProGuard/R8 `-keep` rules that name project classes/packages point at real ones
  6. Gradle plugin ids used by modules are declared in build-logic; plugin classes exist
  7. ABI names set in bootstrap-* modules are valid `Architecture` bootstrap names;
     bootstrap asset path written by the Gradle plugin == path read by BootstrapInstaller
  9. relative documentation links resolve
  8. legacy-name gate: no `libtermux`/old-API identifiers anywhere, and every remaining
     `termux` token must match a documented upstream pattern (see ALLOWED_TERMUX_TOKENS)

What this does NOT check: that the code compiles, that tests pass, or runtime behaviour.
Import resolution (2) is a heuristic over top-level declarations; it can miss some
cases (e.g. members of nested classes are not verified) but will catch stale package
or class names left behind by a rename.

Usage: python3 scripts/verify_repo.py [--list-upstream]
Exit status: 0 = all checks passed, 1 = violations found.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASE_PKG = "com.aeoncorex.aeonruntime"
SKIP_DIRS = {".git", "build", ".gradle", ".idea", "node_modules"}
TEXT_EXT = {".kt", ".kts", ".java", ".xml", ".pro", ".cpp", ".c", ".h", ".txt", ".yml", ".yaml",
            ".sh", ".py", ".properties", ".toml", ".md", ".html", ".svg", ".json", ".css", ".js", ""}

problems: list[str] = []


def fail(msg: str) -> None:
    problems.append(msg)


def files(pattern: str):
    for p in sorted(ROOT.rglob(pattern)):
        if not (set(p.relative_to(ROOT).parts) & SKIP_DIRS) and p.is_file():
            yield p


def rel(p: pathlib.Path) -> str:
    return str(p.relative_to(ROOT))


# ---------------------------------------------------------------------------------------
# Kotlin source index
# ---------------------------------------------------------------------------------------
DECL_RE = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s+)*"                  # leading annotations on the same line
    r"(?:(?:public|internal|private|protected|data|sealed|enum|abstract|open|annotation|inline|"
    r"value|fun|const|expect|actual|suspend|operator|infix|tailrec|external)\s+)*"
    r"(class|interface|object|typealias|fun|val|var)\s+"
    r"(?:<[^>]+>\s*)?"                         # generic params, e.g. fun <reified T>
    r"(?:[\w<>?,.\s*]+?\.)?"                   # extension receiver, e.g. LibTermux.
    r"(\w+)",
    re.M,
)


def kotlin_roots():
    """Yield (root_dir) for every kotlin source root in the repo."""
    for p in sorted(ROOT.rglob("kotlin")):
        if p.is_dir() and "src" in p.parts and not (set(p.relative_to(ROOT).parts) & SKIP_DIRS):
            yield p


package_decls: dict[str, set[str]] = defaultdict(set)   # package -> top-level names
all_packages: set[str] = set()
kt_files: list[pathlib.Path] = []

for root in kotlin_roots():
    for kt in sorted(root.rglob("*.kt")):
        kt_files.append(kt)
        text = kt.read_text(encoding="utf-8")
        m = re.search(r"^package\s+([\w.]+)", text, re.M)
        pkg = m.group(1) if m else ""
        expected_pkg = ".".join(kt.parent.relative_to(root).parts)
        if pkg != expected_pkg:
            fail(f"[package] {rel(kt)}: declares '{pkg}' but directory implies '{expected_pkg}'")
        all_packages.add(pkg)
        for dm in DECL_RE.finditer(text):
            package_decls[pkg].add(dm.group(2))
        # enum entries / nested are not indexed on purpose (top-level only)

# every ancestor of a package is also a valid package prefix for wildcard / FQN checks
package_prefixes = {".".join(p.split(".")[:i]) for p in all_packages for i in range(1, len(p.split(".")) + 1)}


def resolve_fqn(fqn: str) -> bool:
    """True if fqn = <known package>.<top-level decl>[.member...]."""
    parts = fqn.split(".")
    for i in range(len(parts) - 1, 0, -1):
        pkg = ".".join(parts[:i])
        if pkg in package_decls:
            return parts[i] in package_decls[pkg]
    return False


# 2. imports -------------------------------------------------------------------------
for kt in kt_files:
    for n, line in enumerate(kt.read_text(encoding="utf-8").splitlines(), 1):
        m = re.match(r"\s*import\s+([\w.]+)(\.\*)?(?:\s+as\s+\w+)?\s*$", line)
        if not m or not m.group(1).startswith(BASE_PKG):
            continue
        target = m.group(1)
        if ".databinding." in target:   # generated ViewBinding classes are not in source
            continue
        if m.group(2):  # wildcard
            if target not in package_prefixes:
                fail(f"[import] {rel(kt)}:{n}: wildcard import of unknown package '{target}.*'")
        elif not resolve_fqn(target):
            fail(f"[import] {rel(kt)}:{n}: cannot resolve '{target}' to any declaration in the repo")


# also fully-qualified inline references in code/KDoc-free positions: com.aeoncorex....Name
def class_exists(fqn: str) -> bool:
    return resolve_fqn(fqn)


# ---------------------------------------------------------------------------------------
# 3. manifests
# ---------------------------------------------------------------------------------------
def module_namespace(manifest: pathlib.Path) -> str | None:
    module_dir = manifest.parents[2]
    for gf in ("build.gradle.kts", "build.gradle"):
        g = module_dir / gf
        if g.exists():
            m = re.search(r'namespace\s*=\s*"([^"]+)"', g.read_text(encoding="utf-8"))
            if m:
                return m.group(1)
    return None


for mf in files("AndroidManifest.xml"):
    text = mf.read_text(encoding="utf-8")
    ns = module_namespace(mf)
    for m in re.finditer(r'android:name="([^"]+)"', text):
        name = m.group(1)
        if name.startswith("."):
            if ns is None:
                fail(f"[manifest] {rel(mf)}: relative name '{name}' but no namespace found")
                continue
            name = ns + name
        if name.startswith(BASE_PKG) and not class_exists(name):
            fail(f"[manifest] {rel(mf)}: class '{name}' not found in sources")
        if name.startswith("com.libtermux"):
            fail(f"[manifest] {rel(mf)}: legacy package reference '{name}'")

# 4. layout custom views ----------------------------------------------------------------
for lx in files("*.xml"):
    if "/res/layout" not in str(lx):
        continue
    for m in re.finditer(r"<\s*(com\.[\w.]+)", lx.read_text(encoding="utf-8")):
        if m.group(1).startswith(BASE_PKG) and not class_exists(m.group(1)):
            fail(f"[layout] {rel(lx)}: custom view '{m.group(1)}' not found in sources")

# ---------------------------------------------------------------------------------------
# 5. ProGuard / consumer rules
# ---------------------------------------------------------------------------------------
for pf in files("*.pro"):
    for n, line in enumerate(pf.read_text(encoding="utf-8").splitlines(), 1):
        m = re.match(r"\s*-keep\w*\s+(?:class|interface)\s+(com\.[\w.$*]+)", line)
        if not m or not m.group(1).startswith(BASE_PKG):
            continue
        pat = m.group(1)
        base = pat.split("$")[0]                       # strip $* inner-class wildcard
        if base.endswith(".**") or base.endswith(".*"):
            pkg = base.rsplit(".", 1)[0]
            if pkg not in package_prefixes:
                fail(f"[shrinker] {rel(pf)}:{n}: keep rule targets unknown package '{pkg}'")
        elif not class_exists(base):
            fail(f"[shrinker] {rel(pf)}:{n}: keep rule targets unknown class '{base}'")

# ---------------------------------------------------------------------------------------
# 6. gradle plugins declared vs used
# ---------------------------------------------------------------------------------------
declared_ids: dict[str, str] = {}
bl = ROOT / "build-logic"
for g in bl.rglob("build.gradle.kts"):
    text = g.read_text(encoding="utf-8")
    for m in re.finditer(r'id\s*=\s*"([^"]+)"\s*\n\s*implementationClass\s*=\s*"([^"]+)"', text):
        declared_ids[m.group(1)] = m.group(2)
for sp in bl.rglob("*.gradle.kts"):
    if sp.name not in {"build.gradle.kts", "settings.gradle.kts"}:
        declared_ids[sp.name[: -len(".gradle.kts")]] = f"(precompiled script {rel(sp)})"
for plugin_id, impl in declared_ids.items():
    if impl.startswith("(") is False and not class_exists(impl):
        fail(f"[plugin] implementationClass '{impl}' for id '{plugin_id}' not found")

for gk in files("build.gradle.kts"):
    if "build-logic" in gk.parts:
        continue
    for m in re.finditer(r'\bid\("([^"]+)"\)', gk.read_text(encoding="utf-8")):
        pid = m.group(1)
        if (pid.startswith("aeonruntime.") or pid.startswith(BASE_PKG)) and pid not in declared_ids:
            fail(f"[plugin] {rel(gk)}: uses plugin id '{pid}' which build-logic does not declare")

# ---------------------------------------------------------------------------------------
# 7. ABI names used by bootstrap modules vs Architecture enum
# ---------------------------------------------------------------------------------------
arch_names: set[str] = set()
cfg = next((p for p in kt_files if p.name == "RuntimeConfig.kt"), None)
if cfg:
    arch_names = set(re.findall(r'^\s*(?:ARM64|X86_64|ARM|X86)\("([^"]+)"\)', cfg.read_text(encoding="utf-8"), re.M))
else:
    fail("[abi] RuntimeConfig.kt not found")
for bg in sorted(ROOT.glob("bootstrap-*/build.gradle.kts")):
    m = re.search(r'bootstrapArch\.set\("([^"]+)"\)', bg.read_text(encoding="utf-8"))
    if not m:
        fail(f"[abi] {rel(bg)}: no bootstrapArch.set(...)")
    elif m.group(1) not in arch_names:
        fail(f"[abi] {rel(bg)}: '{m.group(1)}' is not an Architecture bootstrap name {sorted(arch_names)}")

# 7b. bootstrap asset path: producer (Gradle plugin) must agree with consumer (BootstrapInstaller)
plugin_src = next((p for p in kt_files if p.name == "BootstrapFetchPlugin.kt"), None)
installer_src = next((p for p in kt_files if p.name == "BootstrapInstaller.kt"), None)
if plugin_src and installer_src:
    prod = re.search(r'File\(out,\s*"([\w/]+/bootstrap-)\$\w+(\.zip)"\)', plugin_src.read_text(encoding="utf-8"))
    cons = re.search(r'assets\.open\("([\w/]+/bootstrap-)\$\w+(\.zip)"\)', installer_src.read_text(encoding="utf-8"))
    if not prod or not cons:
        fail("[asset] could not locate the bootstrap asset path in producer and/or consumer")
    elif prod.groups() != cons.groups():
        fail(f"[asset] producer writes '{prod.group(1)}<arch>{prod.group(2)}' but consumer reads "
             f"'{cons.group(1)}<arch>{cons.group(2)}'")
else:
    fail("[asset] BootstrapFetchPlugin.kt or BootstrapInstaller.kt not found")

# ---------------------------------------------------------------------------------------
# 8. legacy-name gate
# ---------------------------------------------------------------------------------------
# (a) hard-forbidden everywhere except explicitly allowlisted historical documents
FORBIDDEN = [
    r"com\.libtermux", r"Java_com_libtermux", r"LibTermux", r"libtermux(?!-exec)", r"libTermux",
    r"\bTermux(?:Config|ConfigDsl|Bridge|Logger|BackgroundService|CommandException|Dsl)\b",
    r"\btermuxConfig\b", r"\bShizukuTermux\b", r"github\.com/libtermux",
]
# Files that legitimately describe the old names (historical record of the rename, and this tool).
HISTORICAL_ALLOW = {
    "CHANGELOG.md",
    "docs/migration/MIGRATION_REPORT.md",
    "docs/migration/RENAME_MAP.md",
    "docs/migration/KNOWN_ISSUES.md",
    "docs/migration/FILES_CHANGED.txt",
    "scripts/verify_repo.py",
    "scripts/verify_jni.py",
}

# (b) `termux` tokens that are allowed because they refer to the *upstream* Termux project,
#     its package names, env-var conventions or binaries. Each has a stated reason.
ALLOWED_TERMUX_TOKENS: list[tuple[str, str]] = [
    (r"^Termux(?:[-'’][\w-]+)?[.,:;)\]`'\"]*$",        "prose reference to the upstream Termux project (capitalised word)"),
    (r"TERMUX_[A-Z0-9_]+|TERMUX__[A-Z0-9_]+",           "upstream environment-variable / build-property convention (incl. shell vars)"),
    (r"termux/termux-packages",                         "upstream package repository / bootstrap release source (URLs)"),
    (r"termux/proot-distro",                            "upstream proot-distro rootfs releases"),
    (r"termux-packages",                                "upstream termux-packages repository (working-dir / log text)"),
    (r"^termux-app$",                                   "upstream termux-app (referenced in a comment)"),
    (r"libtermux-exec\.so",                             "upstream termux-exec preload library name"),
    (r"com\\?\.termux",                                    "upstream default package / prefix that bootstrap zips are built for"),
    (r"^termux_packages_ref$|^inputs\.termux_packages_ref$", "workflow input naming the upstream termux-packages ref"),
    (r"^TermuxInstaller$",                              "upstream termux-app class named in a comment"),
    (r"^termux$",                                       "bare lowercase word inside prose / quoted upstream name"),
]
ALLOWED_RES = [(re.compile(p), why) for p, why in ALLOWED_TERMUX_TOKENS]
FORBIDDEN_RES = [re.compile(p) for p in FORBIDDEN]
TOKEN_RE = re.compile(r"[\w./:+@%$\\{}\-'’]*termux[\w./:+@%$\\{}\-'’]*", re.I)

list_mode = "--list-upstream" in sys.argv
allowed_hits: dict[str, int] = defaultdict(int)

for p in sorted(ROOT.rglob("*")):
    if not p.is_file() or (set(p.relative_to(ROOT).parts) & SKIP_DIRS):
        continue
    if p.suffix.lower() not in TEXT_EXT or p.name == "gradle-wrapper.jar":
        continue
    try:
        text = p.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue
    r = rel(p)
    historical = r in HISTORICAL_ALLOW
    for n, line in enumerate(text.splitlines(), 1):
        if not historical:
            for rx in FORBIDDEN_RES:
                if rx.search(line):
                    fail(f"[legacy] {r}:{n}: forbidden legacy identifier /{rx.pattern}/ -> {line.strip()[:110]}")
        if r in HISTORICAL_ALLOW:
            continue
        for tm in TOKEN_RE.finditer(line):
            tok = tm.group(0).strip("`'\"*()[],;:")
            if "libtermux" in tok.lower():
                continue  # already reported by the forbidden check above
            why = next((w for rx, w in ALLOWED_RES if rx.search(tok)), None)
            if why is None:
                fail(f"[termux] {r}:{n}: unclassified 'termux' token '{tok}' -> {line.strip()[:110]}")
            else:
                allowed_hits[why] += 1

# ---------------------------------------------------------------------------------------
# 9. documentation links (relative links only; external URLs are not fetched)
# ---------------------------------------------------------------------------------------
LINK_MD = re.compile(r"\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
LINK_HTML = re.compile(r"""(?:href|src)=["']([^"']+)["']""")
for doc in list(files("*.md")) + list(files("*.html")):
    text = doc.read_text(encoding="utf-8")
    pat = LINK_MD if doc.suffix == ".md" else LINK_HTML
    in_code = False
    for n, line in enumerate(text.splitlines(), 1):
        if line.strip().startswith("```"):
            in_code = not in_code
        if in_code:
            continue
        for m in pat.finditer(line):
            tgt = m.group(1)
            if re.match(r"^(?:[a-z][a-z0-9+.-]*:|#|//)", tgt, re.I):
                continue  # external, mailto:, in-page anchor
            path = tgt.split("#")[0].split("?")[0]
            if path and not (doc.parent / path).resolve().exists():
                fail(f"[link] {rel(doc)}:{n}: broken relative link '{tgt}'")

if list_mode:
    print("Allowed upstream 'termux' references by category:")
    for why, n in sorted(allowed_hits.items(), key=lambda kv: -kv[1]):
        print(f"  {n:4d}  {why}")

if problems:
    print(f"verify_repo: {len(problems)} problem(s)")
    for pr in problems:
        print("  - " + pr)
    sys.exit(1)

print(f"verify_repo: OK  ({len(kt_files)} Kotlin files, {len(all_packages)} packages, "
      f"{sum(allowed_hits.values())} classified upstream 'termux' references)")
