plugins {
    id("net.fabricmc.fabric-loom-remap")
}

fun prop(name: String): String = property(name).toString()

// The :common node built for the same Minecraft version as this node.
val commonPath: String = stonecutter.node.sibling("common")!!.hierarchy.toString()

// Every node of one Minecraft version is named after that version (e.g. "1.21.1"), and Gradle identifies
// project dependencies by group:name. Without a per-module group, :fabric:1.21.1 would resolve
// :common:1.21.1 to itself.
group = "${prop("mod_group")}.fabric"
version = "${prop("mod_version")}+mc${prop("minecraft_version")}"
base.archivesName = "${prop("mod_id")}-fabric"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(prop("java_version").toInt())
}

val commonJava = configurations.create("commonJava") {
    isCanBeResolved = true
    isCanBeConsumed = false
}
val commonResources = configurations.create("commonResources") {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    minecraft("com.mojang:minecraft:${prop("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${prop("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${prop("fabric_api_version")}")

    // Common code is compiled into this module from source (see below). The project dependency
    // only exists so IDEs resolve common classes from loader code.
    compileOnly(project(path = commonPath, configuration = "namedElements"))
    commonJava(project(path = commonPath, configuration = "commonJava"))
    commonResources(project(path = commonPath, configuration = "commonResources"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.compileJava {
    source(commonJava)
}

tasks.processResources {
    from(commonResources)

    val metadata = mapOf(
        "mod_id" to prop("mod_id"),
        "mod_name" to prop("mod_name"),
        "mod_version" to version.toString(),
        "mod_author" to prop("mod_author"),
        "mod_license" to prop("mod_license"),
        "mod_homepage" to prop("mod_homepage"),
        "mod_description" to prop("mod_description"),
        "minecraft_version" to prop("minecraft_version"),
        "fabric_loader_version" to prop("fabric_loader_version"),
        "java_version" to prop("java_version"),
    )
    inputs.properties(metadata)
    filesMatching("fabric.mod.json") { expand(metadata) }
}

// Apache-2.0 §4: ship LICENSE and NOTICE in the root of every jar.
tasks.jar {
    from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
}
