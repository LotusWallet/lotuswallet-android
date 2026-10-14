package com.lotuswallet.core.crypto

import com.lotuswallet.core.common.Bytes
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * 口令 → 密钥 的派生函数。使用 **Argon2id**（RFC 9106），当前公认抗 GPU/ASIC 暴力破解的最佳选择。
 *
 * 为什么替换旧版：旧版用 PBKDF2-HMAC-SHA1、1000 轮、固定盐（盐 = SHA512(邮箱)），
 * 且密钥来自服务端下发的 token —— 既弱又与后端强耦合。这里改为：
 * - Argon2id，内存硬化参数可调（默认 64 MiB / 3 轮 / 4 路并发）；
 * - 每个库一份随机盐；
 * - 完全不依赖任何远端输入，这是 serverless 改造的关键前提。
 */
object KeyDerivation {

    const val KEY_LENGTH = 32

    /**
     * Argon2id 参数。持久化时一并存储，以便未来升级参数后仍能解开旧数据。
     */
    data class Params(
        val memoryKib: Int = 64 * 1024,
        val iterations: Int = 3,
        val parallelism: Int = 4,
        val salt: ByteArray = SecureRandom.bytes(SALT_LENGTH),
        val version: Int = Argon2Parameters.ARGON2_VERSION_13,
    ) {
        /** 供存储的紧凑表示：`v=19,m=65536,t=3,p=4,s=<hex>` */
        fun encode(): String =
            "v=$version,m=$memoryKib,t=$iterations,p=$parallelism,s=${Bytes.toHex(salt)}"

        /**
         * 复制一份并**重新生成随机盐**。
         *
         * 每次创建 / 修改主密码都必须调用它，避免调用方复用同一个 [Params] 实例
         * 导致盐被重复使用（盐复用会让相同口令派生出相同 KEK，削弱抗彩虹表能力）。
         */
        fun withFreshSalt(): Params = copy(salt = SecureRandom.bytes(SALT_LENGTH))

        override fun equals(other: Any?): Boolean =
            other is Params && memoryKib == other.memoryKib && iterations == other.iterations &&
                parallelism == other.parallelism && version == other.version && salt.contentEquals(other.salt)

        override fun hashCode(): Int =
            (((memoryKib * 31 + iterations) * 31 + parallelism) * 31 + version) * 31 + salt.contentHashCode()

        companion object {
            const val SALT_LENGTH = 16

            /** 解析 [encode] 产出的字符串；格式非法时返回 null。 */
            fun decode(encoded: String): Params? {
                return try {
                    val map = encoded.split(',').mapNotNull {
                        val parts = it.split('=', limit = 2)
                        if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                    }.toMap()

                    val memoryKib = map["m"]?.toInt() ?: return null
                    val iterations = map["t"]?.toInt() ?: return null
                    val parallelism = map["p"]?.toInt() ?: return null
                    val saltHex = map["s"] ?: return null
                    val version = map["v"]?.toInt() ?: Argon2Parameters.ARGON2_VERSION_13

                    Params(
                        memoryKib = memoryKib,
                        iterations = iterations,
                        parallelism = parallelism,
                        salt = Bytes.fromHex(saltHex),
                        version = version,
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /**
     * 由口令派生 32 字节密钥。
     *
     * 注意：这是 CPU/内存密集型操作（默认参数在手机上约 0.3–1.5 秒），
     * **必须在后台线程调用**，并配合 UI 进度提示。
     */
    fun deriveKey(password: CharArray, params: Params): ByteArray {
        require(password.isNotEmpty()) { "口令不能为空" }
        val generator = Argon2BytesGenerator()
        val argonParams = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(params.version)
            .withIterations(params.iterations)
            .withMemoryAsKB(params.memoryKib)
            .withParallelism(params.parallelism)
            .withSalt(params.salt)
            .build()
        generator.init(argonParams)
        val out = ByteArray(KEY_LENGTH)
        generator.generateBytes(String(password).toByteArray(Charsets.UTF_8), out)
        return out
    }

    /** 低配设备可降档使用（内存减半、轮数减少）。 */
    fun lowEndParams(): Params = Params(memoryKib = 32 * 1024, iterations = 2, parallelism = 2)

    /** 高配设备可升档使用。 */
    fun hardenedParams(): Params = Params(memoryKib = 128 * 1024, iterations = 4, parallelism = 4)
}
