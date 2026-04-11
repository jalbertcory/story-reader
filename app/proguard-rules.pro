# Room — keep entity classes referenced by the schema
-keep class com.storyreader.data.db.entity.** { *; }
-keep class com.storyreader.data.db.dao.** { *; }
-keep class com.storyreader.data.db.AppDatabase { *; }
-keep class com.storyreader.data.db.converter.** { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# dav4jvm — uses XmlPullParser reflectively
-keep class at.bitfire.dav4jvm.** { *; }

# Readium — keep public API surface
-keep class org.readium.** { *; }
-dontwarn org.readium.**

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Android Keystore crypto classes
-keep class android.security.keystore.** { *; }

# Keep Kotlin metadata for coroutines
-keepattributes RuntimeVisibleAnnotations

# Google Play Services Auth
-keep class com.google.android.gms.auth.** { *; }
-dontwarn com.google.android.gms.**
