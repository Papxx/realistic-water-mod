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
    // Mixin annotations only: Fabric Loader and NeoForge both ship Mixin at runtime.
    compileOnly("net.fabricmc:sponge-mixin:${prop("mixin_version")}")

    testImplementation(platform("org.junit:junit-bom:${prop("junit_version")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    // The solver benchmark needs a stable heap; keep the fork small but fixed.
    maxHeapSize = "1G"
    // Minecraft's bootstrap (ChunkVoxelCacheTest) writes logs/ into the working directory; keep it inside build/.
    val runDir = layout.buildDirectory.dir("test-run")
    workingDir(runDir)
    doFirst { runDir.get().asFile.mkdirs() }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
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

// Apache-2.0 §4: ship LICENSE and NOTICE in the root of every jar.
tasks.jar {
    from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
}
