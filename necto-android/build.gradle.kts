plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

android {
    namespace = "io.github.easyhooon.necto.android"
    compileSdk = 35

    defaultConfig {
        // java.util.Base64 and file creation times need API 26.
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":necto-core"))
    api("androidx.datastore:datastore-preferences-core:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifactId = project.name
            afterEvaluate { from(components["release"]) }
        }
    }
}
