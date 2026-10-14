package com.lotuswallet.core.crypto

import com.google.common.truth.Truth.assertThat
import com.lotuswallet.core.common.Bytes
import org.junit.Test

/**
 * SLIP-0010 的官方测试向量。
 *
 * ## 为什么必须用官方向量
 * ed25519 的 HD 派生与 secp256k1 的 BIP-32 只差两处：HMAC 的 key 与「只允许硬化」。
 * 两处都极容易写错，且错了以后**不会抛异常** —— 只会派生出一批「在别的钱包里
 * 不存在的地址」。所以这里逐条对上规范里的私钥与链码，而不是只做「两次调用结果一致」
 * 这种自洽性检查。
 *
 * 期望值来源：SLIP-0010 规范中的 Test vector 1 / Test vector 2（ed25519 部分），
 * 并已用独立的实现复算过一遍。
 */
class Slip10Test {

    /** SLIP-0010 Test vector 1：16 字节种子。 */
    private val seed1 = Bytes.fromHex("000102030405060708090a0b0c0d0e0f")

    /** SLIP-0010 Test vector 2：64 字节种子。 */
    private val seed2 = Bytes.fromHex(
        "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e" +
            "7b7875726f6c696663605d5a5754514e4b484542"
    )

    @Test
    fun `测试向量1——主密钥与各级子密钥`() {
        assertNode(
            seed = seed1,
            path = "m",
            privateKey = "2b4be7f19ee27bbf30c667b642d5f4aa69fd169872f8fc3059c08ebae2eb19e7",
            chainCode = "90046a93de5380a72b5e45010748567d5ea02bbf6522f979e05c0d8d8ca9fffb",
        )
        assertNode(
            seed = seed1,
            path = "m/0'",
            privateKey = "68e0fe46dfb67e368c75379acec591dad19df3cde26e63b93a8e704f1dade7a3",
            chainCode = "8b59aa11380b624e81507a27fedda59fea6d0b779a778918a2fd3590e16e9c69",
        )
        assertNode(
            seed = seed1,
            path = "m/0'/1'",
            privateKey = "b1d0bad404bf35da785a64ca1ac54b2617211d2777696fbffaf208f746ae84f2",
            chainCode = "a320425f77d1b5c2505a6b1b27382b37368ee640e3557c315416801243552f14",
        )
        assertNode(
            seed = seed1,
            path = "m/0'/1'/2'",
            privateKey = "92a5b23c0b8a99e37d07df3fb9966917f5d06e02ddbd909c7e184371463e9fc9",
            chainCode = "2e69929e00b5ab250f49c3fb1c12f252de4fed2c1db88387094a0f8c4c9ccd6c",
        )
        assertNode(
            seed = seed1,
            path = "m/0'/1'/2'/2'",
            privateKey = "30d1dc7e5fc04c31219ab25a27ae00b50f6fd66622f6e9c913253d6511d1e662",
            chainCode = "8f6d87f93d750e0efccda017d662a1b31a266e4a6f5993b15f5c1f07f74dd5cc",
        )
        assertNode(
            seed = seed1,
            path = "m/0'/1'/2'/2'/1000000000'",
            privateKey = "8f94d394a8e8fd6b1bc2f3f49f5c47e385281d5c17e65324b0f62483e37e8793",
            chainCode = "68789923a0cac2cd5a29172a475fe9e0fb14cd6adb5ad98a3fa70333e7afa230",
        )
    }

    @Test
    fun `测试向量2——64字节种子与接近上限的索引`() {
        assertNode(
            seed = seed2,
            path = "m",
            privateKey = "171cb88b1b3c1db25add599712e36245d75bc65a1a5c9e18d76f9f2b1eab4012",
            chainCode = "ef70a74db9c3a5af931b5fe73ed8e1a53464133654fd55e7a66f8570b8e33c3b",
        )
        assertNode(
            seed = seed2,
            path = "m/0'",
            privateKey = "1559eb2bbec5790b0c65d8693e4d0875b1747f4970ae8b650486ed7470845635",
            chainCode = "0b78a3226f915c082bf118f83618a618ab6dec793752624cbeb622acb562862d",
        )
        // 2147483647 是未硬化索引的上限，硬化后正好是 0xFFFFFFFF —— 容易踩整数溢出
        assertNode(
            seed = seed2,
            path = "m/0'/2147483647'",
            privateKey = "ea4f5bfe8694d8bb74b7b59404632fd5968b774ed545e810de9c32a4fb4192f4",
            chainCode = "138f0b2551bcafeca6ff2aa88ba8ed0ed8de070841f0c4ef0165df8181eaad7f",
        )
    }

    @Test
    fun `主密钥用 ed25519 seed 作为 HMAC key 而不是 Bitcoin seed`() {
        // 这是 SLIP-0010 与 BIP-32 最容易混的一处：用错 key 不会报错，只会派生错
        val slip10 = Slip10.masterKey(seed1)
        val bip32 = Bip32.fromSeed(seed1)
        assertThat(slip10.key).isNotEqualTo(bip32.privateKey)

        // 正面确认：HMAC-SHA512("ed25519 seed", seed) 的结果
        val expected = Hash.hmacSha512("ed25519 seed".toByteArray(Charsets.US_ASCII), seed1)
        assertThat(slip10.key).isEqualTo(expected.copyOfRange(0, 32))
        assertThat(slip10.chainCode).isEqualTo(expected.copyOfRange(32, 64))
    }

    @Test
    fun `路径解析接受常见的四种写法`() {
        val expected = listOf(44, 501, 0, 0)
        assertThat(Slip10.parsePath("m/44'/501'/0'/0'")).isEqualTo(expected)
        assertThat(Slip10.parsePath("44'/501'/0'/0'")).isEqualTo(expected)
        assertThat(Slip10.parsePath("m/44h/501H/0'/0'")).isEqualTo(expected)
        assertThat(Slip10.parsePath("M/44/501/0/0")).isEqualTo(expected)
        assertThat(Slip10.parsePath("  m/44'/501'/0'/0'  ")).isEqualTo(expected)
    }

    @Test
    fun `路径解析——m 与空串表示不派生`() {
        assertThat(Slip10.parsePath("m")).isEmpty()
        assertThat(Slip10.parsePath("M")).isEmpty()
        assertThat(Slip10.parsePath("")).isEmpty()
    }

    @Test
    fun `路径解析拒绝非法片段`() {
        for (bad in listOf("m/abc", "m/44'/x", "m/-1'", "m/44''")) {
            val error = runCatching { Slip10.parsePath(bad) }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `派生结果的长度固定为 32 字节`() {
        val node = Slip10.derive(seed1, "m/44'/501'/0'/0'")
        assertThat(node.key.size).isEqualTo(32)
        assertThat(node.chainCode.size).isEqualTo(32)
    }

    @Test
    fun `同一路径派生可复现——不同路径必须不同`() {
        val a = Slip10.derive(seed1, "m/44'/501'/0'/0'")
        val b = Slip10.derive(seed1, "m/44'/501'/0'/0'")
        val c = Slip10.derive(seed1, "m/44'/501'/1'/0'")
        assertThat(a.key).isEqualTo(b.key)
        assertThat(a.key).isNotEqualTo(c.key)
    }

    @Test
    fun `种子过短会被拒绝`() {
        val error = runCatching { Slip10.masterKey(ByteArray(8)) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun assertNode(seed: ByteArray, path: String, privateKey: String, chainCode: String) {
        val node = Slip10.derive(seed, path)
        assertThat(Bytes.toHex(node.key)).isEqualTo(privateKey)
        assertThat(Bytes.toHex(node.chainCode)).isEqualTo(chainCode)
    }
}
