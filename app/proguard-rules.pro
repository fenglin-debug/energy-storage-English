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

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ---- Coroutines ----
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# ---- Hilt ----
-dontwarn dagger.hilt.**

# App domain models used by reflection/serialization are covered above.
