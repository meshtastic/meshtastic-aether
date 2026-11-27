# R8 9.3.16 miscompiles Composer.a into a VerifyError on ART (b/573664753, fixed in R8 9.3.35); drop once the toolchain ships a newer R8.
-keep class org.meshtastic.aether.music.Composer { *; }
