package com.lotuswallet.chain.evm

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.crypto.Secp256k1
import org.junit.Test
import java.math.BigInteger

class EvmTransactionTest {

    private val privateKey = ByteArray(32) { 0x46 }

    /**
     * EIP-155 规范里给出的完整示例，这是最能说明问题的一个向量：
     * 它一次性验证了 RLP 编码、keccak256 摘要、确定性签名、low-S、以及 EIP-155 的 v 值计算。
     *
     * 若 `recId` 选错（这正是我在开发中抓到并修掉的 bug），v 会变成 0x26，
     * 由 `ecrecover` 反推出的地址就会完全不同，交易在链上会被判为「签名者不符」。
     */
    @Test
    fun `EIP-155 官方示例得到完全一致的签名交易`() {
        val tx = LegacyEvmTransaction(
            chainId = 1,
            nonce = 9,
            gasPrice = BigInteger.valueOf(20_000_000_000L),
            gasLimit = BigInteger.valueOf(21_000L),
            to = "0x3535353535353535353535353535353535353535",
            value = BigInteger.TEN.pow(18),
        )

        val expectedSigningHash =
            "daf5a779ae972f972197303d7b574746c7ef83eadac0f2791ad23db92e4c8e53"
        assertThat(Bytes.toHex(tx.unsignedHash())).isEqualTo(expectedSigningHash)

        val signed = tx.sign(privateKey)
        assertThat(signed.rawHex.removePrefix("0x")).isEqualTo(
            "f86c098504a817c800825208943535353535353535353535353535353535353535" +
                "880de0b6b3a76400008025" +
                "a028ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276" +
                "a067cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83",
        )
        // v 必须是 37（= recId 0 + 35 + chainId 1 * 2）
        assertThat(signed.rawHex).contains("8025a0")
    }

    @Test
    fun `交易哈希等于原始交易的 keccak256`() {
        val tx = LegacyEvmTransaction(
            chainId = 56,
            nonce = 0,
            gasPrice = BigInteger.valueOf(5_000_000_000L),
            gasLimit = BigInteger.valueOf(21_000L),
            to = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
            value = BigInteger.valueOf(1_000_000_000_000_000L),
        )
        val signed = tx.sign(privateKey)
        val raw = Bytes.fromHex(signed.rawHex)
        assertThat(signed.hash).isEqualTo("0x" + Bytes.toHex(com.lotuswallet.core.crypto.Hash.keccak256(raw)))
    }

    @Test
    fun `签名可以被 ecrecover 还原为正确的发送方`() {
        val tx = LegacyEvmTransaction(
            chainId = 137,
            nonce = 42,
            gasPrice = BigInteger.valueOf(30_000_000_000L),
            gasLimit = BigInteger.valueOf(60_000L),
            to = "0x3535353535353535353535353535353535353535",
            value = BigInteger.valueOf(123_456_789L),
        )
        val digest = tx.unsignedHash()
        val signature = Secp256k1.sign(privateKey, digest)
        // 大写的 v 校验：recId 必须让恢复出的公钥地址 == 发送方地址
        val recovered = Secp256k1.recoverPublicPoint(digest, signature.r, signature.s, signature.recId)
        val recoveredAddress = EvmAddress.fromPublicKey(recovered.getEncoded(false))
        assertThat(recoveredAddress).isEqualTo(EvmAddress.fromPrivateKey(privateKey))
        assertThat(tx.sign(privateKey).from).isEqualTo(recoveredAddress)
    }

    @Test
    fun `EIP-1559 交易以 0x02 开头且可还原发送方`() {
        val tx = Eip1559EvmTransaction(
            chainId = 1,
            nonce = 7,
            maxPriorityFeePerGas = BigInteger.valueOf(1_500_000_000L),
            maxFeePerGas = BigInteger.valueOf(30_000_000_000L),
            gasLimit = BigInteger.valueOf(21_000L),
            to = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
            value = BigInteger.valueOf(10_000_000_000_000_000L),
        )
        val signed = tx.sign(privateKey)
        assertThat(signed.rawHex).startsWith("0x02")

        val digest = tx.unsignedHash()
        val signature = Secp256k1.sign(privateKey, digest)
        val recovered = Secp256k1.recoverPublicPoint(digest, signature.r, signature.s, signature.recId)
        assertThat(EvmAddress.fromPublicKey(recovered.getEncoded(false)))
            .isEqualTo(EvmAddress.fromPrivateKey(privateKey))
        // yParity 只能是 0 或 1
        assertThat(signature.recId).isIn(listOf(0, 1))
    }

    @Test
    fun `金额或接收方不同的交易摘要必然不同`() {
        val base = Eip1559EvmTransaction(
            chainId = 1,
            nonce = 0,
            maxPriorityFeePerGas = BigInteger.ONE,
            maxFeePerGas = BigInteger.TWO,
            gasLimit = BigInteger.valueOf(21_000L),
            to = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
            value = BigInteger.ONE,
        )
        val differentValue = base.copy(value = BigInteger.TWO)
        val differentTo = base.copy(to = "0x3535353535353535353535353535353535353535")
        assertThat(Bytes.toHex(base.unsignedHash())).isNotEqualTo(Bytes.toHex(differentValue.unsignedHash()))
        assertThat(Bytes.toHex(base.unsignedHash())).isNotEqualTo(Bytes.toHex(differentTo.unsignedHash()))
    }

    @Test
    fun `合约创建时 to 字段为空`() {
        val create = LegacyEvmTransaction(
            chainId = 1,
            nonce = 0,
            gasPrice = BigInteger.ONE,
            gasLimit = BigInteger.valueOf(100_000L),
            to = null,
            value = BigInteger.ZERO,
            data = ByteArray(4) { 0x60 },
        )
        val call = create.copy(to = "0x3535353535353535353535353535353535353535")
        // 空 to（0x80）与 20 字节地址（0x94...）编码不同，摘要必然不同
        assertThat(Bytes.toHex(create.unsignedHash())).isNotEqualTo(Bytes.toHex(call.unsignedHash()))
        // 合约创建的签名交易首位是 0xf8/f9（长列表），且仍然能正常签名
        val signed = create.sign(privateKey)
        assertThat(signed.rawHex).startsWith("0xf")
        assertThat(signed.from).isEqualTo(EvmAddress.fromPrivateKey(privateKey))
    }

    @Test
    fun `数量转十六进制符合 JSON-RPC 约定`() {
        assertThat(BigInteger.ZERO.toHexQuantity()).isEqualTo("0x0")
        assertThat(BigInteger.ONE.toHexQuantity()).isEqualTo("0x1")
        assertThat(BigInteger.valueOf(255).toHexQuantity()).isEqualTo("0xff")
        assertThat(BigInteger.valueOf(1024).toHexQuantity()).isEqualTo("0x400")
        assertThat(parseHexBigInteger("0xde0b6b3a7640000")).isEqualTo(BigInteger.TEN.pow(18))
        assertThat(parseHexBigInteger("0x0")).isEqualTo(BigInteger.ZERO)
        assertThat(parseHexBigInteger("0x")).isEqualTo(BigInteger.ZERO)
    }
}
