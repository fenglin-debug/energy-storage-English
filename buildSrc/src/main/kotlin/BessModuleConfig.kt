import org.gradle.api.JavaVersion

/**
 * Shared module configuration constants for the BESS Sales Trainer project.
 * Single source of truth for SDK levels and JVM target across all modules.
 */
object BessModuleConfig {
    const val COMPILE_SDK = 36
    const val MIN_SDK = 26
    const val TARGET_SDK = 36

    val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17
    const val JVM_TARGET = "17"

    const val APPLICATION_ID = "com.bess.salestrainer"
    const val VERSION_CODE = 5
    const val VERSION_NAME = "0.4.0"

    const val TEST_INSTRUMENTATION_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
}
