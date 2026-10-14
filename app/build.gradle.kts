import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ---------------------------------------------------------------------------
// 版本号：唯一来源是仓库根目录的 version.properties
//
// versionName / versionCode 会同时出现在 APK、git tag、download 仓库的 versions.json
// 和 CHANGELOG 里。手工在多处维护必然漂，所以这里只读一次、其余全部派生。
// 升级请用 `python tools/bump_version.py`，不要手改 version.properties。
// ---------------------------------------------------------------------------
val versionProps = Properties().apply {
    val file = rootProject.file("version.properties")
    check(file.exists()) { "缺少版本号文件 ${file.path}（应由 tools/bump_version.py 维护）" }
    file.inputStream().use { load(it) }
}

fun prop(name: String): Int = versionProps.getProperty(name)
    ?.trim()?.toIntOrNull()
    ?: error("version.properties 里的 $name 缺失或不是整数")

val vMajor = prop("version.major")
val vMinor = prop("version.minor")
val vPatch = prop("version.patch")
val vPre = prop("version.pre")

check(vMinor in 0..99) { "minor 必须在 0..99（当前 $vMinor）：超出请进到 major" }
check(vPatch in 0..99) { "patch 必须在 0..99（当前 $vPatch）：超出请进到 minor" }

val lotusVersionName = buildString {
    append("$vMajor.$vMinor.$vPatch")
    // 预发布只加后缀，不参与 versionCode —— 对外发布靠 patch 递增区分，
    // 详见 version.properties 的说明。
    if (vPre > 0) append("-beta.$vPre")
}

val lotusVersionCode = vMajor * 10_000 + vMinor * 100 + vPatch

// ---------------------------------------------------------------------------
// 发布签名：从仓库外的 keystore.properties 读（该文件在 .gitignore 里）。
// 密钥由 tools/gen_keystore.py 一次性生成；换了密钥老用户就无法覆盖安装，
// 所以这里刻意不做「找不到就退回 debug 签名」的兜底 —— 那种兜底会悄悄产出一个
// 装不上（或装上去之后无法升级）的包，比构建直接失败危险得多。
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProps.getProperty("storeFile")?.let { File(it).exists() } == true

android {
    namespace = "com.lotuswallet.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lotuswallet.app"
        minSdk = 26
        targetSdk = 35
        versionCode = lotusVersionCode
        versionName = lotusVersionName

        // 无账号体系，因此不需要任何第三方 SDK 的 App ID
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = File(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // 两个开关都给上：v1 保证 Android 6 及以下能装，v2/v3 让新系统校验更快。
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 发布签名通过仓库外的 keystore.properties 注入，密钥绝不进仓库。
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
            // 没配签名也要能产出（仅用于本机自测）；正式分发必须有签名。
            // 见 tools/release.py 的发布前检查。
        }
    }

    // 大屏 / 折叠屏 / 分屏适配的前提：允许任意窗口尺寸与动态配置变更
    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // 本项目模块
    implementation(project(":core:common"))
    implementation(project(":core:crypto"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network"))
    implementation(project(":core:designsystem"))
    implementation(project(":chain:bitcoin"))
    implementation(project(":chain:evm"))
    implementation(project(":chain:tron"))
    implementation(project(":chain:alt"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // AndroidX 基础
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.window)
    implementation(libs.androidx.biometric)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    // app 模块自己不含 @Database，但要在 AppModule 里构造 LotusDatabase，
    // 因此需要 runtime/ktx 出现在编译类路径上；DAO 与实体由 core:database 提供。
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    // 协程 / 序列化
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // 二维码
    implementation(libs.zxing.core)
    implementation(libs.coil.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
