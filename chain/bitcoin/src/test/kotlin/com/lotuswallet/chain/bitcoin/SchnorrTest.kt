package com.lotuswallet.chain.bitcoin

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import org.junit.Test

/**
 * BIP-340 官方测试向量。
 *
 * 这些向量是 Schnorr 实现的唯一可靠判据 —— 自签自验无法发现「双方都错」的情况。
 */
class SchnorrTest {

    @Test
    fun `BIP-340 向量 0`() {
        val secretKey = Bytes.fromHex("0000000000000000000000000000000000000000000000000000000000000003")
        val expectedPublicKey = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
        val auxRand = ByteArray(32)
        val message = ByteArray(32)

        assertThat(Bytes.toHex(Schnorr.publicKey(secretKey))).isEqualTo(expectedPublicKey)

        val signature = Schnorr.sign(secretKey, message, auxRand)
        assertThat(Bytes.toHex(signature)).isEqualTo(
            "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca8215" +
                "25f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0",
        )
        assertThat(Schnorr.verify(Bytes.fromHex(expectedPublicKey), message, signature)).isTrue()
    }

    @Test
    fun `BIP-340 向量 1`() {
        val secretKey = Bytes.fromHex("b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef")
        val expectedPublicKey = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659"
        val auxRand = Bytes.fromHex("0000000000000000000000000000000000000000000000000000000000000001")
        val message = Bytes.fromHex("243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89")

        assertThat(Bytes.toHex(Schnorr.publicKey(secretKey))).isEqualTo(expectedPublicKey)

        val signature = Schnorr.sign(secretKey, message, auxRand)
        assertThat(Bytes.toHex(signature)).isEqualTo(
            "6896bd60eeae296db48a229ff71dfe071bde413e6d43f917dc8dcf8c78de334189" +
                "06d11ac976abccb20b091292bff4ea897efcb639ea871cfa95f6de339e4b0a",
        )
        assertThat(Schnorr.verify(Bytes.fromHex(expectedPublicKey), message, signature)).isTrue()
    }

    @Test
    fun `篡改消息或公钥后验签必须失败`() {
        val secretKey = Bytes.fromHex("0000000000000000000000000000000000000000000000000000000000000003")
        val auxRand = ByteArray(32)
        val message = ByteArray(32)
        val signature = Schnorr.sign(secretKey, message, auxRand)
        val publicKey = Schnorr.publicKey(secretKey)

        // 消息被改
        val tamperedMessage = message.copyOf().also { it[0] = 1 }
        assertThat(Schnorr.verify(publicKey, tamperedMessage, signature)).isFalse()

        // 公钥被改
        val tamperedKey = publicKey.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        assertThat(Schnorr.verify(tamperedKey, message, signature)).isFalse()

        // 签名被改
        val tamperedSig = signature.copyOf().also { it[10] = (it[10].toInt() xor 0xFF).toByte() }
        assertThat(Schnorr.verify(publicKey, message, tamperedSig)).isFalse()

        // 长度非法
        assertThat(Schnorr.verify(publicKey, message, ByteArray(63))).isFalse()
    }

    @Test
    fun `签名是随机的但都可验证`() {
        val secretKey = Bytes.fromHex("0000000000000000000000000000000000000000000000000000000000000003")
        val message = Bytes.fromHex("00".repeat(31) + "01")
        val publicKey = Schnorr.publicKey(secretKey)
        val sigA = Schnorr.sign(secretKey, message)
        val sigB = Schnorr.sign(secretKey, message)
        // 默认使用随机 aux_rand，因此两次签名不同（这本身是一种抗侧信道特性）
        assertThat(Bytes.toHex(sigA)).isNotEqualTo(Bytes.toHex(sigB))
        assertThat(Schnorr.verify(publicKey, message, sigA)).isTrue()
        assertThat(Schnorr.verify(publicKey, message, sigB)).isTrue()
    }

    /**
     * Taproot 端到端自检：用调整后的私钥签名，用输出键验签。
     * 这正是 BIP-341 密钥路径花费在链上被验证的方式。
     */
    @Test
    fun `Taproot 调整私钥签名的结果能被输出键验证`() {
        val privateKey = Bytes.fromHex("0000000000000000000000000000000000000000000000000000000000000003")
        val outputKey = Taproot.outputKey(privateKey)
        val tweaked = Taproot.tweakedPrivateKey(privateKey)
        val message = Bytes.fromHex("aa".repeat(32))

        val signature = Schnorr.sign(tweaked, message, ByteArray(32))
        assertThat(Schnorr.verify(outputKey, message, signature)).isTrue()

        // 用未调整的私钥签名则验签失败 —— 证明 tweak 确实生效了
        val wrongSignature = Schnorr.sign(privateKey, message, ByteArray(32))
        assertThat(Schnorr.verify(outputKey, message, wrongSignature)).isFalse()
    }
}
