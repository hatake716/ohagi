# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class io.github.hatake716.ohagi.**$$serializer { *; }
-keepclassmembers class io.github.hatake716.ohagi.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.hatake716.ohagi.** {
    kotlinx.serialization.KSerializer serializer(...);
}
