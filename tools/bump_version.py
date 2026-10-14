#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
递增 LotusWallet 版本号。

    python tools/bump_version.py --part patch     # 3.0.1 → 3.0.2   （修 bug、改文案）
    python tools/bump_version.py --part minor     # 3.0.1 → 3.1.0   （加功能）
    python tools/bump_version.py --part major     # 3.0.1 → 4.0.0   （不兼容变更）
    python tools/bump_version.py --part patch --pre 1   # → 3.0.2-beta.1（内测）
    python tools/bump_version.py --verify         # 只检查各处的版本号是否一致

为什么要有这个脚本
------------------
版本号散落在 version.properties、APK、git tag、download 仓库的 versions.json 和
CHANGELOG 里。手改必然漏。本脚本负责改**唯一来源**并打印出其余各处该写什么，
`release.py` 再负责把 versions.json 与 tag 对齐。

⚠️ 约定：每次改动（无论大小）都要升版本号。这是硬规矩 ——
   用户报 bug 时我们问的第一个问题就是「你装的是哪个版本」。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
VERSION_FILE = REPO / "version.properties"
CHANGELOG = REPO / "CHANGELOG.md"

# download 仓库的内容（本机工作副本，可能不存在）
DOWNLOAD_VERSIONS = REPO.parent / "repos" / "download-src" / "versions.json"

BOUND = 99


def read_version() -> dict[str, int]:
    props: dict[str, str] = {}
    for line in VERSION_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()

    def get(name: str) -> int:
        raw = props.get(name)
        if raw is None or not raw.isdigit():
            raise SystemExit(f"version.properties 里的 {name} 缺失或不是非负整数：{raw!r}")
        return int(raw)

    return {
        "major": get("version.major"),
        "minor": get("version.minor"),
        "patch": get("version.patch"),
        "pre": get("version.pre"),
    }


def write_version(v: dict[str, int]) -> None:
    # 保留原有注释，只替换四行赋值 —— 让 diff 干净、也保住文件里的说明。
    text = VERSION_FILE.read_text(encoding="utf-8")
    for key, value in (
        ("version.major", v["major"]),
        ("version.minor", v["minor"]),
        ("version.patch", v["patch"]),
        ("version.pre", v["pre"]),
    ):
        text, n = re.subn(rf"(?m)^{key}\s*=\s*\d+\s*$", f"{key}={value}", text)
        if n != 1:
            raise SystemExit(f"在 version.properties 里找不到唯一的 {key}= 行（匹配到 {n} 处）")
    VERSION_FILE.write_text(text, encoding="utf-8")


def names(v: dict[str, int]) -> tuple[str, int]:
    version_name = f"{v['major']}.{v['minor']}.{v['patch']}"
    if v["pre"] > 0:
        version_name += f"-beta.{v['pre']}"
    version_code = v["major"] * 10_000 + v["minor"] * 100 + v["patch"]
    return version_name, version_code


def bump(v: dict[str, int], part: str) -> dict[str, int]:
    new = dict(v)
    new["pre"] = 0
    if part == "patch":
        new["patch"] += 1
        if new["patch"] > BOUND:
            raise SystemExit(f"patch 超过 {BOUND}：请改用 --part minor")
    elif part == "minor":
        new["minor"] += 1
        new["patch"] = 0
        if new["minor"] > BOUND:
            raise SystemExit(f"minor 超过 {BOUND}：请改用 --part major")
    elif part == "major":
        new["major"] += 1
        new["minor"] = 0
        new["patch"] = 0
    else:
        raise SystemExit(f"未知的 --part：{part}")
    return new


def verify() -> int:
    problems: list[str] = []
    v = read_version()
    version_name, version_code = names(v)
    print(f"version.properties : {version_name}  (versionCode {version_code})")

    if DOWNLOAD_VERSIONS.exists():
        doc = json.loads(DOWNLOAD_VERSIONS.read_text(encoding="utf-8"))
        latest = doc.get("latest") or {}
        remote_name = latest.get("versionName")
        remote_code = latest.get("versionCode")
        print(f"download/versions.json latest : {remote_name}  (versionCode {remote_code})")
        if remote_name and remote_name != version_name:
            problems.append(
                f"download 仓库的 latest 是 {remote_name}，本地是 {version_name} —— "
                f"要么本地忘了发版，要么 release.py 没跑"
            )
    else:
        print(f"（未找到 {DOWNLOAD_VERSIONS}，跳过远端一致性检查）")

    if CHANGELOG.exists():
        text = CHANGELOG.read_text(encoding="utf-8")
        if f"## {version_name}" not in text:
            problems.append(f"CHANGELOG.md 里还没有 `## {version_name}` 这一节")

    if problems:
        print("\n不一致：", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1
    print("\n版本号一致。")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="递增或校验 LotusWallet 版本号")
    parser.add_argument("--part", choices=["patch", "minor", "major"],
                        help="递增哪一段")
    parser.add_argument("--pre", type=int, metavar="N",
                        help="设为预发布 -beta.N（N>0）")
    parser.add_argument("--verify", action="store_true", help="只校验，不修改")
    args = parser.parse_args()

    if args.verify:
        return verify()
    if not args.part:
        parser.error("需要 --part，或使用 --verify")

    old = read_version()
    old_name, old_code = names(old)

    new = bump(old, args.part)
    if args.pre is not None:
        if not 1 <= args.pre <= 98:
            raise SystemExit("--pre 取值范围是 1..98")
        new["pre"] = args.pre

    new_name, new_code = names(new)
    if new_code <= old_code:
        raise SystemExit(
            f"新 versionCode {new_code} 不大于旧的 {old_code}。"
            f"versionCode 必须单调递增，否则设备无法覆盖安装。"
        )

    write_version(new)

    print(f"{old_name}  →  {new_name}")
    print(f"versionCode  {old_code}  →  {new_code}")
    print()
    print("接下来要做的事：")
    print(f"  1. 在 CHANGELOG.md 顶部加一节 `## {new_name}` 写清改了什么")
    print(f"  2. python tools/release.py        构建签名 APK、更新 versions.json、打 tag v{new_name}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
