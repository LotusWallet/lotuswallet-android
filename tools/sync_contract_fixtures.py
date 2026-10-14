#!/usr/bin/env python3
"""把「实际发布出去的那几份 JSON 清单」同步成单元测试夹具。

为什么需要
----------
链清单 / 市场清单 / 版本清单是**跨仓库契约**：内容由 `repos/assets`、`repos/download-src`
生成，解析却发生在客户端。两侧一旦字段对不上，客户端是**静默失效**
（链拉不到、市场空白、永远提示已是最新），真机上几乎不可能复现。

所以这里把发布侧的真实产物拷进 `core/network/src/test/resources/contract/`，
由 `PublishedManifestContractTest` 用**客户端真正的解析器**跑一遍。

用法
----
    python tools/sync_contract_fixtures.py            # 从仓库产物刷新夹具
    python tools/sync_contract_fixtures.py --check    # 只校验夹具是否与产物一致

`--check` 适合放进 CI / 发版前检查：夹具过期或产物变得无法解析，都会在这里被拦下。
"""

from __future__ import annotations

import argparse
import filecmp
import json
import shutil
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
FIXTURE_DIR = PROJECT_ROOT / "core" / "network" / "src" / "test" / "resources" / "contract"

DEFAULT_REPOS = Path("D:/work/LotusWallet/repos")

# 夹具名 -> 发布侧产物路径（相对于 repos 根）
SOURCES = {
    "chains.json": "assets/chains/chains.json",
    "dapps.json": "assets/market/dapps.json",
    "versions.json": "download-src/versions.json",
}


def fail(msg: str):
    print(f"错误：{msg}", file=sys.stderr)
    sys.exit(1)


def sanity_check(name: str, raw: str) -> None:
    """夹具必须是合法 JSON 且顶层结构对得上，避免把半成品拷进来。"""
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        fail(f"{name} 不是合法 JSON：{exc}")

    if name == "chains.json":
        chains = data.get("chains")
        if not isinstance(chains, list) or not chains:
            fail("chains.json 的 chains 为空 —— 客户端解析后会一条链都没有")
        known_families = {"evm", "utxo", "tron", "ed25519", "filecoin", "chia"}
        for index, chain in enumerate(chains):
            family = chain.get("family")
            if family not in known_families:
                fail(
                    f"chains.json 第 {index} 条链 family={family!r} 不在 {sorted(known_families)} 里"
                    " —— 客户端解析器不认识这个家族"
                )
            # chia 的公网节点没有稳定入口，允许留空；其余链没有节点就是隐形故障
            if family != "chia" and not chain.get("rpcUrls"):
                fail(f"chains.json 的 {chain.get('id', index)} 没有任何 rpcUrls")
    elif name == "dapps.json":
        if not isinstance(data.get("dapps"), list) or not data["dapps"]:
            fail("dapps.json 的 dapps 为空 —— 市场 Tab 会退化成内置清单")
    elif name == "versions.json":
        latest = data.get("latest") or (data.get("releases") or [None])[-1]
        if not isinstance(latest, dict):
            fail("versions.json 既没有 latest 也没有 releases —— 检查更新会永远显示已是最新")
        for key in ("versionName", "versionCode"):
            if key not in latest:
                fail(f"versions.json 的 latest 缺少 {key}")


def main() -> None:
    parser = argparse.ArgumentParser(description="同步/校验远端清单契约夹具")
    parser.add_argument("--repos", type=Path, default=DEFAULT_REPOS,
                        help=f"两个发布仓库的工作副本根目录（默认 {DEFAULT_REPOS}）")
    parser.add_argument("--check", action="store_true",
                        help="只校验夹具与产物是否一致，不写文件")
    args = parser.parse_args()

    repos: Path = args.repos
    if not repos.is_dir():
        fail(f"找不到仓库工作副本目录：{repos}（用 --repos 指定）")

    FIXTURE_DIR.mkdir(parents=True, exist_ok=True)

    stale: list[str] = []
    for fixture_name, relative in SOURCES.items():
        source = repos / relative
        if not source.is_file():
            fail(f"缺少发布侧产物：{source}")

        raw = source.read_text(encoding="utf-8")
        sanity_check(fixture_name, raw)

        target = FIXTURE_DIR / fixture_name
        if args.check:
            if not target.is_file() or not filecmp.cmp(source, target, shallow=False):
                stale.append(fixture_name)
            continue

        shutil.copyfile(source, target)
        print(f"已同步 {fixture_name:<14} <- {relative}  ({source.stat().st_size:,} 字节)")

    if args.check:
        if stale:
            fail(
                "以下夹具与发布侧产物不一致：" + "、".join(stale)
                + "\n  跑一次 `python tools/sync_contract_fixtures.py` 刷新（若产物本身有问题，先修产物）"
            )
        print(f"契约夹具与发布产物一致（{len(SOURCES)} 份）")
    else:
        print(f"\n夹具目录：{FIXTURE_DIR}")


if __name__ == "__main__":
    main()
