#!/usr/bin/env python3
"""
AeonRuntime — JNI contract check.

Verifies, purely from source, that the Kotlin <-> native boundary is consistent:

  1. every Kotlin `external fun` has an exported `Java_<pkg>_<Class>_<method>` symbol
     defined in core/src/main/jni/*.cpp (using the JNI name-mangling rules),
  2. no native `Java_*` symbol is orphaned (no matching Kotlin declaration),
  3. `System.loadLibrary("<name>")` matches the CMake `add_library(<name> ...)` target,
  4. every source file listed in that `add_library` exists.

Optionally (`--so <file> ...`) it also checks that the same symbols are really
exported by built shared objects (uses `readelf --dyn-syms`; intended for CI after assembling).

This is a static contract check. It does NOT execute native code and does NOT replace
building with the NDK or testing on a device.

Usage:
  python3 scripts/verify_jni.py                 # source-level check
  python3 scripts/verify_jni.py --so path/*.so  # also verify built libraries
Exit status: 0 = consistent, 1 = mismatch, 2 = could not run.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
KOTLIN_DIR = ROOT / "core" / "src" / "main" / "kotlin"
JNI_DIR = ROOT / "core" / "src" / "main" / "jni"


def mangle(part: str) -> str:
    """JNI short-name mangling for one identifier / package path."""
    out = []
    for ch in part:
        if ch in "./":
            out.append("_")
        elif ch == "_":
            out.append("_1")
        elif ch == ";":
            out.append("_2")
        elif ch == "[":
            out.append("_3")
        elif ch.isascii() and (ch.isalnum()):
            out.append(ch)
        else:
            out.append("_0%04x" % ord(ch))
    return "".join(out)


def kotlin_externals() -> dict[str, str]:
    """Return {expected_symbol: 'path:line'} for every `external fun` in Kotlin."""
    expected: dict[str, str] = {}
    for kt in sorted(KOTLIN_DIR.rglob("*.kt")):
        text = kt.read_text(encoding="utf-8")
        if "external fun" not in text:
            continue
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        cls = re.search(r"^\s*(?:object|class)\s+(\w+)", text, re.M)
        if not pkg or not cls:
            print(f"ERROR: cannot determine package/class in {kt}", file=sys.stderr)
            sys.exit(2)
        fq = f"{pkg.group(1)}.{cls.group(1)}"
        for m in re.finditer(r"external\s+fun\s+(\w+)\s*\(", text):
            line = text.count("\n", 0, m.start()) + 1
            sym = f"Java_{mangle(fq)}_{mangle(m.group(1))}"
            rel = kt.relative_to(ROOT)
            if sym in expected:
                print(f"ERROR: overloaded native '{m.group(1)}' needs a __signature suffix "
                      f"(not supported by this checker): {rel}:{line}", file=sys.stderr)
                sys.exit(2)
            expected[sym] = f"{rel}:{line}"
    return expected


def native_exports() -> dict[str, str]:
    found: dict[str, str] = {}
    for src in sorted(list(JNI_DIR.glob("*.cpp")) + list(JNI_DIR.glob("*.c"))):
        text = src.read_text(encoding="utf-8")
        for m in re.finditer(r"\b(Java_[A-Za-z0-9_]+)\s*\(", text):
            line = text.count("\n", 0, m.start()) + 1
            found.setdefault(m.group(1), f"{src.relative_to(ROOT)}:{line}")
    return found


def load_library_names() -> list[str]:
    names = []
    for kt in KOTLIN_DIR.rglob("*.kt"):
        names += re.findall(r'System\.loadLibrary\(\s*"([^"]+)"\s*\)', kt.read_text(encoding="utf-8"))
    return names


def cmake_target() -> tuple[str | None, list[str]]:
    cml = JNI_DIR / "CMakeLists.txt"
    text = re.sub(r"#.*", "", cml.read_text(encoding="utf-8"))
    m = re.search(r"add_library\(\s*([^\s)]+)\s+SHARED\s+([^)]*)\)", text, re.S)
    if not m:
        return None, []
    return m.group(1), m.group(2).split()


def so_exports(so: pathlib.Path) -> set[str]:
    """Defined dynamic symbols of an ELF shared object (any architecture).

    Uses `readelf` rather than `nm` because binutils' `nm` on an x86 host usually cannot
    read arm64/arm ELF files, while `readelf` parses ELF generically.
    """
    res = subprocess.run(["readelf", "--dyn-syms", "-W", str(so)], capture_output=True, text=True)
    if res.returncode != 0:
        print(f"ERROR: readelf failed on {so}: {res.stderr.strip()}", file=sys.stderr)
        sys.exit(2)
    syms: set[str] = set()
    for line in res.stdout.splitlines():
        parts = line.split()
        # Num: Value Size Type Bind Vis Ndx Name
        if len(parts) >= 8 and parts[0].endswith(":") and parts[6] != "UND":
            syms.add(parts[7].split("@")[0])
    return syms


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--so", nargs="*", default=[], help="built libaeonruntime_jni.so files to check with readelf")
    args = ap.parse_args()

    problems: list[str] = []
    expected = kotlin_externals()
    exported = native_exports()

    print(f"Kotlin external funs : {len(expected)}")
    print(f"Native Java_* symbols: {len(exported)}")

    for sym, where in expected.items():
        if sym not in exported:
            problems.append(f"MISSING native symbol {sym}  (declared at {where})")
    for sym, where in exported.items():
        if sym not in expected:
            problems.append(f"ORPHAN native symbol {sym}  (defined at {where}; no Kotlin external fun)")

    libs = load_library_names()
    target, sources = cmake_target()
    if target is None:
        problems.append("CMakeLists.txt: no add_library(<name> SHARED ...) found")
    else:
        print(f"CMake target         : {target}  (-> lib{target}.so)")
        if not libs:
            problems.append("no System.loadLibrary(...) call found in Kotlin sources")
        for name in libs:
            print(f"System.loadLibrary   : {name}")
            if name != target:
                problems.append(f'System.loadLibrary("{name}") does not match CMake target "{target}"')
        for s in sources:
            if not (JNI_DIR / s).is_file():
                problems.append(f"CMake source listed but missing on disk: core/src/main/jni/{s}")

    for so in args.so:
        syms = so_exports(pathlib.Path(so))
        missing = [s for s in expected if s not in syms]
        print(f"{so}: {len(expected) - len(missing)}/{len(expected)} expected symbols exported")
        problems += [f"{so}: symbol not exported: {s}" for s in missing]

    if problems:
        print("\nJNI contract check FAILED:")
        for p in problems:
            print("  - " + p)
        return 1
    print("\nJNI contract check passed (static; see header for what this does not cover).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
