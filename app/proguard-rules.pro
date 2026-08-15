#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

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
-keep class io.payanam.core.database.entities.** { *; }
# DAOs (accessed via generated implementations)
-keep interface io.payanam.core.database.dao.** { *; }
-keep class io.payanam.core.database.dao.**_Impl { *; }
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
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn androidx.work.**

# ---- Kotlin Coroutines ----
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
# Coroutines debug infrastructure is not needed in release
-assumenosideeffects class kotlinx.coroutines.debug.** { *; }

# ---- Kotlin (general) ----
-keep class kotlin.** { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Metadata { *; }

# ---- Compose ----
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ---- Vico charts ----
-keep class com.patrykandpatrick.vico.** { *; }
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
