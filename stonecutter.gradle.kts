plugins {
    id("dev.kikugie.stonecutter")
    // Loaded once here, not applied, so all version nodes share one copy of each plugin.
    id("net.fabricmc.fabric-loom-remap") version "1.17.21" apply false
    id("net.neoforged.moddev") version "2.0.148" apply false
}

// The version whose code is active in src/. The "Set active project to ..." tasks rewrite this line.
stonecutter active "1.21.11"

stonecutter parameters {
    // Mojang renamed ResourceLocation to Identifier in 1.21.11. Sources use the new name;
    // older versions get the old one back, including readIdentifier/readResourceLocation and friends.
    replacements.string(current.parsed < "1.21.11") {
        replace("Identifier", "ResourceLocation")
    }
}
