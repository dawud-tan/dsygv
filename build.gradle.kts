plugins {
    alias(libs.plugins.android.application) apply false

    // NEVER APPLIED -- `apply false` only puts Kotlin Gradle Plugin 2.4.10 on
    // the buildscript classpath. Two things depend on that:
    //   1. AGP 9's built-in Kotlin compiles with the KGP it finds there, so
    //      this is what actually pins the compiler to 2.4.10. Drop it and you
    //      silently fall back to the 2.2.10 that AGP bundles.
    //   2. app/build.gradle.kts references KotlinVersion.KOTLIN_2_4, which
    //      does not exist in 2.2.10 (that enum stops at KOTLIN_2_3).
    // Do NOT apply it -- AGP 9.0+ hard-errors on the Kotlin Android plugin.
    alias(libs.plugins.kotlin.android) apply false
}