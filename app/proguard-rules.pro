#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
# R8/ProGuard rules — tightened for APK size research
# Last updated: 2026-08-17 (research/apk-size-reduction branch)

# ---- Annotations (must come first) ----
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod

# ---- Hilt / Dagger ----
-keep class dagger.hilt.** { *; }
-keep class dagger.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }
# Hilt component interfaces generated at compile time
-keep @dagger.hilt.InstallIn class * { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }

# ---- Room ----
# Entities (used via reflection by Room)
-keep class io.payanam.database.entity.** { *; }
# DAOs (accessed via generated implementations)
-keep interface io.payanam.database.dao.** { *; }
-keep class io.payanam.database.dao.**_Impl { *; }
# Room generated _Impl classes
-keep class **_Impl extends androidx.room.RoomDatabase { *; }
-keep class **_Impl { *; }
-dontwarn androidx.room.**

# ---- SQLCipher (native JNI bridge; must not be stripped) ----
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }
-dontwarn net.zetetic.**
-dontwarn net.sqlcipher.**

# ---- AndroidX Biometric ----
-keep class androidx.biometric.** { *; }
-dontwarn androidx.biometric.**

# ---- WorkManager ----
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn androidx.work.**

# ---- Kotlin Coroutines (narrowed) ----
# Keep core runtime, strip debug infrastructure
-keep class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keep class kotlinx.coroutines.CoroutineScope { *; }
-keep class kotlinx.coroutines.Dispatchers { *; }
-keep class kotlinx.coroutines.Job { *; }
-keep class kotlinx.coroutines.MainCoroutineDispatcher { *; }
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-dontwarn kotlinx.coroutines.**
# Coroutines debug infrastructure is not needed in release
-assumenosideeffects class kotlinx.coroutines.debug.** { *; }

# ---- Kotlin (narrowed — was: -keep class kotlin.** { *; }) ----
# Keep only what's actually needed for runtime behavior
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Metadata { *; }
-keep class kotlin.reflect.jvm.internal.** { *; }
-keep class kotlin.jvm.functions.** { *; }
-keep class kotlin.jvm.internal.** { *; }
-keep class kotlin.sequences.GeneratorSequence { *; }
-dontwarn kotlin.**

# ---- Compose (narrowed — was: -keep class androidx.compose.** { *; }) ----
# Keep runtime essentials; let R8 strip unused material-icons-extended classes
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.animation.** { *; }
-keep class androidx.compose.foundation.** { *; }
# NOTE: intentionally NOT keeping androidx.compose.material.** to let R8 strip
# unused material-icons-extended classes (46K seeds → ~40 used icons)
-keep class **ComposedClass { *; }
-dontwarn androidx.compose.**

# ---- Vico charts (narrowed — was: -keep class com.patrykandpatrick.vico.** { *; }) ----
-keep class com.patrykandpatrick.vico.compose.** { *; }
-keep class com.patrykandpatrick.vico.core.** { *; }
-dontwarn com.patrykandpatrick.vico.**

# ---- Timber (logging — strip debug logs in release) ----
# NOTE: the parameter types must be explicit — an unqualified "d(...)"
# wildcard made R8 match java.lang.Object methods (equals/hashCode/
# toString) and strip them app-wide, crashing the app at startup.
-assumenosideeffects class timber.log.Timber {
    public static *** d(java.lang.String, ...) ;
    public static *** v(java.lang.String, ...) ;
}

# ---- Kotlin serialization (future-proofing) ----
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Suppress known harmless warnings from transitive deps ----
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
