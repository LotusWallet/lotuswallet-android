# ---------------------------------------------------------------------------
# LotusWallet — R8 / ProGuard 规则
#
# 结论先行：**本项目不需要任何 keep 规则**。下面记录的是验证过程，
# 目的是防止后来者「看到加密库就往里塞 -keep」，无谓地牺牲体积与优化。
#
# 验证依据（release 变体实测，非推测）：
#   1. BouncyCastle 只以**普通类库**方式使用（Secp256k1 / Hash / KeyDerivation 等
#      直接 import 其 crypto.* 低层 API），全程没有走 JCE Provider 机制：
#         - 全仓库没有 Security.addProvider(...) / BouncyCastleProvider
#         - 没有 Class.forName / getDeclaredMethod 之类的反射入口
#      R8 对「静态可见的引用」做删除或内联是**语义保真**的，因此不会破坏密码学行为。
#
#   2. Cipher.getInstance("AES/GCM/NoPadding")、MessageDigest.getInstance("SHA-256")、
#      Mac.getInstance(...)、SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
#      走的是 **Android 平台自带 Provider（Conscrypt）**，与 R8 无关，必然可用。
#      （AndroidKeyStore 侧同理，由系统 keystore 提供实现。）
#
#   3. Room / Hilt(Dagger) / kotlinx.serialization / Compose 都在各自 artifact 里
#      自带 consumer ProGuard 规则，AGP 会自动合并，无需在此重复。
#
#   4. 类路径资源（chains.json、bip39/english.txt 通过 Class.getResourceAsStream 读取）
#      位于 APK 根目录，shrinkResources 只作用于 Android 资源表，不会删除它们。
#      —— 已实测确认 release APK 中两者均在，且 release 版 R8 + shrinkResources
#      全开后构建通过、单 dex、体积由 24MB 降至约 4MB。
#
# 若将来引入以下任一情况，才需要在这里补规则，并同时更新上面的说明：
#   - 让 BouncyCastle 作为 JCE Provider 注册（-keep class org.bouncycastle.jce.provider.**)
#   - 新增反射 / 序列化框架 / 服务发现（保留对应 SPI 文件与类名）
# ---------------------------------------------------------------------------

# 说明：下方 -dontwarn 是**防御性**的，不改变产物。
# BouncyCastle 的部分非 Android 功能会引用 java.awt / javax.naming 等平台上不存在的类；
# 当前未触发 R8 报错（这些分支未被触达），但 BC 升级后可能开始告警，
# 提前限定在 org.bouncycastle 命名空间内声明，可避免误伤其它库的真实缺失类告警。
-dontwarn org.bouncycastle.**
