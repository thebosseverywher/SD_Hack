// Top-level build file. Plugin versions are declared here with `apply false`
// and applied in the module build scripts.
plugins {
    id("com.android.application") version "8.5.2" apply false
    // Kotlin 2.3.x is required to consume LiteRT-LM (litertlm-android is compiled with
    // Kotlin 2.3 metadata; the old 1.9.24 compiler can't read it). Compose now uses the
    // dedicated compiler plugin (versioned with Kotlin) instead of kotlinCompilerExtensionVersion.
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
