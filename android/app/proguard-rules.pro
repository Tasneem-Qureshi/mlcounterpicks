# OpenCV loads native code reflectively.
-keep class org.opencv.** { *; }
# Room entities / DAOs are referenced by generated code.
-keep class com.dripagency.mlbbdraft.data.db.** { *; }
