# R8 keeps what the code reaches directly. These keep what is reached by name
# only. The libraries bring their own rules as well (the Claude SDK's for
# Jackson, OkHttp's, Room's, WorkManager's for its workers).

# Readable stack traces: file and line numbers, under one generic file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# SQLCipher: its native library calls back into these classes by name.
-keep class net.zetetic.database.** { *; }

# Conscrypt: native TLS (post-quantum key exchange). OkHttp finds it by class
# name, and its native code calls back into Java by name.
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# BouncyCastle: only its lightweight API is used, directly (J-PAKE, ML-KEM);
# other parts refer to JDK classes Android doesn't have.
-dontwarn org.bouncycastle.**
