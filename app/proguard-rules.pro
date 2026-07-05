# Keep Room entities and generated code
-keep class com.agentchat.data.** { *; }

# Readable crash stack traces after obfuscation
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization — keep generated serializers for our @Serializable DTOs
# (Anthropic API models) so encode/decode keeps working after shrinking.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.agentchat.** {
    *** Companion;
}
-keepclasseswithmembers class com.agentchat.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.agentchat.**$$serializer { *; }
