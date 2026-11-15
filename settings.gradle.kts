import java.util.Properties

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    // Lets Gradle download the JDK requested by the Java toolchain when it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "fluidphysics"

// One Gradle project per (module, Minecraft version), e.g. :common:1.21.1, :fabric:1.21.11.
// Sources live in <module>/src; the node directories <module>/versions/<mc>/ only hold build output.
stonecutter {
    create(rootProject) {
        // The version the sources in src/ are written for when committed.
        vcsVersion = "1.21.11"
        for (module in listOf("common", "fabric", "neoforge")) {
            branch(module) { versions("1.21.1", "1.21.11") }
        }
    }
}

// The dependency matrix of each Minecraft version lives in versions/<mc>/gradle.properties.
// Those directories are not Gradle projects, so hand the values to every node of that version.
gradle.beforeProject {
    val matrix = rootDir.resolve("versions/$name/gradle.properties")
    if (matrix.isFile) {
        val properties = Properties().apply { matrix.inputStream().use(::load) }
        properties.forEach { (key, value) -> extensions.extraProperties[key.toString()] = value }
    }
}
