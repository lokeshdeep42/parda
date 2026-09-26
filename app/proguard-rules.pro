# kotlinx.serialization: keep generated serializers for the policy and ledger types.
-keepclassmembers class app.parda.core.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.parda.core.**$$serializer { *; }

# Native code calls TokenSink.onToken by name while the model streams an answer.
-keep interface app.parda.llm.TokenSink { *; }
