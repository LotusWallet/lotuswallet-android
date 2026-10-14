# LotusWallet Android 架构设计

> 本文件是新版 `lotuswallet-android` 的设计基线。旧版（`../android`，Java + MVPArms + 服务端）仅作参考，不再演进。

## 1. 目标与非目标

### 目标
1. **Serverless**：不依赖任何自建后端。用户数据只落本机磁盘，永不上传。
2. **保留两块核心能力**：加密信息管理（私钥/助记词、证件、银行卡、网站登录、自定义）、OTP（TOTP/HOTP）。
3. **多链**：第一版支持 BTC、ETH 及所有 EVM 链、TRON。
4. **加链零代码**：新增 EVM 链只加一条配置。
5. **现代 UX**：Material 3、自适应布局，完整支持大屏 / 折叠屏 / 平板。
6. **可验证**：核心算法必须有标准测试向量覆盖。

### 非目标（本阶段明确不做）
- 不涉及合规/牌照相关功能；短期不对外公开运营。
- 不做 iOS、不做桌面端。
- 不保留旧版的服务端账号体系、火密盾 NFC 硬件、腾讯 X5 WebView、DApp 内嵌浏览器竞价体系。
- 不做云端同步、不做中心化行情推送。

## 2. 模块划分与依赖方向

依赖只能自上而下，禁止反向依赖与跨层引用。

```
app  ──────────────────────────────────────────────┐
 │  (Compose UI / 导航 / DI 装配 / 生物识别)        │
 ├──▶ feature 层（本阶段直接以 app 内分包实现）      │
 │      钱包 · 保险库 · OTP · 市场 · 设置            │
 ├──▶ chain 层                                      │
 │      chain:bitcoin  chain:evm  chain:tron        │
 │      chain:alt（Solana / ICP / Monero /          │
 │                 Filecoin / Chia）                │
 ├──▶ core 层                                       │
 │      core:common  core:crypto  core:database     │
 │      core:datastore  core:network                │
 │      core:designsystem                           │
 └──────────────────────────────────────────────────┘
```

| 模块 | 职责 | 关键约束 |
|---|---|---|
| `core:common` | 错误模型、结果类型、调度器、字节工具 | 不依赖任何业务概念 |
| `core:crypto` | 哈希、Base58/32/Bech32/Bech32m、secp256k1、ed25519（含裸标量乘法）、BIP39/32、SLIP-0010、AEAD、Argon2id、OTP、密码生成器 | **不依赖 Android 之外的任何模块**；除 `AndroidKeyStoreGate` 外全部可在 JVM 上单测 |
| `core:database` | Room 实体与 DAO（钱包、账户、保险库条目、OTP 条目） | 只存密文与元数据 |
| `core:datastore` | 偏好设置（语言、主题、解锁方式、链/市场缓存与时间戳、用户自定义链） | 不存秘密；只存公开清单 |
| `core:network` | 公开 RPC / 索引服务 / 市场清单拉取、`ChainConfig` 与三层合并 | 无鉴权、无用户标识 |
| `chain:bitcoin` `chain:evm` `chain:tron` | UTXO / EVM / TRON 的派生路径、地址编码、交易构建与签名 | 只依赖 `core:crypto`、`core:network` |
| `chain:alt` | ed25519 系（Solana/ICP/Monero）与 Filecoin、Chia 的地址派生与校验 | 只做**地址**，不含交易签名 |
| `app` | UI、导航、依赖注入装配 | 不直接调用底层库 |

## 3. 加链 = 加一条配置（声明式链配置）

**问题**：旧版每新增一条链都要改 UI、数据层、常量、图标、白名单等多处代码。

**方案**：把「链」建模为纯数据。`core:network/chain/ChainConfig.kt` 是一个密封类，
序列化判别字段为 `family`，六个家族各有专属参数，公共参数抽成 `CommonFields`：

```kotlin
@Serializable
sealed class ChainConfig {
    val id: String                  // 稳定标识，用户覆盖与 DApp 清单都按它引用
    val name: String
    val symbol: String
    val decimals: Int
    val coinType: Int               // SLIP-0044，决定派生路径里的 44'/x'
    val rpcUrls: List<String>       // **多个节点**，按顺序做失败转移
    val explorerTxUrl: String?
    val testnet: Boolean
    val enabled: Boolean
    val family: Family
}

enum class Family { EVM, UTXO, TRON, ED25519, FILECOIN, CHIA }
```

| 家族 | 专属参数 | 派生 |
|---|---|---|
| `EVM` | `chainId`、`supportsEip1559` | `m/44'/60'/0'/0/i`（secp256k1） |
| `UTXO` | `addressType`（legacy/p2sh/bech32/bech32m）、`hrp`、`versionByte`、`dustThreshold` | secp256k1 |
| `TRON` | — | secp256k1 |
| `ED25519` | `variant`（SOLANA/ICP/MONERO）、`derivation`（SLIP10/BIP32）、`path`、`addressPrefix` | SLIP-0010 或 BIP-32 |
| `FILECOIN` | `path`、`network`（`f`/`t`） | BIP-32（secp256k1，负载用**未压缩**公钥的 BLAKE2b-160） |
| `CHIA` | `path`、`hrp` | **本版本不派生**，只做 `xch1…` 的 bech32m 编解码 |

新增一条链 = **在 `chains.json` 里加一段 JSON**，不触碰任何 Kotlin 业务代码：

```json
{ "family": "evm", "id": "arbitrum", "name": "Arbitrum One", "symbol": "ETH",
  "decimals": 18, "coinType": 60, "chainId": 42161,
  "rpcUrls": ["https://arb1.arbitrum.io/rpc", "https://arbitrum.llamarpc.com"] }
```

配套机制：

- **三层清单覆盖**：内置 `/chains.json`（随 APK，离线可用）
  < 远端清单（GitHub raw + jsDelivr 镜像，可覆盖同 id、也可新增）
  < **用户自定义**（本机 DataStore，来自「链管理」）。
  任意一层缺失或损坏都**静默降级**到下一层 —— 这是 serverless 的前提。
  合并用 `LinkedHashMap` 保持首次出现的相对顺序，因此内置链排列稳定、新链追加在末尾。
- **用户可以改链**：chainlist 转存的覆盖面与节点质量都不完整（不少小链只有一两个公共节点），
  把「加一条链 / 换一个 RPC」变成一次本机编辑，比等仓库合并快得多。
  覆盖只存本机、不上传不同步；改错了用「恢复默认」即可回到官方配置，
  也避免了「谁都能改公共清单」的供应链风险。
- **停用 vs 删除**（`ChainEditor`）：删内置链只能写一条 `enabled=false` 的覆盖
  （下次刷新远端它还会回来），删自建链才是真删。UI 提示文案也随之区分。
- **代币模型标准化**：`TokenEntity(chainId, contract, symbol, decimals, standard)`，
  `standard ∈ {ERC20, ERC721, ERC1155}`，余额查询走同一套 `eth_call`。
- **未接入节点的链显示「—」而不是 0**：`Balance.unavailable(note = …)`。
  把「查不到」画成「没有钱」是误导用户，比不显示更糟。
- **配置来源优先级**（同上文三层）；远端拉取失败时静默降级为内置配置，不影响可用性。

> 验收标准：给出任意一条 EVM 公链的 chainId / RPC / 符号，能在 **5 分钟内**通过新增配置接入并完成转账，期间零 Kotlin 改动。

## 4. 大屏 / 折叠屏适配（对应需求点 3）

**旧版问题**：依赖 `AutoSize` + `design_width_in_dp = 360 / height = 640`，把 UI 按固定设计稿等比拉伸。这套方案在平板、折叠屏展开态、横屏下会放大变形，是 2022 年前的做法。

**方案**：Compose 原生自适应。

| 宽度窗口 | 布局策略 |
|---|---|
| `Compact`（手机竖屏 < 600dp） | 单列 + 底部导航栏 |
| `Medium`（折叠屏展开 / 小平板 600–840dp） | 双列网格；主导航改为 `NavigationRail` |
| `Expanded`（平板 / 桌面模式 > 840dp） | 列表-详情双面板（左侧导航 + 中间列表 + 右侧详情） |

具体约定：
- 用 `androidx.compose.material3.windowsizeclass` 计算 `WindowSizeClass`，在 `MainActivity` 顶层分发。
- 内容区限制最大宽度（`widthIn(max = 640.dp)` 居中），避免大屏上文字行长过宽。
- 启用 **edge-to-edge**，正确处理手势导航条与 3 键导航的 inset。
- 支持 **多窗口 / 分屏 / 自由窗口**：所有页面在任意窗口尺寸下不得崩溃（`resizeableActivity=true`，不使用强制固定方向）。
- 折叠屏铰链：使用 `WindowLayoutInfo`（`androidx.window`）识别铰链位置，避免把内容放在铰链下方。
- 所有尺寸用 `dp` + `sp`，禁止像素硬编码；字体随系统字号缩放（不做 `fontScale` 锁定）。
- 状态栏/导航栏主题跟随 Material 3 动态取色（Android 12+）。

> 已废弃：`AutoSize`、`design_width_in_dp`。旧版 `autosize` 模块整体不迁移。

## 5. 数据流与 Serverless 边界

```
┌─────────────── 设备本地（唯一可信区） ───────────────┐
│  用户主密码 ─Argon2id─▶ KEK ─包裹─▶ DEK ─加密─▶ 密文 │
│                                    │                 │
│  Room（密文）+ DataStore（非敏感偏好）                │
│  Android Keystore（生物识别解封 DEK，密钥不可导出）    │
└──────────────────────────────────────────────────────┘
                    │
                    │ 仅出站、只读、无用户标识
                    ▼
┌─────────────── 外部公开服务 ───────────────┐
│  公共 RPC 节点（余额、广播交易、日志）       │
│  Esplora（BTC 系 UTXO 查询）                │
│  GitHub raw / jsDelivr（三份只读 JSON 清单） │
│    · assets/chains/chains.json   链清单      │
│    · assets/market/dapps.json    DApp 市场   │
│    · download/versions.json      版本清单    │
└────────────────────────────────────────────┘
```

- **没有登录、没有注册、没有账户恢复服务**。设备丢失 = 用助记词在新设备恢复。
- 备份 = 用户自行导出加密文件（含 DEK 信封 + 密文库），我们不经手。
- 所有出站请求不携带任何可识别用户的标识；不同链的 RPC 请求互不关联。
- 三份清单都给出**两个等价地址并按顺序失败转移**：`raw.githubusercontent.com`（官方源，内容最新）
  优先，不可达时退到 `cdn.jsdelivr.net`（国内可达性通常更好，代价是约 12 小时缓存）。
  这些是低频变动的数据，缓存足够。
- 拉不到清单时**静默降级**：链清单退回随包内置的那份，市场清单退回内置精选，
  版本清单则视为「已是最新」。断网不应该是钱包不可用的理由。

## 6. 安全模型

| 层 | 措施 | 对应旧版问题 |
|---|---|---|
| 口令 → 密钥 | Argon2id（内存硬化，参数可升级） | 旧版 PBKDF2-SHA1 / 1000 轮 / 固定盐 |
| 密钥来源 | 本地随机 DEK，主密码包裹 | 旧版密钥派生自**服务端 token**，后端一停数据全废 |
| 数据加密 | AES-256-GCM，每次随机 96-bit nonce，带 AAD | 旧版 CBC 且 **IV == 密钥**，无完整性保护 |
| 密钥落盘 | 仅存包裹后的 DEK；KEK 永不落盘 | 旧版 pblkey 明文存 SharedPreferences |
| 免密解锁 | Android Keystore 硬件密钥 + 生物识别真正参与解封 | 旧版指纹只是「登录开关」，属假安全 |
| 剪贴板 | 复制私钥/助记词后自动清除，并标记敏感内容 | 旧版无处理 |
| 截屏 | 敏感页面 `FLAG_SECURE` | 旧版无处理 |
| 签名 | RFC 6979 确定性 nonce + low-S 规范化 | 旧版依赖 `SecureRandom`，有 nonce 复用风险 |
| 密钥材料生命周期 | 用后立即清零数组 | 旧版无处理 |

## 7. 旧 → 新 功能映射

| 旧版 | 新版 | 说明 |
|---|---|---|
| `WalletEntity`（LitePal） | Room `WalletEntity` + `AccountEntity` | 拆分「钱包（助记词）」与「账户（派生地址）」 |
| `SecInfoEntity` / `SecInfoRecordEntity` | Room `VaultItemEntity` + `VaultFieldEntity` | **必须保留**，结构化改写以支持自定义字段 |
| `TwostepVerificationEntity` | Room `OtpEntryEntity` | **必须保留** |
| `util/crypto/GoogleAuth` | `core:crypto/OtpEngine` | 修正固定种子、补齐 SHA256/512、新增 otpauth URI |
| `util/crypto/AESUtil`/`PwEncryUtil`/`PBKDF2Util` | `Aead` + `KeyDerivation` + `VaultCrypto` | 完整重做 |
| `util/crypto/HdUtil`/`Hash` | `core:crypto/Hash` + `Bip32` | 完整重做，含测试向量 |
| `wallet/manager/*`（7 个链） | `chain:bitcoin` `chain:evm` `chain:tron` | 第一版收缩到 3 个模块 |
| `util/pwgenerator/PasswordGenerator` | `core:crypto/PasswordGenerator` | 改用 SecureRandom |
| 5 个主 Tab | 同样 5 个主 Tab | 钱包 / 保险库 / 市场 / OTP / 我的 |
| 邮箱注册登录 | **删除** | 无账号体系 |
| `c/could/up|down` 云备份 | 本地加密文件导出/导入 | 用户自持 |
| `dapp/d/*` 市场接口 | GitHub raw JSON 清单 | 只读、可缓存、可离线 |
| `nfc` 模块 + 火密盾 | **删除** | 依赖已停产的自研硬件 |
| `autosize` 模块 | **删除** | 被 WindowSizeClass 取代 |
| 腾讯 X5 WebView | 系统 WebView | 减少体积与隐私面 |

## 8. 构建与网络约束

本机（用户环境）**无 VPN**，外网受限，因此：

- Maven 依赖：`settings.gradle.kts` 已配置 **阿里云镜像优先**（`maven.aliyun.com`），回退 google / mavenCentral。
- Gradle 发行版：`gradle-wrapper.properties` 指向 **腾讯云镜像**（`mirrors.cloud.tencent.com/gradle/`）。
- JDK：从 **清华 Adoptium 镜像** 获取（`mirrors.tuna.tsinghua.edu.cn`）。
- Android SDK：`dl.google.com` 可直连，无需代理。
- ⚠️ `github.com` 与 `objects.githubusercontent.com` **不可直连**。因此所有需要 GitHub 的操作（拉取远端清单调试、发布 release、把市场清单仓库 clone 下来）必须**攒批**，在用户开启 VPN 时统一执行。

## 9. 远端清单的契约与验证

上面那三份 JSON 是**跨仓库契约**：内容在 `LotusWallet/assets`、`LotusWallet/download` 生成，
解析却在这里。两侧字段对不上的后果全是**静默失效** —— 链拉不到、市场空白、
永远显示「已是最新」—— 而真机上几乎不可能复现。所以给这条边界专门加了三道闸。

### 9.1 网络层抽成接口

`RestClient` 是接口，实现只有 `OkHttpRestClient`（应用里唯一真正出网的地方）。
抽接口不是为了「以后可能换实现」，而是为了让链清单 / 市场清单 / 版本清单
**三个仓库的处理逻辑可以在纯内存里被测试**：多端点失败转移、远端损坏时保留缓存、
「force 刷新必须先联网而不能被本地持久化清单短路」等分支，全部有覆盖。
多端点失败转移写在接口的默认方法里，假实现只覆盖单地址 GET 就能继承同样的语义。

### 9.2 跨仓库契约测试

`core/network/src/test/resources/contract/` 下是发布侧**实际产物**的快照，
由 `tools/sync_contract_fixtures.py` 从两个仓库同步；
`PublishedManifestContractTest` 用客户端**真正的解析器**去读它们，并校验：

| 检查 | 为什么会伤到用户 |
|---|---|
| 全部链能解析、id 唯一 | id 是数据库外键，重复会让两条链的数据串在一起 |
| EVM `chainId` 唯一 | 重复意味着同一笔签名可在两条链上重放 |
| EVM `coinType` 恒为 60 | 漂了则同一助记词在不同 EVM 链上派生出不同地址 |
| UTXO `hrp` / `p2pvVersion` / `addressType` 自洽 | 漂了则所有 BTC 地址都变 |
| 非 EVM 新链族的必填字段齐全 | 少一个 `path` / `variant` / `addressPrefix` 就会派生出**别人无法解锁**的地址 |
| SLIP-0010 路径全硬化 | 非硬化段在 ed25519 上无法推导，写了等于无效配置 |
| Monero `addressPrefix == 18` | 主网前缀漂了，地址在官方钱包里会被判为无效 |
| 内置与远端对同一条链的 `decimals`/`coinType`/`chainId` 一致 | 否则会出现「联网余额正常、断网余额变了」 |
| 市场清单的 `category` 必须在 `categories` 里、URL 必须 https | 否则分类页空白或跳出明文地址 |
| 市场清单 id 唯一（含团队精选清单） | 按 id 去重是**静默**的：重复条目会让 DApp 凭空少一条且不报错 |
| `versionCode == major*10000+minor*100+patch` | 规则漂了则覆盖安装判定失效 |
| `minSupportedVersionCode <= latest.versionCode` | 否则会把所有用户逼进永远无法满足的强制更新 |

`--check` 模式（提交发布产物前必须跑）会在夹具与产物不一致时直接失败；
`release.py` 在更新 `versions.json` 之后会自动刷新夹具，所以这一步不需要人记着做。

### 9.4 地址派生的可验证性

加密原语这一类东西**错了不会抛异常**，只会安静地生成一批「格式合法但没人能解锁」的地址。
因此新链的地址派生一律用**官方测试向量**钉住，而不是做「两次调用结果一致」这种自洽性检查：

| 向量 | 锁住什么 |
|---|---|
| BIP-32 Test vector 1 的 4 个 `xprv` 串 | `xprv` 串同时编码私钥、链码、深度、父指纹、子编号 —— 任何一环写错都串不出来。硬化与非硬化都做 `k_i = I_L + k_parent (mod n)` 这一点最容易错 |
| RFC 8032 ed25519 向量 | ed25519 公钥推导（SHA-512 → clamp → 标量乘基点） |
| SLIP-0010 ed25519 Test vector | HMAC key 必须是 `"ed25519 seed"`（不是 `"Bitcoin seed"`），且 ed25519 的子密钥**只取 `I_L`、不加父密钥** |

生成这些期望值时踩到的坑记录下来：第一版向量生成脚本漏了「加父密钥」，
又把 `m/44'/223'/0'/0/0` 的末两级当硬化处理，于是 ICP / Filecoin 的期望值是错的，
反而误判成实现有问题。**结论：先让向量生成器自己通过官方向量，再拿它去核对实现。**
生成脚本已固化在 `tools/gen_chain_vectors.py`（本机无外网，无法在线核对，故这一步必须本地自证）；
它跑不通就直接断言退出，不会输出可疑的期望值。以后新加链时用它重新推导，不要手打。

派生路径解析刻意**严格**：`44''`、`44'//0'` 这类多写一个引号或空段的写法直接报错，
而不是「尽量理解」。派生路径是与其它钱包对齐的接口，静默纠错等于派生出一批在别人钱包里不存在的地址。

Chia 的取舍：地址需要 BLS12-381 与标准 puzzle 的字节级常量，在本机无外网的条件下
无法逐字节比对参照实现，因此**不生成**派生地址，只提供 `xch1…` 的 bech32m 编解码与观察地址导入，
并从创建钱包的可选链里过滤掉（`ChainConfig.supportsKeyDerivation`）。
宁可少一个功能，也不要静默的错资产。

### 9.3 验证与打包：全部在本机完成

**不用 CI，也不用 GitHub 的任何构建/部署能力。** GitHub 只承担两个角色：
存放源码、分发 APK 与清单。所有"需要算力"的事都在开发机上跑完再上传 ——
这个项目的构建配置（国内镜像优先、临时目录指向 D 盘）本来就是为了本机环境调的，
搬到托管 runner 上还要额外做一层环境无关化，收益为零。

本机的验证与发布链路：

| 步骤 | 命令 |
| --- | --- |
| 契约校验（夹具 vs 发布产物） | `python tools/sync_contract_fixtures.py --check` |
| 全量单元测试 | `tools/build.sh testDebugUnitTest` |
| Lint | `tools/build.sh :app:lintDebug` |
| 构建并签名 + 生成 `versions.json` | `python tools/release.py` |
| 上传两个仓库 + 建 Release | `python repos/publish.py …` / `publish_release.py` |

**Release 构建必须纳入** —— R8 混淆与资源压缩的问题只在 Release 变体暴露。

签名包用 apksigner 与 aapt2 **双重校验**（签名有效 + 包内版本号与 `version.properties`
逐字一致）。发布动作本身是纯上传：`publish.py` 写两个仓库的文本文件、
`publish_release.py` 建 tag 与 Release 并上传 APK。
发版脚本**不自动改 `versions.json` 的对外可见性** —— 它等于立刻对所有用户生效，
属于对外动作，留给人确认。

> 曾经有过 `ci.yml` / `release.yml` / `sync-data.yml` 三个工作流，已按上述约定移除。
> 移除时的副产品是两个真 bug：`gradle.properties` 里写死 `-Djava.io.tmpdir=D:/tmp`
> （任何没有 D 盘的环境都起不来 Gradle 守护进程），以及 `release.py` 用
> `Path.write_text` 写出 CRLF 的 `versions.json`（与 git 里 LF 的契约夹具对不上）。
> 两者都已修复。

## 10. 里程碑

| 阶段 | 内容 | 状态 |
|---|---|---|
| M0 | 现状评估 + 技术路线决策 | ✅ 已完成 |
| M1 | 工程骨架、版本目录、国内镜像、安全内核（crypto）+ 标准向量测试 | ✅ 已完成（crypto 90 个向量用例） |
| M2 | 数据层（Room + DataStore）+ 依赖注入装配 | ✅ 已完成 |
| M3 | 链实现：BTC / EVM / TRON 的派生、地址、余额、交易签名与广播 | ✅ 代码与单测完成（72 个用例）；⚠️ 未真机广播验证 |
| M4 | UI：五大 Tab、自适应布局、钱包创建/导入/导出流程 | ✅ 代码完成；⚠️ 未真机走查 |
| M5 | 保险库与 OTP 功能落地（沿用 `core:crypto`） | ✅ 代码与单测完成 |
| M6 | DApp 市场（远端 JSON 清单）、设置、备份导出 | ✅ 已完成 |
| M7 | 旧数据迁移工具（LitePal → Room） | ❌ 不做 —— 3.x 与 2.x 数据格式不延续，改为「旧版内导出、新版导入」 |
| M8 | 打包、签名 | ✅ 已完成（v2+v3 签名，RSA 4096，约 4.2 MB） |
| M9 | 工程化：远端清单契约测试、发布前校验、本机发布链路 | ✅ 已完成 |
| M10 | 非 EVM 链扩展（Solana / ICP / Monero / Filecoin / Chia）+ 链管理可编辑 + 市场折叠与 24 小时缓存 | ✅ 已完成（crypto 142 + alt 55 + network 75 用例） |
| M11 | 真机验证（安装、解锁、扫码、各链余额与转账、折叠屏布局） | ⏳ **下一步** —— 当前唯一未覆盖的环节 |

> 现状小结：代码、单元测试（**272 个用例全绿**）、Lint（0 error）、签名打包、
> 发布流程与跨仓库契约校验均已就位；**唯一没有做过的是在真实设备上跑一遍**。
> 真机验证需要先联网推送两个 GitHub 仓库（见仓库外的 `PUSH-CHECKLIST.md`），
> 因为应用内「检查更新」「链清单刷新」「DApp 市场」都指向那两个仓库。
