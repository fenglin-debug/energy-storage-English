plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = BessModuleConfig.JAVA_VERSION
    targetCompatibility = BessModuleConfig.JAVA_VERSION
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(BessModuleConfig.JVM_TARGET))
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
