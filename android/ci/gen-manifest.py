#!/usr/bin/env python3
"""Generate an incremental-update manifest and stage individual .so files.

  gen-manifest.py <abi> <libdir> <outdir> [--plugins-dir <dir>]
                  [--build-id <sha>] [--previous <manifest.json>]

For each .so in <libdir>, computes SHA-256 and writes:
  <outdir>/manifest.json          — file inventory for the patcher
  <outdir>/<abi>/<filename>.so    — individual .so files (copy)

Every entry also carries a "build" stamp: the id of the build that last
changed that file's contents. The patcher stores the stamp it applied and
compares the two strings, so it can tell a rebuilt library from an unchanged
one without hashing 150 MB of files on the phone.

Comparing sizes alone cannot do this. libcs_android_arm64.so rebuilt with a
few instructions of HUD fixup came out exactly the same size as the build
before it, so the patcher called it up to date and shipped none of the fix.
The stamp is the answer the user asked for and the honest one: a file is
stale when its bytes differ, and sha256 is what decides that. It is computed
here, once, in CI, where reading the file is free.

--build-id and --previous are how the stamp stays meaningful across releases:
the current build id is stamped onto files whose sha256 changed since the
previous manifest, and files that did not change keep the stamp they already
had. Without --previous the first manifest stamps everything with the build
id, which is correct but marks unchanged files as new.
"""
import argparse
import hashlib
import json
import os
import shutil
import sys

VERSION = os.environ.get("RELEASE_VERSION", "1.10.0-dev")
BUILD_ID = "unknown"

# producer name -> the id of the sources that library is built from.
# Filled from --producer on the command line. Empty means every file falls back
# to the sha256 comparison.
PRODUCERS: dict[str, str] = {}

MODULES = [
    "cstrike", "csx", "engine", "fakemeta", "fun", "geoip",
    "hamsandwich", "json", "nvault", "reapi", "regex", "sockets", "sqlite",
]

# ABI -> (android runtime lib suffix, AMXX module suffix)
ABI_MAP = {
    "arm64-v8a": ("arm64", "amd64"),
    "armeabi-v7a": ("armv7l", "arm"),
}


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def load_previous(path: str | None) -> dict:
    """name -> {sha256, build} from the manifest this build supersedes."""
    if not path or not os.path.exists(path):
        return {}
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError) as exc:
        print(f"WARN: previous manifest unreadable ({exc})", file=sys.stderr)
        return {}
    out = {}
    for entry in data.get("files", []):
        name = entry.get("name")
        if name:
            out[name] = {"sha256": entry.get("sha256", ""), "build": entry.get("build", "unknown")}
    return out


def stamp_for(name: str, sha: str, previous: dict, producer: str | None = None) -> str:
    """The stamp that tells the phone whether it needs this file.

    Two ways to answer that, and the producer one is the one that works.

    Comparing sha256 of the output only works if the build is reproducible.
    It is not: libclient_android_*.so built from an identical tree twice came
    out the same length with different content. So its sha changed, the stamp
    advanced, and the phone re-downloaded a library nobody had touched - along
    with every other one built in the same run, because they all carry the same
    flaw.

    A producer stamp asks a better question: has anything that goes into this
    library changed? The CI already knows, as the submodule commits and the
    cs16-client pin. It is
    coarser than sha256 within a producer - an edit to the client dll marks the
    menu as new too - but it is never wrong in the direction that matters,
    which is marking a changed library as up to date.

    Falls back to the sha comparison for a file with no known producer, and
    for the first release of a producer whose phone has never seen it.
    """
    if producer and producer in PRODUCERS:
        return f"{producer}@{PRODUCERS[producer]}"

    old = previous.get(name)
    if old and old.get("sha256") == sha and old.get("build"):
        return old["build"]
    return BUILD_ID


def collect_entries(libdir: str, abi: str, previous: dict) -> list:
    suffix, mod_suffix = ABI_MAP[abi]
    entries = []

    def add(name, target, required, desc, producer=None):
        src = os.path.join(libdir, name)
        if not os.path.exists(src):
            print(f"WARN: {name} not found, skipping", file=sys.stderr)
            return
        asset_name = f"{abi}__{name}"
        sha = sha256_of(src)
        entries.append({
            "name": name,
            "asset": asset_name,
            "path": f"lib/{abi}/{name}",
            "target": target,
            "sha256": sha,
            "build": stamp_for(name, sha, previous, producer),
            "producer": producer or "",
            "size": os.path.getsize(src),
            "required": required,
            "description": desc,
        })

    add("libamxmodx.so", f"lib/{abi}/libamxmodx.so", True, "AMX Mod X core", producer="amxmodx")

    metamod_name = "libyapb_android_" + suffix + ".so"
    metamod_src = os.path.join(libdir, "libmetamod.so")
    if os.path.exists(metamod_src):
        asset_name = f"{abi}__libmetamod.so"
        metamod_sha = sha256_of(metamod_src)
        entries.append({
            "name": "libmetamod.so",
            "asset": asset_name,
            "path": f"lib/{abi}/libmetamod.so",
            "target": f"lib/{abi}/{metamod_name}",
            "sha256": metamod_sha,
            "build": stamp_for("libmetamod.so", metamod_sha, previous, "metamod"),
            "producer": "metamod",
            "size": os.path.getsize(metamod_src),
            "required": True,
            "description": "Metamod HL1 (as libyapb for -dll @yapb)",
        })

    add("libyapb.so", f"lib/{abi}/libyapb.so", False, "YaPB bot plugin", producer="yapb")

    client_name = f"libclient_android_{suffix}.so"
    add(client_name, f"lib/{abi}/{client_name}", False, "CS16Client client DLL (crash handler)",
        producer="cs16-client")

    # The main menu, built from the same tree as the client. The engine looks
    # for cl_dlls/libmenu_android_<arch>.so first, then <gamelibdir>/ of the
    # same name, and only falls back to libmenu.so out of the engine package.
    # Shipping it under the first name is what makes the game package the menu
    # comes from; without it every run used the stock menu from the engine
    # app, which is not the one this tree builds.
    menu_name = f"libmenu_android_{suffix}.so"
    add(menu_name, f"lib/{abi}/{menu_name}", False, "CS16Client main menu (mainui_cpp)",
        producer="cs16-client")

    # ReGameDLL game DLL, bundled as libcs. Shipped from the berkchy fork branch
    # fix/first-spawn-equip; skipped silently when not present, then
    # the patcher falls back to patching the base APK's libcs in place.
    libcs_name = "libcs_android_" + suffix + ".so"
    add(libcs_name, f"lib/{abi}/{libcs_name}", False, "ReGameDLL game DLL (first-spawn fix)",
        producer="regamedll")

    # libmenu intentionally NOT shipped: the text-based menu is broken, stock stays.
    for mod in MODULES:
        modname = f"lib{mod}_amxx_{mod_suffix}.so"
        add(modname, f"lib/{abi}/{modname}", True, f"{mod} module", producer="amxmodx")

    return entries


def main():
    parser = argparse.ArgumentParser(description="Generate incremental update manifest")
    parser.add_argument("abi", help="Target ABI (arm64-v8a or armeabi-v7a)")
    parser.add_argument("libdir", help="Directory containing built .so files")
    parser.add_argument("outdir", help="Output directory for manifest + staged files")
    parser.add_argument("--plugins-dir", help="Directory containing compiled .amxx plugins")
    parser.add_argument("--build-id", default="", help="commit id stamped onto rebuilt files")
    parser.add_argument("--previous", help="manifest.json of the release being superseded")
    parser.add_argument(
        "--producer",
        action="append",
        default=[],
        metavar="NAME=STAMP",
        help=(
            "id of the sources one group of libraries is built from, e.g. "
            "cs16-client=<commit>. Repeatable. A file whose producer is listed is "
            "stamped from that id instead of from its sha256, because the build "
            "is not reproducible and identical sources keep changing the hash."
        ),
    )
    args = parser.parse_args()

    global BUILD_ID
    BUILD_ID = args.build_id or os.environ.get("GITHUB_SHA", "")[:12] or "unknown"
    previous = load_previous(args.previous)

    for item in args.producer:
        name, sep, value = item.partition("=")
        if not sep or not name or not value:
            print(f"WARN: --producer {item!r} is not NAME=STAMP, ignoring", file=sys.stderr)
            continue
        PRODUCERS[name] = value

    if PRODUCERS:
        print(f"producers: {', '.join(f'{k}={v}' for k, v in sorted(PRODUCERS.items()))}")

    if args.abi not in ABI_MAP:
        print(f"unsupported ABI: {args.abi}", file=sys.stderr)
        sys.exit(1)

    # Collect .so entries
    entries = collect_entries(args.libdir, args.abi, previous)

    manifest = {
        "version": VERSION,
        "game": "cs16client",
        "abi": args.abi,
        "files": entries,
    }

    # Write manifest
    os.makedirs(args.outdir, exist_ok=True)
    manifest_path = os.path.join(args.outdir, "manifest.json")
    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
    print(f"manifest: {manifest_path} ({len(entries)} files)")

    # Stage individual .so files (ABI-prefixed to avoid conflicts)
    staged_dir = os.path.join(args.outdir, args.abi)
    os.makedirs(staged_dir, exist_ok=True)
    for entry in entries:
        src = os.path.join(args.libdir, entry["name"])
        dst = os.path.join(staged_dir, entry["asset"])
        shutil.copy2(src, dst)
        os.chmod(dst, 0o755)

    print(f"staged: {staged_dir} ({len(entries)} files)")

    # Stage plugins if provided
    if args.plugins_dir and os.path.isdir(args.plugins_dir):
        plugins_dir = os.path.join(args.outdir, "plugins")
        os.makedirs(plugins_dir, exist_ok=True)
        count = 0
        for name in sorted(os.listdir(args.plugins_dir)):
            if name.endswith(".amxx"):
                shutil.copy2(os.path.join(args.plugins_dir, name),
                             os.path.join(plugins_dir, name))
                count += 1
        print(f"plugins: {plugins_dir} ({count} files)")


if __name__ == "__main__":
    main()
