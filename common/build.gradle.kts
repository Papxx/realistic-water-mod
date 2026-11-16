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

// JMH benchmarks of the physics core (Plan.md §6.5) in src/jmh. `check` compiles them so they keep building; they only
// run on request: ./gradlew :common:1.21.11:jmh, with JMH options in -Pjmh="..." (e.g. -Pjmh="-p particles=16384").
val jmh: SourceSet = sourceSets.create("jmh") {
    // The benchmarks reuse the hand-built test worlds of src/test (dev.tore.fluidphysics.physics.TestWorlds).
    val main = sourceSets.main.get()
    val test = sourceSets.test.get()
    compileClasspath += main.output + test.output + main.compileClasspath
    runtimeClasspath += main.output + test.output + main.runtimeClasspath
}

dependencies {
    "jmhImplementation"("org.openjdk.jmh:jmh-core:${prop("jmh_version")}")
    "jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:${prop("jmh_version")}")
}

tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs the JMH benchmarks in src/jmh. Pass JMH options with -Pjmh=\"...\"."
    classpath = jmh.runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(prop("java_version").toInt()) }
    val options = providers.gradleProperty("jmh").orElse("")
    argumentProviders.add(CommandLineArgumentProvider { options.get().split(' ').filter { it.isNotBlank() } })
    // Minecraft classes may write logs/ into the working directory; keep it inside build/.
    val runDir = layout.buildDirectory.dir("jmh-run")
    workingDir(runDir)
    doFirst { runDir.get().asFile.mkdirs() }
}

tasks.check {
    dependsOn(jmh.classesTaskName)
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
    // Recipes differ between versions (1.21.2 changed the ingredient format), so each version has its own folder.
    add(commonResources.name, file("recipes/${prop("minecraft_version")}"))
}

// Apache-2.0 §4: ship LICENSE and NOTICE in the root of every jar.
tasks.jar {
    from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
}
