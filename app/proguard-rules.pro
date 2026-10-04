# Rock Edit ProGuard rules.
# Keep exception messages useful for crash diagnostics.
-keepattributes SourceFile,LineNumberTable,Exceptions

# juniversalchardet is accessed reflectively? No - direct use. Keep package info only.
-dontwarn org.mozilla.universalchardet.**

# ---- Storage Manager (v0.6.0) ----
# sshj pulls BouncyCastle and JNR; R8 full mode needs generous keeps here.
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn org.ietf.jgss.**
-dontwarn java.naming.**
-dontwarn javax.naming.**
-dontwarn javax.security.auth.**
-dontwarn javax.security.auth.login.**
-dontwarn javax.security.sasl.**
-dontwarn sun.**
-dontwarn com.jcraft.jzlib.**
-dontwarn org.apache.sshd.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.sshj.** { *; }
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn com.hierynomus.**
# commons-net: direct use, but keep its parser classes intact just in case.
-keep class org.apache.commons.net.** { *; }
-dontwarn org.apache.commons.net.**
