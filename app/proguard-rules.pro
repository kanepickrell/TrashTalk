# Keep model classes used for Firestore (de)serialisation via reflection.
-keep class com.trashtalk.data.model.** { *; }
-keepclassmembers class com.trashtalk.data.model.** { <init>(...); }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-keepclassmembers class **$* {
    kotlinx.serialization.KSerializer serializer(...);
}
