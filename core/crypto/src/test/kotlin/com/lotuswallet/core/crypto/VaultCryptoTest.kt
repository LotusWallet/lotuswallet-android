package com.lotuswallet.core.crypto

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.AppError
import com.lotuswallet.core.common.AppException
import org.junit.Test

class VaultCryptoTest {

    /** 测试里用低配参数，缩短 Argon2 耗时。 */
    private val fast = KeyDerivation.lowEndParams()

    @Test
    fun `创建后可用同一口令解锁并得到相同 DEK`() {
        val password = "correct horse battery staple".toCharArray()
        val (envelope, dek) = VaultCrypto.create(password, fast)
        val unlocked = VaultCrypto.unlock(password, envelope)
        assertThat(unlocked).isEqualTo(dek)
        assertThat(dek).hasLength(32)
    }

    @Test
    fun `错误口令解锁失败并报告为密码错误`() {
        val (envelope, _) = VaultCrypto.create("right-password".toCharArray(), fast)
        val error = runCatching { VaultCrypto.unlock("wrong-password".toCharArray(), envelope) }.exceptionOrNull()
        assertThat(error).isInstanceOf(AppException::class.java)
        assertThat((error as AppException).error).isEqualTo(AppError.WrongPassword)
    }

    @Test
    fun `verify 对正确与错误口令分别返回真假`() {
        val (envelope, _) = VaultCrypto.create("pw".toCharArray(), fast)
        assertThat(VaultCrypto.verify("pw".toCharArray(), envelope)).isTrue()
        assertThat(VaultCrypto.verify("nope".toCharArray(), envelope)).isFalse()
    }

    @Test
    fun `两个保险库的 DEK 与盐都不同`() {
        val (env1, dek1) = VaultCrypto.create("pw".toCharArray(), fast)
        val (env2, dek2) = VaultCrypto.create("pw".toCharArray(), fast)
        assertThat(dek1).isNotEqualTo(dek2)
        assertThat(env1.kdfParams).isNotEqualTo(env2.kdfParams)
        assertThat(env1.wrappedDek).isNotEqualTo(env2.wrappedDek)
    }

    @Test
    fun `改密码后新口令可解锁 旧口令失效 且 DEK 不变`() {
        val (envelope, dek) = VaultCrypto.create("old".toCharArray(), fast)
        val rotated = VaultCrypto.changePassword("old".toCharArray(), "new".toCharArray(), envelope, fast)

        assertThat(VaultCrypto.unlock("new".toCharArray(), rotated)).isEqualTo(dek)
        assertThat(VaultCrypto.verify("old".toCharArray(), rotated)).isFalse()
    }

    @Test
    fun `改密码不会改变已加密的数据`() {
        val (envelope, dek) = VaultCrypto.create("old".toCharArray(), fast)
        val secret = "my seed phrase".toByteArray()
        val sealed = Aead.encrypt(dek, secret)

        val rotated = VaultCrypto.changePassword("old".toCharArray(), "new".toCharArray(), envelope, fast)
        val dekAfter = VaultCrypto.unlock("new".toCharArray(), rotated)

        // 数据密文原封不动，仍能被解出
        assertThat(Aead.decrypt(dekAfter, sealed)).isEqualTo(secret)
    }

    @Test
    fun `KDF 参数可序列化并往返`() {
        val params = KeyDerivation.Params(memoryKib = 32768, iterations = 2, parallelism = 2)
        val decoded = KeyDerivation.Params.decode(params.encode())
        assertThat(decoded).isEqualTo(params)
    }

    @Test
    fun `损坏的 KDF 参数返回 null 而不是崩溃`() {
        assertThat(KeyDerivation.Params.decode("")).isNull()
        assertThat(KeyDerivation.Params.decode("garbage")).isNull()
        assertThat(KeyDerivation.Params.decode("m=1")).isNull()
    }

    @Test
    fun `外部密钥包裹可往返`() {
        val dek = SecureRandom.bytes(32)
        val wrappingKey = SecureRandom.bytes(32)
        val wrapped = VaultCrypto.wrapWithExternalKey(dek, wrappingKey)
        assertThat(VaultCrypto.unwrapWithExternalKey(wrapped, wrappingKey)).isEqualTo(dek)
        val error = runCatching {
            VaultCrypto.unwrapWithExternalKey(wrapped, SecureRandom.bytes(32))
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(AppException::class.java)
    }

    @Test
    fun `旧密钥加密的数据可迁移到新密钥`() {
        val legacyKey = SecureRandom.bytes(32)
        val newKey = SecureRandom.bytes(32)
        val legacyPayload = Aead.encrypt(legacyKey, "旧数据".toByteArray())
        val migrated = VaultCrypto.reencrypt(legacyKey, newKey, legacyPayload)
        assertThat(Aead.decrypt(newKey, migrated)).isEqualTo("旧数据".toByteArray())
    }

    @Test
    fun `Argon2id 对相同输入给出确定性输出`() {
        val params = KeyDerivation.Params(memoryKib = 8192, iterations = 1, parallelism = 1, salt = ByteArray(16))
        val a = KeyDerivation.deriveKey("pw".toCharArray(), params)
        val b = KeyDerivation.deriveKey("pw".toCharArray(), params)
        assertThat(a).isEqualTo(b)
        assertThat(a).hasLength(32)
    }

    @Test
    fun `不同盐产生不同密钥`() {
        val p1 = KeyDerivation.Params(memoryKib = 8192, iterations = 1, parallelism = 1, salt = ByteArray(16))
        val p2 = KeyDerivation.Params(memoryKib = 8192, iterations = 1, parallelism = 1, salt = ByteArray(16) { 1 })
        assertThat(KeyDerivation.deriveKey("pw".toCharArray(), p1))
            .isNotEqualTo(KeyDerivation.deriveKey("pw".toCharArray(), p2))
    }
}
