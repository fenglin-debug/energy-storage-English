plugins {
    id("com.android.application")
    id("bess-android-compose")
    id("bess-android-hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.bess.salestrainer"
    compileSdk = BessModuleConfig.COMPILE_SDK

    defaultConfig {
        applicationId = BessModuleConfig.APPLICATION_ID
        minSdk = BessModuleConfig.MIN_SDK
        targetSdk = BessModuleConfig.TARGET_SDK
        versionCode = BessModuleConfig.VERSION_CODE
        versionName = BessModuleConfig.VERSION_NAME
        testInstrumentationRunner = BessModuleConfig.TEST_INSTRUMENTATION_RUNNER
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            buildConfigField("boolean", "USE_FAKE_REPOS", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "USE_FAKE_REPOS", "false")
            ndk { abiFilters += listOf("arm64-v8a") }

            val keystorePath = System.getenv("BESS_KEYSTORE")
            if (keystorePath != null) {
                signingConfig = signingConfigs.create("release").apply {
                    storeFile = file(keystorePath)
                    storePassword = System.getenv("BESS_STORE_PASSWORD") ?: ""
                    keyAlias = System.getenv("BESS_KEY_ALIAS") ?: "bess"
                    keyPassword = System.getenv("BESS_KEY_PASSWORD") ?: ""
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = BessModuleConfig.JAVA_VERSION
        targetCompatibility = BessModuleConfig.JAVA_VERSION
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(BessModuleConfig.JVM_TARGET))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Feature modules
    implementation(project(":feature:home"))
    implementation(project(":feature:vocabulary"))
    implementation(project(":feature:scenario"))
    implementation(project(":feature:settings"))

    // Core modules
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":core:audio"))
    implementation(project(":core:corpus"))
    implementation(project(":core:designsystem"))

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
