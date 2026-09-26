# kotlinx.serialization: keep generated serializers for the policy and ledger types.
-keepclassmembers class app.parda.core.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.parda.core.**$$serializer { *; }
