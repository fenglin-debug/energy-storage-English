plugins {
    `kotlin-dsl`
}

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    google()
    mavenCentral()
}

dependencies {
    // Plugin implementation artifacts so convention plugins can reference them.
    // NOTE: no org.jetbrains.kotlin.android — AGP 9 built-in Kotlin handles Android Kotlin compilation.
    implementation("com.android.tools.build:gradle:9.2.1")
    implementation("org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.3.10")
    implementation("com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:2.3.10")
    implementation("com.google.dagger:hilt-android-gradle-plugin:2.60")
}
