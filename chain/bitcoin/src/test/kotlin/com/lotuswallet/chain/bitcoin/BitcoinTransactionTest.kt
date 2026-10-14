package com.lotuswallet.chain.bitcoin

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import com.lotuswallet.core.network.chain.UtxoAddressType
import org.junit.Test

/**
 * BIP-143（SegWit v0）官方向量回归测试。
 *
 * 两组向量都取自 BIP-143 原文，且都是**逐字节可核验**的：
 * - 示例 1 覆盖原生 P2WPKH（`scriptCode` 由公钥哈希构造）；
 * - 示例 2 覆盖 P2SH-P2WPKH（`scriptSig` 里压入 redeemScript，见证放签名与公钥）。
 *
 * 这两个用例之所以重要：BIP-143 的签名原像里，`scriptCode` 必须**恰好带一次**
 * CompactSize 长度前缀。多带一次会让原像变成 `1a 19 76a914…88ac`，
 * 摘要随之改变，签名在链上会被直接判为无效 —— 而本机离线环境下很难发现。
 */
class BitcoinTransactionTest {

    /**
     * BIP-143 原文给出的是**交易内的原始字节序**的 prevout 哈希；
     * `TxOutPoint` 对外统一使用展示用的大端 txid，这里做一次转换，
     * 让向量里的十六进制串可以原样粘贴。
     */
    private fun wireTxid(wireHex: String) = TxWriter.wireOrderToTxId(Bytes.fromHex(wireHex))

    // ---------------------------------------------------------------- 示例 1

    private val in0TxidWire = "fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f"
    private val in1TxidWire = "ef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a"

    /** 输入 0（P2PK）的解锁脚本，由官方向量给出。 */
    private val in0ScriptSig =
        "4830450221008b9d1dc26ba6a9cb62127b02742fa9d754cd3bebf337f7a55d114c8e5cdd30be" +
            "022040529b194ba3f9281a99f2b1c0a19c0489bc22ede944ccf4ecbab4cc618ef3ed01"

    private val in1ScriptPubKey = "00141d0f172a0ecb48aee1be1f2687d2963ae33f71a1"
    private val in1PrivateKey = "619c335025c7f4012e556c2a58b2506e30b8511b53ade95ea316fd8c3286feb9"

    private val unsignedHex =
        "0100000002$in0TxidWire" +
            "0000000000eeffffff" +
            "$in1TxidWire" +
            "0100000000ffffffff" +
            "02202cb206000000001976a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac" +
            "9093510d000000001976a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac" +
            "11000000"

    private val signedHex =
        "01000000000102$in0TxidWire" +
            "00000000" +
            "494830450221008b9d1dc26ba6a9cb62127b02742fa9d754cd3bebf337f7a55d114c8e5cdd30be" +
            "022040529b194ba3f9281a99f2b1c0a19c0489bc22ede944ccf4ecbab4cc618ef3ed01" +
            "eeffffff" +
            "$in1TxidWire" +
            "0100000000ffffffff" +
            "02202cb206000000001976a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac" +
            "9093510d000000001976a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac" +
            "0002" +
            "47304402203609e17b84f6a7d30c80bfa610b5b4542f32a8a0d5447a12fb1366d7f01cc44a" +
            "0220573a954c4518331561406f90300e8f3358f51928d43c212a8caed02de67eebee" +
            "01" +
            "21025476c2e83188368da1ff3e292e7acafcdb3566bb0ad253f62fc70f07aeee6357" +
            "11000000"

    /** 官方向量里输入 0 是 6.25 BTC 的 P2PK，输入 1 是 6 BTC 的 P2WPKH。 */
    private fun bip143NativeSegwitTx() = BitcoinTransaction(
        version = 1,
        lockTime = 17,
        inputs = listOf(
            TxInput(
                outPoint = TxOutPoint(wireTxid(in0TxidWire), 0L),
                value = 625_000_000,
                scriptPubKey = Bytes.fromHex(
                    "2103c9f4836b9a4f77fc0d81f7bcb01b7f1b35916864b9476c241ce9fc198bd25432ac",
                ),
                sequence = 0xFFFFFFEEL,
            ),
            TxInput(
                outPoint = TxOutPoint(wireTxid(in1TxidWire), 1L),
                value = 600_000_000,
                scriptPubKey = Bytes.fromHex(in1ScriptPubKey),
                sequence = 0xFFFFFFFFL,
            ),
        ),
        outputs = listOf(
            TxOutput(112_340_000, Bytes.fromHex("76a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac")),
            TxOutput(223_450_000, Bytes.fromHex("76a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac")),
        ),
        segwit = true,
    )

    @Test
    fun `BIP-143 示例1 未签名交易序列化逐字节一致`() {
        val tx = bip143NativeSegwitTx()
        assertThat(Bytes.toHex(tx.serialize(includeWitness = false))).isEqualTo(unsignedHex)
    }

    /**
     * 摘要必须等于原文公布的 `c37af311…`。
     *
     * 该值同时也被独立参考实现交叉核验过：用原文公布的 DER 签名对该摘要做 ECDSA 验签可以通过，
     * 说明这不是「自己算、自己比」的循环论证。
     */
    @Test
    fun `BIP-143 示例1 签名摘要等于官方公布值`() {
        val tx = bip143NativeSegwitTx()
        val scriptCode = Script.p2wpkhScriptCode(Bytes.fromHex("1d0f172a0ecb48aee1be1f2687d2963ae33f71a1"))
        val digest = tx.segwitV0Sighash(inputIndex = 1, scriptCode = scriptCode, amount = 600_000_000L)
        assertThat(Bytes.toHex(digest))
            .isEqualTo("c37af31116d1b27caf68aae9e3ac82f1477929014d5b917657d0eb49478cb670")
    }

    @Test
    fun `BIP-143 示例1 签名后的完整交易逐字节一致`() {
        val tx = bip143NativeSegwitTx()

        BitcoinSigner.signInput(tx, 1, Bytes.fromHex(in1PrivateKey), UtxoAddressType.NATIVE_SEGWIT)

        // 输入 0（P2PK）的解锁脚本由向量给定，直接填入后比较整笔交易
        tx.inputs[0].scriptSig = Bytes.fromHex(in0ScriptSig)

        assertThat(tx.rawHex()).isEqualTo(signedHex)
        BitcoinSigner.sanityCheck(tx)
    }

    /**
     * txid = `SHA256d(不含见证数据的序列化)`，与含见证的 wtxid 必然不同。
     *
     * 期望值由独立参考实现（`tools/bip143_check.py`）从官方向量逐字节复算得到。
     */
    @Test
    fun `BIP-143 示例1 的 txid 不含见证数据`() {
        val tx = bip143NativeSegwitTx()
        BitcoinSigner.signInput(tx, 1, Bytes.fromHex(in1PrivateKey), UtxoAddressType.NATIVE_SEGWIT)
        tx.inputs[0].scriptSig = Bytes.fromHex(in0ScriptSig)

        assertThat(tx.txId()).isEqualTo("e8151a2af31c368a35053ddd4bdb285a8595c769a3ad83e0fa02314a602d4609")
        assertThat(tx.wtxId()).isNotEqualTo(tx.txId())
    }

    // ---------------------------------------------------------------- 示例 2

    private val nestedTxidWire = "db6b1b20aa0fd7b23880be2ecbd4a98130974cf4748fb66092ac4d3ceb1a5477"

    private val nestedUnsignedHex =
        "0100000001$nestedTxidWire" +
            "0100000000feffffff" +
            "02b8b4eb0b000000001976a914a457b684d7f0d539a46a45bbc043f35b59d0d96388ac" +
            "0008af2f000000001976a914fd270b1ee6abcaea97fea7ad0402e8bd8ad6d77c88ac" +
            "92040000"

    private val nestedSignedHex =
        "01000000000101$nestedTxidWire" +
            "01000000" +
            "1716001479091972186c449eb1ded22b78e40d009bdf0089" +
            "feffffff" +
            "02b8b4eb0b000000001976a914a457b684d7f0d539a46a45bbc043f35b59d0d96388ac" +
            "0008af2f000000001976a914fd270b1ee6abcaea97fea7ad0402e8bd8ad6d77c88ac" +
            "02473044022047ac8e878352d3ebbde1c94ce3a10d057c24175747116f8288e5d794d12d482f" +
            "0220217f36a485cae903c713331d877c1f64677e3622ad4010726870540656fe9dcb01" +
            "2103ad1d8e89212f0b92c74d23bb710c00662ad1470198ac48c43f7d6f93a2a26873" +
            "92040000"

    private val nestedPrivateKey = "eb696a065ef48a2192da5b28b694f87544b30fae8327c4510137a922f32c6dcf"

    /** 输入来自 10 BTC 的 P2SH-P2WPKH。 */
    private fun bip143NestedSegwitTx() = BitcoinTransaction(
        version = 1,
        lockTime = 1170,
        inputs = listOf(
            TxInput(
                outPoint = TxOutPoint(wireTxid(nestedTxidWire), 1L),
                value = 1_000_000_000,
                scriptPubKey = Bytes.fromHex("a9144733f37cf4db86fbc2efed2500b4f4e49f31202387"),
                sequence = 0xFFFFFFFEL,
            ),
        ),
        outputs = listOf(
            TxOutput(199_996_600, Bytes.fromHex("76a914a457b684d7f0d539a46a45bbc043f35b59d0d96388ac")),
            TxOutput(800_000_000, Bytes.fromHex("76a914fd270b1ee6abcaea97fea7ad0402e8bd8ad6d77c88ac")),
        ),
        segwit = true,
    )

    @Test
    fun `BIP-143 示例2 未签名交易序列化逐字节一致`() {
        assertThat(Bytes.toHex(bip143NestedSegwitTx().serialize(includeWitness = false)))
            .isEqualTo(nestedUnsignedHex)
    }

    @Test
    fun `BIP-143 示例2 签名摘要等于官方公布值`() {
        val tx = bip143NestedSegwitTx()
        val scriptCode = Script.p2wpkhScriptCode(Bytes.fromHex("79091972186c449eb1ded22b78e40d009bdf0089"))
        val digest = tx.segwitV0Sighash(inputIndex = 0, scriptCode = scriptCode, amount = 1_000_000_000L)
        assertThat(Bytes.toHex(digest))
            .isEqualTo("64f3b0f4dd2bb3aa1ce8566d220cc74dda9df97d8490cc81d89d735c92e59fb6")
    }

    /** P2SH-P2WPKH：`scriptSig` 压入 redeemScript，签名与公钥进见证。 */
    @Test
    fun `BIP-143 示例2 签名后的完整交易逐字节一致`() {
        val tx = bip143NestedSegwitTx()
        BitcoinSigner.signInput(tx, 0, Bytes.fromHex(nestedPrivateKey), UtxoAddressType.NESTED_SEGWIT)
        assertThat(tx.rawHex()).isEqualTo(nestedSignedHex)
        BitcoinSigner.sanityCheck(tx)
    }

    // ---------------------------------------------------------------- 基础行为

    @Test
    fun `txid 与 wtxid 不同`() {
        val tx = BitcoinTransaction(
            version = 2,
            lockTime = 0,
            inputs = listOf(TxInput(TxOutPoint("00".repeat(32), 0L), 100_000, ByteArray(0))),
            outputs = listOf(TxOutput(90_000, Bytes.fromHex("0014" + "11".repeat(20)))),
            segwit = true,
        )
        BitcoinSigner.signInput(
            tx, 0,
            Bytes.fromHex("01".padStart(64, '0')),
            UtxoAddressType.NATIVE_SEGWIT,
        )
        assertThat(tx.txId()).isNotEqualTo(tx.wtxId())
        assertThat(tx.txId()).hasLength(64)
        assertThat(tx.wtxId()).hasLength(64)
    }

    @Test
    fun `未签名的交易不允许广播`() {
        val tx = BitcoinTransaction(
            version = 2,
            lockTime = 0,
            inputs = listOf(TxInput(TxOutPoint("00".repeat(32), 0L), 100_000, ByteArray(0))),
            outputs = listOf(TxOutput(90_000, Bytes.fromHex("0014" + "11".repeat(20)))),
        )
        val error = runCatching { tx.rawHex() }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `序列化的 varInt 边界正确`() {
        assertThat(Bytes.toHex(TxWriter.varInt(0))).isEqualTo("00")
        assertThat(Bytes.toHex(TxWriter.varInt(0xfc))).isEqualTo("fc")
        assertThat(Bytes.toHex(TxWriter.varInt(0xfd))).isEqualTo("fdfd00")
        assertThat(Bytes.toHex(TxWriter.varInt(0xffff))).isEqualTo("fdffff")
        assertThat(Bytes.toHex(TxWriter.varInt(0x10000))).isEqualTo("fe00000100")
    }

    @Test
    fun `txid 的字节序转换正确`() {
        val displayHex = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
        val wire = TxWriter.txIdToWireOrder(displayHex)
        assertThat(wire[0].toInt() and 0xFF).isEqualTo(0x20)
        assertThat(wire[31].toInt() and 0xFF).isEqualTo(0x01)
        assertThat(TxWriter.wireOrderToTxId(wire)).isEqualTo(displayHex)
    }
}
