// Convention plugin adding Hilt DI (applied where DI is needed: app + feature modules).
plugins {
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

dependencies {
    add("implementation", "com.google.dagger:hilt-android:2.60")
    add("ksp", "com.google.dagger:hilt-android-compiler:2.60")
}
