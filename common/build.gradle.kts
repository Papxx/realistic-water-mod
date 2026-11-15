plugins {
    id("net.fabricmc.fabric-loom-remap")
}

fun prop(name: String): String = property(name).toString()

// Every node of one Minecraft version is named after that version (e.g. "1.21.1"), and Gradle identifies
// project dependencies by group:name. Without a per-module group, :fabric:1.21.1 would resolve
// :common:1.21.1 to itself.
group = "${prop("mod_group")}.common"
version = "${prop("mod_version")}+mc${prop("minecraft_version")}"
base.archivesName = "${prop("mod_id")}-common"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(prop("java_version").toInt())
}

dependencies {
    // Vanilla Minecraft only: nothing loader-specific may leak into common code.
    minecraft("com.mojang:minecraft:${prop("minecraft_version")}")
    mappings(loom.officialMojangMappings())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// The loader modules compile the common sources together with their own, so hand them the source
// directories instead of a jar. For the inactive Minecraft version these are Stonecutter's generated copies.
val commonJava = configurations.create("commonJava") {
    isCanBeResolved = false
    isCanBeConsumed = true
}
val commonResources = configurations.create("commonResources") {
    isCanBeResolved = false
    isCanBeConsumed = true
}

artifacts {
    val generate = tasks.named("stonecutterGenerate")
    val main = sourceSets.main.get()
    main.java.srcDirs.forEach { add(commonJava.name, it) { builtBy(generate) } }
    main.resources.srcDirs.forEach { add(commonResources.name, it) { builtBy(generate) } }
}
