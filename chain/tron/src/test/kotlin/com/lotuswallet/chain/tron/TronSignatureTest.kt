package com.lotuswallet.chain.tron

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Bip32
import org.junit.Test
import java.math.BigInteger

class TronSignatureTest {

    private val privateKey = Bytes.fromHex(
        "b5a4cea271ff424d7c31dc12a3e43e401df7a40d7412a15750f3f0b6b5449a28",
    )
    private val address = "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH"

    @Test
    fun `签名长度 65 字节且恢复标识为 0 或 1`() {
        val txId = ByteArray(32) { it.toByte() }
        val signature = TronSignature.sign(txId, privateKey)
        assertThat(signature.size).isEqualTo(65)
        assertThat(TronSignature.recoveryId(signature)).isIn(listOf(0, 1))
    }

    @Test
    fun `签名能够恢复到正确的发起地址`() {
        val txId = ByteArray(32) { (it * 7).toByte() }
        val signature = TronSignature.sign(txId, privateKey)
        assertThat(TronSignature.verify(txId, signature, address)).isTrue()
    }

    @Test
    fun `篡改交易哈希或签名后校验失败`() {
        val txId = ByteArray(32) { it.toByte() }
        val signature = TronSignature.sign(txId, privateKey)

        val tamperedTxId = txId.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        assertThat(TronSignature.verify(tamperedTxId, signature, address)).isFalse()

        val tamperedSig = signature.copyOf().also { it[5] = (it[5].toInt() xor 0xFF).toByte() }
        assertThat(TronSignature.verify(txId, tamperedSig, address)).isFalse()

        // 用别人的地址去校验同样失败
        // （TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t 是 TRON 主网 TRC-20 USDT 合约地址，Base58Check 合法）
        assertThat(TronSignature.verify(txId, signature, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t")).isFalse()
    }

    /**
     * low-S 规范化：TRON 节点会拒绝 s 处于高半区的签名。
     * 这里验证无论输入如何，产出的 s 永远在低半区。
     */
    @Test
    fun `签名始终使用 low-S`() {
        val halfN = com.lotuswallet.core.crypto.Secp256k1.HALF_N
        for (i in 0 until 32) {
            val txId = ByteArray(32) { ((it * 31) + i).toByte() }
            val signature = TronSignature.sign(txId, privateKey)
            val s = BigInteger(1, signature.copyOfRange(32, 64))
            assertThat(s <= halfN).isTrue()
        }
    }

    @Test
    fun `签名流程会拒绝不匹配的私钥`() {
        val unsigned = TronUnsignedTransaction(
            kotlinx.serialization.json.JsonObject(
                mapOf(
                    "txID" to kotlinx.serialization.json.JsonPrimitive("11".repeat(32)),
                    "raw_data_hex" to kotlinx.serialization.json.JsonPrimitive("0a025a8c"),
                ),
            ),
        )
        val otherKey = TronAddress.privateKeyOf(Bip32.fromMnemonic("legal winner thank year wave sausage worth useful legal winner thank yellow"), 0)
        val error = runCatching {
            TronSigningFlow.sign(unsigned, otherKey, address)
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error!!.message).contains("签名校验失败")
    }
}
