-keep class org.klab.batteryinfo.BatteryInfoEnabler { *; }
-keep class io.github.libxposed.** { *; }
-keep interface io.github.libxposed.** { *; }

-keepclassmembernames class * {
    @androidx.annotation.Keep <methods>;
}