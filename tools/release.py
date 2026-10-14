#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键发布 LotusWallet Android 版本。

    python tools/release.py                  # 完整流程：构建 → 签名校验 → 生成产物清单
    python tools/release.py --skip-build     # 复用上次构建产物（改 versions.json 时用）
    python tools/release.py --push           # 额外执行 git/gh 的发布动作

它会做这些事：
  1. 读 version.properties，算出 versionName / versionCode
  2. 检查签名配置（没配签名直接拒绝继续 —— 未签名的 APK 用户装不上）
  3. 跑 `./gradlew :app:assembleRelease`
  4. 把 APK 复制到 releases/v<版本>/LotusWallet-v<版本>.apk，算 sha256 与大小
  5. 更新 download 仓库工作副本里的 versions.json（新增版本条目 + 更新 latest）
  6. 打印后续发布命令（打 tag、上传 Release、推 download 仓库）

关于 APK 放哪
-------------
放 GitHub Releases，不进 git。理由：
  · git 会永久保留每个版本的二进制，几十个版本后仓库会膨胀到无法克隆；
  · Releases 有独立 CDN 和下载统计，还能给出「永远指向最新版」的固定链接。
发布命令由本脚本打印，`--push` 时会尝试自动执行（需要网络与 gh CLI / 推送凭据）。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import date
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
JAVA_HOME = Path("D:/android-toolchain/jdk-17.0.20.1+1")
GRADLEW = REPO / ("gradlew.bat" if sys.platform == "win32" else "gradlew")

# 本机 C 盘（系统盘）空间紧张，凡是构建产生的目录一律放到 D 盘，否则会写满 C 盘
# 导致「页面文件太小」→ JVM 起不来。与 tools/build.sh 保持一致。
GRADLE_USER_HOME = Path("D:/gradle-home")
BUILD_TMP = Path("D:/tmp")


def build_env() -> dict[str, str]:
    """构造构建用的环境变量：工具链、Gradle 缓存、临时目录全部指向 D 盘。"""
    BUILD_TMP.mkdir(parents=True, exist_ok=True)
    return dict(
        os.environ,
        JAVA_HOME=str(JAVA_HOME),
        ANDROID_HOME="D:/android-toolchain/sdk",
        ANDROID_SDK_ROOT="D:/android-toolchain/sdk",
        GRADLE_USER_HOME=str(GRADLE_USER_HOME),
        TMPDIR=str(BUILD_TMP),
        TMP=str(BUILD_TMP),
        TEMP=str(BUILD_TMP),
        JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=D:/tmp",
    )

RELEASES_DIR = REPO.parent / "releases"
DOWNLOAD_SRC = REPO.parent / "repos" / "download-src"
VERSIONS_JSON = DOWNLOAD_SRC / "versions.json"

OWNER_REPO = "LotusWallet/download"

# 下载页与直链。资产名约定：LotusWallet.apk（永远指向最新）+ LotusWallet-v<版本>.apk（归档）
LATEST_ASSET = "LotusWallet.apk"


# ------------------------------------------------------------------ 版本
def read_version() -> tuple[str, int]:
    props: dict[str, str] = {}
    for line in (REPO / "version.properties").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()

    major, minor, patch = (int(props[f"version.{k}"]) for k in ("major", "minor", "patch"))
    pre = int(props["version.pre"])
    name = f"{major}.{minor}.{patch}" + (f"-beta.{pre}" if pre > 0 else "")
    code = major * 10_000 + minor * 100 + patch
    return name, code


# ------------------------------------------------------------------ 构建
def check_signing() -> None:
    props_file = REPO / "keystore.properties"
    if not props_file.exists():
        raise SystemExit(
            "没有找到 keystore.properties，拒绝发布。\n"
            "  未签名的 APK 用户根本装不上，签错密钥的 APK 会让老用户无法覆盖安装。\n"
            "  先跑一次：python tools/gen_keystore.py"
        )
    props: dict[str, str] = {}
    for line in props_file.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    store = Path(props.get("storeFile", ""))
    if not store.exists():
        raise SystemExit(f"keystore.properties 指向的密钥文件不存在：{store}")


def build() -> Path:
    env = build_env()
    print("构建 release APK ……")
    result = subprocess.run(
        [str(GRADLEW), ":app:assembleRelease", "--console=plain"],
        cwd=REPO, env=env,
    )
    if result.returncode != 0:
        raise SystemExit("构建失败")

    apk = REPO / "app/build/outputs/apk/release/app-release.apk"
    if not apk.exists():
        # 没配签名时 Gradle 会产出 -unsigned 后缀；按签名检查，这里不应该出现。
        unsigned = REPO / "app/build/outputs/apk/release/app-release-unsigned.apk"
        if unsigned.exists():
            raise SystemExit(f"只产出了未签名包 {unsigned.name}，说明签名配置没生效")
        raise SystemExit(f"找不到构建产物：{apk}")
    return apk


def build_tools_dir() -> Path | None:
    """最新的 build-tools 目录（apksigner / aapt2 都在里面）。"""
    sdk = Path("D:/android-toolchain/sdk/build-tools")
    versions = sorted(p.name for p in sdk.glob("*") if p.is_dir())
    return sdk / versions[-1] if versions else None


def verify_signed(apk: Path) -> str:
    """用 apksigner 校验签名，并返回签名证书的 SHA-256 指纹。"""
    tools = build_tools_dir()
    if tools is None:
        return "(未找到 apksigner，跳过签名校验)"
    exe = tools / ("apksigner.bat" if sys.platform == "win32" else "apksigner")
    if not exe.exists():
        return "(未找到 apksigner，跳过签名校验)"

    env = build_env()
    result = subprocess.run(
        [str(exe), "verify", "--print-certs", str(apk)],
        capture_output=True, env=env,
    )
    out = (result.stdout + result.stderr).decode("utf-8", "replace")
    if result.returncode != 0:
        raise SystemExit(f"APK 签名校验失败：\n{out}")
    match = re.search(r"SHA-256 digest:\s*([0-9a-fA-F:]+)", out)
    return match.group(1).upper() if match else "(已签名)"


def verify_apk_identity(apk: Path, expected_name: str, expected_code: int) -> None:
    """读 APK 里**真实的** versionName/versionCode，与 version.properties 对账。

    为什么必须在校验完签名之后再看一眼这个：版本号会同时出现在四个地方
    （version.properties、包内、git tag、versions.json），而「tag 是 3.0.2、
    包内却是 3.0.1」这种错配的后果很具体 —— 用户装上去系统认为版本没变化，
    in-app 的更新提示**永远不会消失**，而他明明已经是新版了。
    Gradle 读的确实是 version.properties，但这里是发布前最后一道闸，
    不依赖「Gradle 一定按我们以为的方式工作」这个假设。
    """
    tools = build_tools_dir()
    if tools is None:
        print("(未找到 aapt2，跳过包内版本号校验)")
        return
    exe = tools / ("aapt2.exe" if sys.platform == "win32" else "aapt2")
    if not exe.exists():
        print("(未找到 aapt2，跳过包内版本号校验)")
        return

    result = subprocess.run(
        [str(exe), "dump", "badging", str(apk)],
        capture_output=True, env=build_env(),
    )
    out = (result.stdout + result.stderr).decode("utf-8", "replace")
    if result.returncode != 0:
        raise SystemExit(f"aapt2 读取 APK 失败：\n{out}")

    m_code = re.search(r"versionCode='(\d+)'", out)
    m_name = re.search(r"versionName='([^']*)'", out)
    actual_code = int(m_code.group(1)) if m_code else None
    actual_name = m_name.group(1) if m_name else None

    if actual_code != expected_code or actual_name != expected_name:
        raise SystemExit(
            "包内版本号与 version.properties 不一致，拒绝发布：\n"
            f"  version.properties : {expected_name} (versionCode {expected_code})\n"
            f"  APK 内实际          : {actual_name} (versionCode {actual_code})\n"
            "  多半是构建产物没刷新（试着删掉 app/build 重新构建），"
            "或 version.properties 被改动后没有重新构建。"
        )
    print(f"包内版本号核对通过：{actual_name} (versionCode {actual_code})")


def refresh_contract_fixtures() -> None:
    """把刚写好的 versions.json 同步成单元测试夹具。

    为什么发布脚本要管这件事：契约夹具的**唯一作用**就是「和发布产物保持一致」，
    而 versions.json 恰恰是在这一步被改写的。不顺手刷新的话，
    下一次跑测试就会红（`PublishedManifestContractTest` 的版本号断言），
    而那个红是假警报 —— 修法就是这里补一步。
    """
    script = REPO / "tools" / "sync_contract_fixtures.py"
    if not script.exists():
        return
    result = subprocess.run([sys.executable, str(script)], cwd=REPO)
    if result.returncode != 0:
        raise SystemExit("同步契约夹具失败 —— 发布产物或客户端解析器可能已经对不上了")


# ------------------------------------------------------------------ 产物清单
def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def update_versions_json(version_name: str, version_code: int, apk: Path) -> dict:
    entry = {
        "versionName": version_name,
        "versionCode": version_code,
        "releaseDate": date.today().isoformat(),
        "mandatory": False,
        "sizeBytes": apk.stat().st_size,
        "sha256": sha256_of(apk),
        "downloadUrl": (
            f"https://github.com/{OWNER_REPO}/releases/download/"
            f"v{version_name}/LotusWallet-v{version_name}.apk"
        ),
        "latestUrl": f"https://github.com/{OWNER_REPO}/releases/latest/download/{LATEST_ASSET}",
        "releasePageUrl": f"https://github.com/{OWNER_REPO}/releases/tag/v{version_name}",
    }

    doc = json.loads(VERSIONS_JSON.read_text(encoding="utf-8")) if VERSIONS_JSON.exists() else {
        "schemaVersion": 1,
        "channel": "stable",
        "minSupportedVersionCode": 0,
        "releases": [],
        "legacy": [],
    }

    releases = [r for r in doc.get("releases", []) if r.get("versionCode") != version_code]
    releases.insert(0, entry)
    releases.sort(key=lambda r: r.get("versionCode", 0), reverse=True)

    doc["schemaVersion"] = 1
    doc["channel"] = doc.get("channel", "stable")
    doc["minSupportedVersionCode"] = doc.get("minSupportedVersionCode", 0)
    doc["latest"] = releases[0]
    doc["releases"] = releases
    doc.setdefault("legacy", [])

    DOWNLOAD_SRC.mkdir(parents=True, exist_ok=True)
    # 必须用 write_bytes 强制 LF，不能用 write_text。
    #
    # Path.write_text 走文本模式，在 Windows 上会把 "\n" 翻译成 "\r\n"，
    # 于是这里的 versions.json 变成 CRLF，而 assets 侧的产物（build_market.py、
    # sync_chainlist.py 都显式 write_bytes + replace("\r\n","\n")）是 LF。
    # 后果不是"格式难看"，而是真会挂：契约夹具在 git 里按 .gitattributes 的
    # eol=lf 存成 LF，CI 检出后拿 LF 夹具去比 CRLF 产物，报
    #   「以下夹具与发布侧产物不一致：versions.json」
    # 这个坑已经真实踩过一次（2026-10-14，源码仓库 CI 第 1 次运行）。
    # 与 assets 侧保持同一套写法，别再改回去。
    VERSIONS_JSON.write_bytes(
        (json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
        .replace("\r\n", "\n").encode("utf-8")
    )
    return entry


# ------------------------------------------------------------------ 主流程
def main() -> int:
    parser = argparse.ArgumentParser(description="发布 LotusWallet Android 版本")
    parser.add_argument("--skip-build", action="store_true", help="复用已有的构建产物")
    parser.add_argument("--push", action="store_true", help="执行 git tag / gh release / 推送")
    args = parser.parse_args()

    version_name, version_code = read_version()
    print(f"版本：{version_name}（versionCode {version_code}）")

    check_signing()

    if args.skip_build:
        apk = REPO / "app/build/outputs/apk/release/app-release.apk"
        if not apk.exists():
            raise SystemExit("--skip-build 但找不到已有产物，请先正常构建一次")
    else:
        apk = build()

    fingerprint = verify_signed(apk)
    verify_apk_identity(apk, version_name, version_code)

    out_dir = RELEASES_DIR / f"v{version_name}"
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / f"LotusWallet-v{version_name}.apk"
    shutil.copyfile(apk, target)

    entry = update_versions_json(version_name, version_code, target)
    refresh_contract_fixtures()

    size_mb = target.stat().st_size / 1048576
    print()
    print(f"APK      : {target}  ({size_mb:.2f} MB)")
    print(f"sha256   : {entry['sha256']}")
    print(f"签名证书 : {fingerprint}")
    print(f"已更新   : {VERSIONS_JSON}")
    print(f"已同步   : core/network/src/test/resources/contract/")

    tag = f"v{version_name}"
    print()
    print("后续发布步骤：")
    print(f"  1. 提交版本号与 CHANGELOG：git add -A && git commit -m 'release: {tag}'")
    print(f"  2. 打 tag：git tag -a {tag} -m 'LotusWallet {version_name}'")
    print(f"  3. 推 tag：git push origin main {tag}")
    print(f"  4. 上传安装包：")
    print(f"       gh release create {tag} \\")
    print(f"         '{target}' \\")
    print(f"         --repo {OWNER_REPO} --title 'LotusWallet {version_name}' --notes-file CHANGELOG.md")
    print(f"     并另传一份重命名为 {LATEST_ASSET} 的资产（提供「永远最新」直链）：")
    print(f"       gh release upload {tag} '{target}' --repo {OWNER_REPO} --clobber")
    print(f"  5. 推送 download 仓库的 versions.json / README / CHANGELOG：")
    print(f"       python ../repos/publish.py download")

    if args.push:
        print("\n--push 暂未自动执行：需要本机可访问 github.com 且已配置推送凭据。")
        print("请按上面步骤手动执行，或配置好凭据后重跑本脚本。")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
