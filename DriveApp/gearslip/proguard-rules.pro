# R8 shrinks the release build; these keep what it can't see being used.

# Names stay readable, so stack traces in shared logs need no mapping file. Shrinking does
# the real work on size.
-dontobfuscate

# Gearslip hosts other apps' car screens: their templates arrive as Bundles, and the car
# library rebuilds them by class name and reflection. Any model class an app might send has to
# survive, used by Gearslip's code or not.
-keep class androidx.car.app.** { *; }

# Speech-to-text: the native library calls back into these classes by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# --- Dashline ---------------------------------------------------------------------------------
# Vela's engine and what it links (OsmAnd router, Rhino JS for calibration transforms, trove,
# kxml2, commons-logging) use reflection and serialization: kept whole, never shrunk or renamed.
-keep class app.vela.** { *; }
-keep class net.osmand.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class gnu.trove.** { *; }
-keep class org.kxml2.** { *; }
-keep class org.xmlpull.** { *; }
-keep class org.apache.commons.logging.** { *; }
-keep class dagger.** { *; }
-keep class javax.inject.** { *; }
-keep class org.maplibre.** { *; }
-dontwarn net.osmand.**
-dontwarn org.mozilla.javascript.**
-dontwarn org.apache.commons.logging.**
-dontwarn javax.**
-dontwarn java.awt.**
-dontwarn org.slf4j.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.avalon.**
-dontwarn org.apache.log.**
-dontwarn com.google.protobuf.**
-dontwarn net.osmand.shaded.**
-dontwarn org.json.**
-dontwarn org.w3c.**
-dontwarn org.xml.**
-dontwarn sun.**
-dontwarn kotlinx.serialization.**
-dontwarn com.google.errorprone.annotations.**
