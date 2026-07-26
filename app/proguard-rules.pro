# BESS Sales Trainer — release R8 rules (主 Agent 独占)

# ---- Kotlinx Serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$serializer { *; }
-keep,includedescriptorclasses class com.bess.salestrainer.**$$serializer { *; }
-keepclassmembers class com.bess.salestrainer.** {
    *** Companion;
}
-keepclasseswithmembers,includedescriptorclasses class com.bess.salestrainer.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Retrofit / OkHttp ----
-keepattributes Signature, Exceptions
-keep,allowobfuscation interface retrofit2.** { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ---- Coroutines ----
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# ---- sherpa-onnx JNI (added in Track C; keep unconditionally is safe) ----
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# ---- Hilt ----
-dontwarn dagger.hilt.**

# App domain models used by reflection/serialization are covered above.
