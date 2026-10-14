# LotusWallet · Android

去中心化多链钱包 + 加密信息管理 + OTP + DApp 市场，ALL-IN-ONE，**全本地 Serverless**。

> 本目录是新版工程（Kotlin + Jetpack Compose）。旧版 Java/MVPArms 代码在 `../android`，仅作参考，已停止演进。
> 架构设计与决策依据见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 特性

- **多链钱包**：第一版支持 BTC、ETH 及所有 EVM 链、TRON。新增 EVM 链仅需添加一条配置，无需改代码。
- **加密信息管理**：私钥/助记词、身份证件、银行卡、网站登录信息、自定义条目，全部本地加密存储。
- **OTP 认证器**：标准 TOTP / HOTP（SHA1/SHA256/SHA512），支持 `otpauth://` 扫码与导出。
- **DApp 市场**：只读远端清单，零后端。
- **无账号体系**：没有注册、登录、找回密码；设备丢失用助记词恢复。
- **自适应布局**：手机 / 折叠屏 / 平板 / 分屏全场景适配。

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 | Kotlin 2.0 |
| UI | Jetpack Compose + Material 3（Material You 动态取色） |
| 架构 | 多模块 + MVVM + 单向数据流 |
| DI | Hilt |
| 本地存储 | Room（密文）+ DataStore（偏好） |
| 网络 | OkHttp + kotlinx.serialization |
| 密码学 | BouncyCastle（secp256k1 / Keccak / Argon2id）+ 平台 `javax.crypto` |
| 构建 | Gradle 8.7 + AGP 8.6 + Version Catalog |

- `minSdk 26`（Android 8.0）、`targetSdk 35`、`compileSdk 35`、JDK 17。

## 安全模型（要点）

```
用户主密码 ──Argon2id（随机盐，内存硬化）──▶ KEK（永不落盘）
                                              │ AES-256-GCM 包裹
                                              ▼
随机 32 字节 ─────────────────────────▶ DEK ──▶ 加密所有条目
```

- KEK 从不落盘；拿到数据库文件但没有主密码无法解密。
- 改密码只需重新包裹 DEK，**不需要重新加密全部数据**。
- DEK 可被 Android Keystore 的硬件密钥（不可导出）再包一层，实现生物识别免密解锁。
- 所有数据加密使用 AES-256-GCM（随机 96-bit nonce + AAD），自带完整性校验。

## 目录结构

```
app/                    Compose UI、导航、DI 装配
core/
  common/               错误模型、结果类型、调度器、字节工具
  crypto/               哈希、编码、secp256k1、BIP39/32、AEAD、Argon2id、OTP
  database/             Room 实体与 DAO
  datastore/            DataStore 偏好
  network/              RPC / 索引 / 市场清单
  designsystem/         Material 3 主题与通用组件
chain/
  bitcoin/  evm/  tron/ 各链派生、地址、交易
docs/ARCHITECTURE.md    架构设计基线
```

## 构建

### 环境要求
- JDK 17
- Android SDK Platform 35 + Build Tools 35.0.0
- 无需 Android Studio（命令行可完成构建）

### 已配置的国内镜像（本机无 VPN，务必保留）

| 资源 | 镜像 |
|---|---|
| Maven 依赖 | `https://maven.aliyun.com/repository/public`（`settings.gradle.kts`） |
| Gradle 发行版 | `https://mirrors.cloud.tencent.com/gradle/`（`gradle/wrapper/gradle-wrapper.properties`） |
| JDK | `https://mirrors.tuna.tsinghua.edu.cn/Adoptium/` |
| Android SDK | `https://dl.google.com/android/repository/`（可直连） |

> ⚠️ `github.com` 在用户网络下不可直连。需要访问 GitHub 的操作（发布 release、clone 清单仓库）请攒批处理。

### 命令

```bash
# 单元测试（核心算法）
./gradlew :core:crypto:testDebugUnitTest

# 编译 Debug APK
./gradlew :app:assembleDebug

# 静态检查
./gradlew lint
```

若本机 `java` 未加入 PATH，可显式指定：

```bash
export JAVA_HOME="<jdk17 路径>"
export ANDROID_HOME="<android-sdk 路径>"
./gradlew :core:crypto:testDebugUnitTest
```

`local.properties` 中的 `sdk.dir` 指向 Android SDK 根目录。

## 开发约定

- 新增 EVM 链：改 `chains.json`，不改业务代码。见架构文档第 3 节。
- 新增 UI：必须同时验证 `Compact` / `Medium` / `Expanded` 三种宽度窗口。
- 禁止在 UI 层直接使用底层密码学类；一律经由 `core` 层。
- 禁止在任何日志、崩溃上报中输出私钥、助记词、OTP 密钥或明文保险库内容。
- 所有核心算法变更必须补充或更新测试向量。

## 许可

待定（正式对外公开前确定）。
