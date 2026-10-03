import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ---- 签名配置 ----
// keystore.properties 不入库（.gitignore 覆盖），里面是项目外 keystore 的路径与口令。
// 刻意"缺文件即报错"而不是静默退回调试签名：静默退回会签出
// "装得上、但以后升级不了的包"，问题会拖到换机/正式录入时才爆出来。
val keystorePropertiesFile = rootProject.file("keystore.properties")
check(keystorePropertiesFile.exists()) {
    "缺少 keystore.properties —— 它是签名配置，构建必需品（不入库）。" +
        "格式说明与恢复方式见 docs/HANDOFF.md。"
}
val keystoreProperties = Properties().apply {
    keystorePropertiesFile.reader(Charsets.UTF_8).use { load(it) }
}

android {
    namespace = "io.github.hexgmskr.noteone"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.hexgmskr.noteone"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 正式钥匙在项目外（具体路径由 keystore.properties 指定，不入库）
        create("release") {
            storeFile = keystoreProperties.getProperty("storeFile")?.let { file(it) }
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        // debug 与 release 刻意共用同一把正式钥匙：身份从第一天起就是正式身份，
        // 之后所有构建都能直接覆盖升级，不会再出现"换签名 = 卸载重装 = 清数据"。
        // 代价：构建机上 keystore.properties 存有口令；换机器时它要和 keystore 一起搬。
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    sourceSets {
        // MigrationTestHelper 从测试 APK 的 assets/schemas/ 读历史 schema JSON，
        // 不配这条它找不到 1.json，迁移回归测试无法构造旧版本库。
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}

// Room 把每个版本的表结构导出成 JSON，交给 MigrationTestHelper 做迁移回归。
// 这些 JSON 必须入库：丢了就无法对历史版本构造测试库。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// ---- kotlinx-serialization 版本分裂修复（仅 androidTest）----
//
// 现象：MigrationTest 抛 AbstractMethodError
//   "GeneratedSerializer.typeParametersSerializers()"
//
// 根因：androidx.room:room-migration 2.8.5 明确依赖 kotlinx-serialization-json 1.8.1，
// 而 Compose BOM 把 kotlinx-serialization-bom 压成 {strictly 1.7.3}，
// 经 Gradle 跨配置一致解析传导到 androidTest，于是 json 1.8.1 对上了 core 1.7.3，
// 而 typeParametersSerializers() 是 1.8.x 才有的方法。
//
// 方向：升到 1.8.1 对齐 Room 的编译目标，而不是把 Room 压回 1.7.3。
//
// 作用域收窄到 androidTestConfiguration 的原因：room-migration 只被 room-testing
// 引入，主应用根本用不到 kotlinx-serialization，没必要动主 classpath。
//
// 用 force 是因为普通依赖声明压不过 BOM 的 strictly 约束。
// 用 configureEach 而非 named()：androidTest 的 configuration 由 AGP 延后创建，
// build script 求值时 named() 找不到会直接抛异常。
// 债务：等 Compose BOM 升到序列化 1.8.x 后，删掉这块验证是否恢复正常。
// 见 CLAUDE.md「已知债务」。
configurations.configureEach {
    if (name.contains("AndroidTest")) {
        resolutionStrategy.force(
            "org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1",
            "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.8.1",
            "org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1",
            "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.8.1",
            "org.jetbrains.kotlinx:kotlinx-serialization-json-okio:1.8.1",
        )
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // 本地加密数据库：Room + SQLCipher
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.net.zetetic.sqlcipher.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}