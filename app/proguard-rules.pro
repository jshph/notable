# Notable ProGuard/R8 rules


# ── Room database entities & DAOs ──
-keep class com.ethran.notable.data.db.** { *; }

# ── Kotlin serialization ──
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class com.ethran.notable.** {
    *** Companion;
    *** serializer(...);
}
-keep class com.ethran.notable.**$$serializer { *; }
-keepclasseswithmembers class com.ethran.notable.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Onyx SDK (accessed via reflection by the device firmware) ──
-keep class com.onyx.** { *; }
-dontwarn com.onyx.**

# ── HWR Parcelables (IPC with ksync service) ──
-keep class com.onyx.android.sdk.hwr.service.** { *; }

# ── Hilt / Dagger ──
-dontwarn dagger.**
-keep class dagger.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# ── Firebase ──
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ── ShipBook logging ──
-keep class io.shipbook.** { *; }
-dontwarn io.shipbook.**

# ── MuPDF (native JNI) ──
-keep class com.artifex.mupdf.fitz.** { *; }

# ── Jetpack Ink (native JNI) ──
-keep class androidx.ink.** { *; }

# ── Coil (image loading, uses reflection) ──
-dontwarn coil.**

# ── RxJava ──
-dontwarn io.reactivex.**

# ── LZ4 (native loader uses reflection) ──
-keep class net.jpountz.** { *; }
-dontwarn net.jpountz.**

# ── Apache Commons Compress ──
-dontwarn org.apache.commons.compress.**

# ── Kotlin enums (used by name in serialization/Room converters) ──
-keepclassmembers enum com.ethran.notable.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Keep Parcelable CREATOR fields ──
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ── Keep Android entry points (activities, services, receivers) ──
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.inputmethodservice.InputMethodService

# ── Hidden API bypass (loaded by reflection) ──
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**

# ── MMKV (used by Onyx SDK, native JNI) ──
-keep class com.tencent.mmkv.** { *; }

# ── Standard suppressions ──
-dontwarn javax.**
-dontwarn org.joda.**
-dontwarn sun.misc.Unsafe
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.StaticMDCBinder
-dontwarn org.slf4j.impl.StaticMarkerBinder

