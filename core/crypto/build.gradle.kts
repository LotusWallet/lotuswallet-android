plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.lotuswallet.core.crypto"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 单元测试直接跑在 JVM 上（BouncyCastle 为纯 Java 实现），
    // 因此 BIP39 / BIP32 / OTP 等核心算法可以用标准测试向量做真实验证。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core:common"))

    // 纯 Java 密码学实现，Android 与 JVM 单元测试均可运行
    api(libs.bouncycastle.prov)

    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
