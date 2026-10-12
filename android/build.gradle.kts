// AGP 9+ ships built-in Kotlin support, so no org.jetbrains.kotlin.android plugin is applied.
// Compose compiler + KSP must match the Kotlin version bundled with AGP 9.3 (2.2.10).
plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
}
