-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep Hidden Android Framework ActivityView (System PIP API)
-keep class android.app.ActivityView { *; }
-keep class android.app.ActivityView$* { *; }
-keepclassmembers class android.app.ActivityView { *; }

# Keep OpenLauncher Data, Models, and Settings
-keep class com.openlauncher.app.data.** { *; }
-keepclassmembers class com.openlauncher.app.data.** { *; }
-keep class com.openlauncher.app.model.** { *; }
-keepclassmembers class com.openlauncher.app.model.** { *; }

# Keep Retrofit & Gson Serialized Classes
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep Retrofit interfaces
-keep interface com.openlauncher.app.data.WeatherApiService { *; }
-keep interface com.openlauncher.app.data.NominatimApiService { *; }

# Keep Coil Image Loader
-dontwarn coil.**
-keep class coil.** { *; }
