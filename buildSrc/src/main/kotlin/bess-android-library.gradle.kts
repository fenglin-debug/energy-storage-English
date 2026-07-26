import com.android.build.api.dsl.LibraryExtension

// Convention plugin for Android library modules (core/*, feature/*) using AGP 9 built-in Kotlin.
// NOTE: do NOT apply org.jetbrains.kotlin.android — AGP 9 compiles Kotlin via built-in support.
plugins {
    id("com.android.library")
}

extensions.configure<LibraryExtension>("android") {
    namespace = findProperty("bess.namespace") as? String
        ?: error("Module must set bess.namespace in gradle.properties")
    compileSdk = BessModuleConfig.COMPILE_SDK

    defaultConfig {
        minSdk = BessModuleConfig.MIN_SDK
        testInstrumentationRunner = BessModuleConfig.TEST_INSTRUMENTATION_RUNNER
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = BessModuleConfig.JAVA_VERSION
        targetCompatibility = BessModuleConfig.JAVA_VERSION
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(BessModuleConfig.JVM_TARGET))
    }
}
