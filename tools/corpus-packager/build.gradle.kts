plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
    application
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

application {
    mainClass.set("com.bess.packager.MainKt")
}

dependencies {
    implementation(libs.poi.ooxml)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
