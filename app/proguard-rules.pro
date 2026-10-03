# Rock Edit ProGuard rules.
# Keep exception messages useful for crash diagnostics.
-keepattributes SourceFile,LineNumberTable,Exceptions

# juniversalchardet is accessed reflectively? No - direct use. Keep package info only.
-dontwarn org.mozilla.universalchardet.**
