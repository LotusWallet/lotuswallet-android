#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 chainlist（https://chainid.network，全开源）生成 LotusWallet 的内置 EVM 链清单。

为什么需要这个脚本
------------------
LotusWallet 的链配置是**声明式**的：加一条 EVM 链只是在 chains.json 里加一段 JSON，
不改任何业务代码。chains.json 里的 EVM 部分不再手写，而是由本脚本从 chainlist
拉取并筛选生成 —— 这样链的名称、符号、精度、RPC、浏览器链接都来自社区维护的
权威数据源，避免手抄出错。

用法
----
    python tools/generate_chains.py            # 使用本地缓存 <系统临时目录>/chainlist.json
    curl -sL https://chainid.network/chains.json -o "$TMPDIR/chainlist.json" && \\
        python tools/generate_chains.py

非 EVM 链（Bitcoin / Litecoin / TRON）不来自 chainlist，脚本会原样保留
chains.json 里已有的 utxo / tron 条目，只重建 EVM 部分。

筛选规则
--------
- 只保留 CURATED_CHAIN_IDS 里列出的链（主网 + 测试网），避免把 chainlist 上
  上千条死链/玩笑链塞给用户；
- RPC 必须是 https 且不含占位符（那种 URL 需要付费 API Key，公共端点才能直接用）；
- 测试网通过 chainId 白名单单独标记，客户端用「显示测试网」开关控制其可见性。

之后要做的事
------------
等自家 GitHub 仓库就绪后，把生成结果提交上去，客户端的远端清单端点指向它即可；
也可以把 CURATED_CHAIN_IDS 扩充后重跑本脚本。
"""

from __future__ import annotations

import json
import os
import re
import sys
import tempfile
from pathlib import Path

CHAINLIST_URL = "https://chainid.network/chains.json"
DEFAULT_CACHE = Path(tempfile.gettempdir()) / "chainlist.json"

# 输出路径相对仓库根目录
OUTPUT = Path(__file__).resolve().parent.parent / "core/network/src/main/resources/chains.json"

# ---------------------------------------------------------------- 白名单
# 主网：钱包默认展示、用户最常用的链。
MAINNET_CHAIN_IDS = [
    1,            # Ethereum
    56,           # BNB Smart Chain
    137,          # Polygon PoS
    8453,         # Base
    10,           # OP Mainnet
    42161,        # Arbitrum One
    43114,        # Avalanche C-Chain
    59144,        # Linea
    534352,       # Scroll
    324,          # zkSync Era
    250,          # Fantom Opera
    100,          # Gnosis Chain
    8217,         # Kaia (原 Klaytn)
    1284,         # Moonbeam
    1285,         # Moonriver
    25,           # Cronos
    1088,         # Metis
    42220,        # Celo
    1313161554,   # Aurora
    1666600000,   # Harmony
    42262,        # Oasis Emerald
    199,          # BitTorrent Chain
    66,           # OKX Chain (OKC)
    128,          # HECO
    321,          # KCC
    167000,       # Taiko
    81457,        # Blast
    34443,        # Mode
    7777777,      # Zora
    1116,         # Core
]

# 测试网：用于开发验证，客户端由「显示测试网」开关控制。
TESTNET_CHAIN_IDS = [
    11155111,     # Sepolia
    11155420,     # OP Sepolia
    84532,        # Base Sepolia
    421614,       # Arbitrum Sepolia
    80002,        # Polygon Amoy
    97,           # BSC Testnet
    17000,        # Holesky
    167009,       # Taiko Hekla
    43113,        # Avalanche Fuji
    10200,        # Gnosis Chiado
    1666700000,   # Harmony Testnet
]

# 最多保留多少条 RPC（按出现顺序，chainlist 已按可用性大致排序）
MAX_RPC = 4

# 可读 id 覆盖：chainlist 的 shortName 有些很费解（如 arb1 / oeth / pol），
# 这里对主流链给出更直白的 id。id 会成为数据库外键，一经发布不可更改，
# 因此这份映射必须保持稳定 —— 新增链若不在表里就回退到 shortName。
ID_OVERRIDES = {
    1: "ethereum",
    56: "bsc",
    137: "polygon",
    10: "optimism",
    42161: "arbitrum",
    43114: "avalanche",
    8453: "base",
    59144: "linea",
    534352: "scroll",
    324: "zksync-era",
    250: "fantom",
    100: "gnosis",
    8217: "kaia",
    1284: "moonbeam",
    1285: "moonriver",
    25: "cronos",
    1088: "metis",
    42220: "celo",
    1313161554: "aurora",
    1666600000: "harmony",
    42262: "oasis-emerald",
    199: "bittorrent",
    66: "okx-chain",
    128: "heco",
    321: "kcc",
    167000: "taiko",
    81457: "blast",
    34443: "mode",
    7777777: "zora",
    1116: "core",
    11155111: "sepolia",
    11155420: "optimism-sepolia",
    84532: "base-sepolia",
    421614: "arbitrum-sepolia",
    80002: "polygon-amoy",
    97: "bsc-testnet",
    17000: "holesky",
    43113: "avalanche-fuji",
    10200: "gnosis-chiado",
    1666700000: "harmony-testnet",
}

_PLACEHOLDER = re.compile(r"\$\{[^}]*\}")


def clean_rpcs(raw: list[str]) -> list[str]:
    """挑出可直接使用的公共 HTTPS RPC，去掉带占位符的付费端点和 ws://。"""
    out: list[str] = []
    for url in raw or []:
        if not isinstance(url, str):
            continue
        if not url.startswith("https://"):
            continue
        if _PLACEHOLDER.search(url):
            continue
        if url not in out:
            out.append(url)
        if len(out) >= MAX_RPC:
            break
    return out


def explorer_urls(entry: dict) -> tuple[str | None, str | None]:
    """从 chainlist 的 explorers 里取交易与地址前缀。"""
    for ex in entry.get("explorers") or []:
        base = (ex.get("url") or "").rstrip("/")
        if not base.startswith("https://"):
            continue
        tx = ex.get("txPage") or (base + "/tx/")
        addr = ex.get("addressPage") or (base + "/address/")
        return tx, addr
    return None, None


def unique_id(short_name: str, chain_id: int, used: set[str]) -> str:
    """id 必须稳定且唯一：优先用 shortName，冲突时追加 chainId。"""
    base = re.sub(r"[^a-z0-9-]+", "-", (short_name or "").lower()).strip("-")
    if not base:
        base = "chain"
    candidate = base
    n = 2
    while candidate in used:
        candidate = f"{base}-{chain_id}" if n == 2 else f"{base}-{n}"
        n += 1
    used.add(candidate)
    return candidate


def build_evm(entries: list[dict], used_ids: set[str]) -> list[dict]:
    by_id = {e["chainId"]: e for e in entries}
    chains: list[dict] = []

    for chain_id, is_testnet in (
        [(cid, False) for cid in MAINNET_CHAIN_IDS]
        + [(cid, True) for cid in TESTNET_CHAIN_IDS]
    ):
        entry = by_id.get(chain_id)
        if entry is None:
            print(f"  ! chainlist 中找不到 chainId={chain_id}，已跳过", file=sys.stderr)
            continue

        rpcs = clean_rpcs(entry.get("rpc") or [])
        if not rpcs:
            print(f"  ! chainId={chain_id} ({entry.get('name')}) 没有可用的公共 HTTPS RPC，已跳过",
                  file=sys.stderr)
            continue

        currency = entry.get("nativeCurrency") or {}
        tx_url, addr_url = explorer_urls(entry)
        features = {f.get("name") for f in entry.get("features") or []}

        # 优先用可读 id 覆盖；若与已有 id 冲突则回退到 shortName 派生
        chain_id_str = ID_OVERRIDES.get(chain_id)
        if chain_id_str is None or chain_id_str in used_ids:
            chain_id_str = unique_id(entry.get("shortName") or "", chain_id, used_ids)
        else:
            used_ids.add(chain_id_str)

        chains.append({
            "family": "evm",
            "id": chain_id_str,
            "name": entry.get("name") or f"Chain {chain_id}",
            "symbol": currency.get("symbol") or "ETH",
            "chainId": chain_id,
            # ⚠️ 强制 60，不能用 chainlist 的 slip44：
            # 例如 BNB 的 slip44 是 714，但所有 EVM 链的派生路径都固定 m/44'/60'/0'/0/i，
            # 这样同一助记词才能在全部 EVM 链上得到同一地址（「加链零代码」的前提）。
            # 这条不变量由 ChainConfigTest 明确守护。
            "coinType": 60,
            "decimals": currency.get("decimals", 18),
            "rpcUrls": rpcs,
            "explorerTxUrl": tx_url,
            "explorerAddressUrl": addr_url,
            "supportsEip1559": "EIP1559" in features,
            "testnet": is_testnet,
            "enabled": True,
        })

    return chains


def main() -> int:
    cache = Path(os.environ.get("CHAINLIST_CACHE", DEFAULT_CACHE))
    if not cache.exists():
        print(f"未找到 chainlist 缓存：{cache}")
        print(f"请先执行：curl -sL {CHAINLIST_URL} -o {cache}")
        return 1

    entries = json.loads(cache.read_text(encoding="utf-8"))
    print(f"chainlist 共 {len(entries)} 条链")

    existing = json.loads(OUTPUT.read_text(encoding="utf-8"))
    # 非 EVM 条目不归本脚本管，原样保留
    keep = [c for c in existing.get("chains", []) if c.get("family") != "evm"]
    used_ids = {c["id"] for c in keep}

    print("筛选 EVM 链……")
    evm = build_evm(entries, used_ids)
    print(f"  主网 {sum(1 for c in evm if not c['testnet'])} 条，测试网 {sum(1 for c in evm if c['testnet'])} 条")

    doc = {
        "version": existing.get("version", 1),
        "chains": keep + evm,
    }
    OUTPUT.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"已写入 {OUTPUT}（共 {len(doc['chains'])} 条链）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
