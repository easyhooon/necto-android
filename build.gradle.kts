plugins {
    // Declared once so every module shares the same Kotlin Gradle Plugin. The Android
    // Gradle Plugin is declared in necto-android alone, so the JVM modules build without it.
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
}

allprojects {
    group = "io.github.easyhooon.necto"
    version = "0.1.0"
}
