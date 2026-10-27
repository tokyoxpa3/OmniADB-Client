# libadb 走反射與 BouncyCastle provider，release 目前沒開 minify，
# 保留規則只是為了日後真的要開時不必重新查一輪。
-keep class io.github.muntashirakon.adb.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.conscrypt.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
