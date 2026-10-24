# R8 rules for the release build (see app/build.gradle.kts and docs/FDROID.md section 2).
# Library consumer rules (Compose, DataStore, kotlinx.serialization) are applied automatically;
# the rules below cover what the app itself depends on at runtime.

# Keep line numbers and source file names so CrashLog reports stay readable after retracing
# with app/build/outputs/mapping/release/mapping.txt.
-keepattributes SourceFile,LineNumberTable

# Annotations and generic signatures are needed by kotlinx.serialization at runtime.
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature,AnnotationDefault

# kotlinx.serialization: serializers are looked up through generated `$$serializer` classes,
# `Companion.serializer()` and `INSTANCE.serializer()`. The library's consumer rules already keep
# these; the explicit rules make the intent visible and cover every @Serializable class in
# :core (content DTOs, engine/BattleCodec models, puzzle model) and :app (Settings, Profile,
# SavedMatch, backups, logbook, FreePlayState, PuzzleSnapshot, ...).
-keep class pt.tiago.nightfleet.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class pt.tiago.nightfleet.** {
    static ** Companion;
    public static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.**

# Enums: values() and valueOf(String) are called by name (OpponentLevel.valueOf, AiTier.valueOf,
# Difficulty.valueOf, ContentDifficulty.valueOf, PuzzleGrade.valueOf). The constant names come from
# Enum.name, which R8 does not rename, so the JSON names stay valid.
-keepclassmembers enum pt.tiago.nightfleet.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# CrashLog installs the uncaught-exception handler from the Application class; the manifest
# already keeps it, this makes the dependency explicit.
-keep class pt.tiago.nightfleet.App { *; }
-keep class pt.tiago.nightfleet.crash.CrashLog { *; }
