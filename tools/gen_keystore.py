#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 LotusWallet 的发布签名密钥（release keystore）。

只在第一次发布前跑一次。之后**永远不要重新生成** ——
Android 用签名证书判断「这个 APK 是不是同一个应用的新版本」，
换密钥 = 所有老用户无法覆盖安装（只能卸载重装，而卸载会清掉本地数据）。

    python tools/gen_keystore.py

产物（都在仓库之外，绝不进 git）：
    D:/work/LotusWallet/keys/lotuswallet-release.jks   私钥，务必离线备份
    <仓库>/keystore.properties                         密码，已在 .gitignore 里

⚠️ 备份要求：把 .jks 与 keystore.properties **一起**备份到至少两个离线位置。
   两者缺一不可，丢了就无法再发布「能覆盖安装」的新版本。
"""

from __future__ import annotations

import secrets
import string
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
KEYSTORE_DIR = REPO.parent / "keys"
KEYSTORE = KEYSTORE_DIR / "lotuswallet-release.jks"
PROPS = REPO / "keystore.properties"

ALIAS = "lotuswallet"
# 30 年：Android 只要求证书在 App 预期生命周期内有效，但钱包类应用寿命长，
# 给足余量，省得十年后被迫换密钥。
VALIDITY_DAYS = 10950
JAVA_HOME = Path("D:/android-toolchain/jdk-17.0.20.1+1")


def random_password(length: int = 32) -> str:
    # 只用字母数字：避免 shell / properties / CI 各种转义坑。
    alphabet = string.ascii_letters + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(length))


def main() -> int:
    keytool = JAVA_HOME / "bin" / ("keytool.exe" if sys.platform == "win32" else "keytool")
    if not keytool.exists():
        print(f"找不到 keytool：{keytool}", file=sys.stderr)
        return 1

    if KEYSTORE.exists() or PROPS.exists():
        print("已存在签名密钥，拒绝覆盖：")
        for p in (KEYSTORE, PROPS):
            if p.exists():
                print(f"  {p}")
        print("如果确实要换密钥（会让所有老用户无法覆盖安装），请先手动删除这两个文件。")
        return 1

    KEYSTORE_DIR.mkdir(parents=True, exist_ok=True)

    store_pass = random_password()
    # keyPass 与 storePass 相同：JDK 之后的 PKCS12 格式本就不支持两者不同。
    cmd = [
        str(keytool), "-genkeypair", "-v",
        "-keystore", str(KEYSTORE),
        "-storetype", "PKCS12",
        "-alias", ALIAS,
        "-keyalg", "RSA", "-keysize", "4096",
        "-sigalg", "SHA256withRSA",
        "-validity", str(VALIDITY_DAYS),
        "-storepass", store_pass,
        "-keypass", store_pass,
        "-dname", "CN=LotusWallet, OU=Android, O=LotusWallet, C=CN",
    ]
    # keytool 在中文 Windows 上按 GBK 输出，直接按 UTF-8 解会炸；这里只要退出码，
    # 输出统一按 errors="replace" 解，绝不因为日志编码问题让发布密钥的生成半途而废。
    result = subprocess.run(cmd, capture_output=True)
    if result.returncode != 0:
        print("keytool 失败：", file=sys.stderr)
        print(result.stdout.decode("utf-8", "replace"), file=sys.stderr)
        print(result.stderr.decode("utf-8", "replace"), file=sys.stderr)
        return 1

    PROPS.write_text(
        "# 发布签名配置 —— 由 tools/gen_keystore.py 生成\n"
        "# 本文件已在 .gitignore 中，切勿提交。与 .jks 一起离线备份。\n"
        f"storeFile={KEYSTORE.as_posix()}\n"
        f"storePassword={store_pass}\n"
        f"keyAlias={ALIAS}\n"
        f"keyPassword={store_pass}\n",
        encoding="utf-8",
    )

    print(f"已生成密钥：{KEYSTORE}")
    print(f"已写入配置：{PROPS}")
    print(f"密码长度 {len(store_pass)} 位（字母数字），已存进上面的配置文件，未在此打印。")
    print()
    print("⚠️ 现在就去备份这两个文件到至少两个离线位置。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
