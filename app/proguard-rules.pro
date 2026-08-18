# Shelfit Sentinel keeps no reflective entry points beyond the Android framework
# defaults, so the default AGP/R8 configuration is sufficient today.
#
# Add rules here if a future trigger detector or action executor is instantiated
# reflectively (for example, if detectors are ever resolved by class name).

# Enum constant names are persisted.
#
# SmartHomeProvider, TuyaRegion and SensitivityLevel are all stored in DataStore by
# `name` and read back with `fromName`. The compiler passes those names to the Enum
# constructor as string literals, so R8 renaming the fields would not actually change
# what `.name` returns — but relying on that is relying on a compilation detail to
# protect a user's saved settings across an app update. Keeping the fields is free and
# removes the question.
-keepclassmembers enum com.shelfit.sentinel.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
