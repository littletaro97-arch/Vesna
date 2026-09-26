plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFile = providers.environmentVariable("VESNA_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("VESNA_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("VESNA_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("VESNA_RELEASE_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.littletaro.vesna"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.littletaro.vesna"
        minSdk = 26
        targetSdk = 35
        // 可用 -PversionCode=xx -PversionName=x.y 覆盖（构建测试包时不动源文件）。
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 8
        versionName = (project.findProperty("versionName") as String?) ?: "1.5.2"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

// Do not let an unsigned APK slip into a public release by accident.
tasks.configureEach {
    if (name.startsWith("packageRelease") || name.startsWith("bundleRelease")) {
        doFirst {
            check(releaseSigningConfigured) {
                "Release outputs must be signed. Set VESNA_RELEASE_STORE_FILE, " +
                    "VESNA_RELEASE_STORE_PASSWORD, VESNA_RELEASE_KEY_ALIAS, and " +
                    "VESNA_RELEASE_KEY_PASSWORD."
            }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
