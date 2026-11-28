# R8 9.3.16 miscompiles Composer.a into a VerifyError on ART (b/573664753, fixed in R8 9.3.35); drop once the toolchain ships a newer R8.
-keep class org.meshtastic.aether.music.Composer { *; }

# The Jetpack XR renderer calls the platform's XR extensions, which only a headset provides. R8 needs their API as a
# library to keep the interface methods SceneCore's lambdas implement; without it the release build aborts at session
# creation (AbstractMethodError on Consumer.accept). The jar is com.android.extensions.xr:extensions-xr 1.5.0
# (Apache-2.0) from Google Maven, used at build time only; the Kotlin Toolchain hands compile-only jars to no R8 run.
-libraryjars r8/extensions-xr-1.5.0.jar
-dontwarn android.extensions.xr.**
