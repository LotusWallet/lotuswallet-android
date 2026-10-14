package com.lotuswallet.core.crypto

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.AppError
import com.lotuswallet.core.common.AppException
import com.lotuswallet.core.common.Bytes
import org.junit.Test

class AeadTest {

    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `加解密往返`() {
        val plaintext = "私钥：0xdeadbeef，助记词：abandon abandon about".toByteArray()
        val sealed = Aead.encrypt(key, plaintext)
        assertThat(Aead.decrypt(key, sealed)).isEqualTo(plaintext)
    }

    @Test
    fun `相同明文两次加密产生不同密文`() {
        val plaintext = "same".toByteArray()
        val a = Aead.encrypt(key, plaintext)
        val b = Aead.encrypt(key, plaintext)
        assertThat(Bytes.toHex(a)).isNotEqualTo(Bytes.toHex(b))
        assertThat(Aead.decrypt(key, a)).isEqualTo(plaintext)
        assertThat(Aead.decrypt(key, b)).isEqualTo(plaintext)
    }

    @Test
    fun `密文格式含版本号与 12 字节 nonce`() {
        val sealed = Aead.encrypt(key, "x".toByteArray())
        assertThat(sealed[0]).isEqualTo(1.toByte())
        assertThat(sealed.size).isEqualTo(1 + 12 + 1 + 16)
    }

    @Test
    fun `篡改密文触发完整性失败`() {
        val sealed = Aead.encrypt(key, "sensitive".toByteArray())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()
        val error = runCatching { Aead.decrypt(key, sealed) }.exceptionOrNull()
        assertThat(error).isInstanceOf(AppException::class.java)
        assertThat((error as AppException).error).isEqualTo(AppError.IntegrityCheckFailed)
    }

    @Test
    fun `篡改 nonce 触发完整性失败`() {
        val sealed = Aead.encrypt(key, "sensitive".toByteArray())
        sealed[3] = (sealed[3].toInt() xor 0xFF).toByte()
        val error = runCatching { Aead.decrypt(key, sealed) }.exceptionOrNull()
        assertThat(error).isInstanceOf(AppException::class.java)
    }

    @Test
    fun `错误密钥无法解密`() {
        val sealed = Aead.encrypt(key, "sensitive".toByteArray())
        val wrongKey = ByteArray(32) { (it + 1).toByte() }
        val error = runCatching { Aead.decrypt(wrongKey, sealed) }.exceptionOrNull()
        assertThat(error).isInstanceOf(AppException::class.java)
    }

    @Test
    fun `附加认证数据不匹配则解密失败`() {
        val aad = "entry-id-1".toByteArray()
        val sealed = Aead.encrypt(key, "sensitive".toByteArray(), aad)
        assertThat(Aead.decrypt(key, sealed, aad)).isEqualTo("sensitive".toByteArray())
        val error = runCatching { Aead.decrypt(key, sealed, "entry-id-2".toByteArray()) }.exceptionOrNull()
        assertThat(error).isInstanceOf(AppException::class.java)
    }

    @Test
    fun `密钥长度非法时给出明确异常`() {
        val error = runCatching { Aead.encrypt(ByteArray(16), "x".toByteArray()) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `字符串便捷方法往返`() {
        val hex = Aead.encryptString(key, "hello 世界")
        assertThat(Aead.decryptString(key, hex)).isEqualTo("hello 世界")
    }

    @Test
    fun `支持大数据量`() {
        val big = SecureRandom.bytes(512 * 1024)
        assertThat(Aead.decrypt(key, Aead.encrypt(key, big))).isEqualTo(big)
    }
}
