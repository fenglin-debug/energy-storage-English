import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

// Adds Compose support on top of an Android module (app or library) that already
// applies an AGP plugin. Enables the compose build feature, applies the Compose
// compiler plugin, and adds the BOM.
plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

// Enable the compose build feature on whichever AGP extension is present.
pluginManager.withPlugin("com.android.application") {
    extensions.configure<ApplicationExtension>("android") {
        buildFeatures {
            compose = true
        }
    }
}
pluginManager.withPlugin("com.android.library") {
    extensions.configure<LibraryExtension>("android") {
        buildFeatures {
            compose = true
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.06.01")
    add("implementation", bom)
    add("androidTestImplementation", bom)
}
