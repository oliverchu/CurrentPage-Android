import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 签名信息只放在 local.properties（已 gitignore），仓库里不保留任何密码/密钥
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val releaseStoreFile = localProperties.getProperty("RELEASE_STORE_FILE")
    ?.let { rootProject.file(it) }
val hasReleaseSigning = releaseStoreFile?.exists() == true

android {
    namespace = "com.oliver.app.currentpage"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.oliver.app.currentpage"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
                // minSdk 26 不需要 v1；v2 + v3 覆盖系统要求
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // 本地配置了 release 签名时，debug 也使用同一把 key，
            // 方便 debug/release 互相覆盖安装；CI 无配置时回退默认 debug key。
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                // 不指定 packageScope：AGP 9 的跨包重打包会把 Kotlin 集合类
                // 挪进 kotlin.collections，导致 release 启动时 IllegalAccessError。
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
