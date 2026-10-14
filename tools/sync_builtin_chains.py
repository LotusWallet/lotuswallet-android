#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 `LotusWallet/assets` 已发布的链清单对齐成 APK 的内置资源。

为什么不变量需要专门的工具来守
------------------------------
内置资源 `core/network/src/main/resources/chains.json` 是**新装用户的离线基线**，
客户端的三层清单是「内置 < 远端清单 < 用户自定义」，所以它必须等于
**某一次真实发布过**的清单 —— 否则会出现「用户离线看到的链列表，
在服务端从来没有存在过」这种说不清的状态。

问题是这条不变量**有两个生成器**：
  - `repos/assets/tools/sync_chainlist.py` → 发布侧产物
  - 本仓库 `tools/generate_chains.py`     → 内置资源
两者各自去抓 chainlist，靠巧合产出逐字节相同的结果。
只要上游在两次抓取之间动了、或者某个筛选规则改了一处没同步，
不变量就悄悄破了 —— 而且**没有任何测试会因此变红**。

这个工具把「巧合」换成「结构」：内置资源**就是从发布产物拷过来的**，
逐字节断言，没有第二种可能。

用法
----
    python tools/sync_builtin_chains.py            # 拷贝并断言
    python tools/sync_builtin_chains.py --check    # 只断言，不写文件

`release.py` 会在发版前自动调用一次，所以正常流程不需要手工记着跑。
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
BUILTIN = PROJECT_ROOT / "core" / "network" / "src" / "main" / "resources" / "chains.json"

DEFAULT_REPOS = Path("D:/work/LotusWallet/repos")
PUBLISHED = Path("assets/chains/chains.json")


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description="用已发布的链清单对齐 APK 内置资源")
    parser.add_argument("--repos", type=Path, default=DEFAULT_REPOS,
                        help=f"发布仓库工作副本根目录（默认 {DEFAULT_REPOS}）")
    parser.add_argument("--check", action="store_true", help="只校验，不写文件")
    args = parser.parse_args()

    source: Path = args.repos / PUBLISHED
    if not source.is_file():
        print(f"错误：找不到已发布的链清单 {source}\n"
              f"      （用 --repos 指定仓库工作副本根目录）", file=sys.stderr)
        return 1
    if not BUILTIN.is_file():
        print(f"错误：找不到内置资源 {BUILTIN}", file=sys.stderr)
        return 1

    published = source.read_bytes()
    builtin = BUILTIN.read_bytes()

    if published == builtin:
        print(f"内置资源与已发布清单逐字节一致 ✓  (sha256={sha(published)[:16]}, {len(published):,} 字节)")
        return 0

    if args.check:
        print(
            "错误：内置资源与已发布清单不一致 ——\n"
            f"  已发布 {source}\n"
            f"         sha256={sha(published)[:16]}  {len(published):,} 字节\n"
            f"  内置   {BUILTIN}\n"
            f"         sha256={sha(builtin)[:16]}  {len(builtin):,} 字节\n"
            "  跑一次 `python tools/sync_builtin_chains.py` 对齐。",
            file=sys.stderr,
        )
        return 1

    # 用 write_bytes：这个文件必须与发布产物**逐字节**一致。
    # write_text 在 Windows 上会把 \n 转成 \r\n，直接破坏该不变量（踩过一次）。
    BUILTIN.write_bytes(published)

    if BUILTIN.read_bytes() != published:
        print("错误：写入后仍不一致 —— 文件系统层面出了问题，请人工检查。", file=sys.stderr)
        return 1

    print(f"已对齐 {BUILTIN.relative_to(PROJECT_ROOT)}")
    print(f"  <- {source}")
    print(f"  sha256={sha(published)[:16]}  {len(published):,} 字节")
    print("\n注意：这只改了源码里的基线，**要下次发版才会进 APK**。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
