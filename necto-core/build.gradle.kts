plugins {
    kotlin("jvm")
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Panels live in src/main/panels/<id>. They are packaged as Java resources under
// necto/panels/<id>, each with a files.txt index, because resource folders cannot be
// listed portably at run time (an APK is not a directory).
val generatePanelResources by tasks.registering {
    val source = layout.projectDirectory.dir("src/main/panels")
    val output = layout.buildDirectory.dir("generated/panelResources")
    inputs.dir(source)
    outputs.dir(output)
    doLast {
        val root = output.get().asFile.resolve("necto/panels")
        root.deleteRecursively()
        source.asFile.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }.forEach { panel ->
            val destination = root.resolve(panel.name)
            val paths = panel.walkTopDown()
                .filter { it.isFile && !it.name.startsWith(".") }
                .map { it.relativeTo(panel).invariantSeparatorsPath }
                .sorted()
                .toList()
            paths.forEach { path -> panel.resolve(path).copyTo(destination.resolve(path), overwrite = true) }
            destination.resolve("files.txt").writeText(paths.joinToString("\n", postfix = "\n"))
        }
    }
}

sourceSets.main {
    resources.srcDir(generatePanelResources)
}
