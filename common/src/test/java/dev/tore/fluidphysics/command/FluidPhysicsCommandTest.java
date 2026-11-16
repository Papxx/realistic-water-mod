// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FluidPhysicsCommandTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void registersTheSubcommandTree() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        FluidPhysicsCommand.register(dispatcher);

        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("fluidphysics");
        assertNotNull(root);
        assertEquals(List.of("clear", "floodgates", "limit", "spawn", "stats"), childNames(root));

        CommandNode<CommandSourceStack> spawn = root.getChild("spawn");
        assertEquals(List.of("lava", "sand", "slime", "water"), childNames(spawn));
        CommandNode<CommandSourceStack> blocks = spawn.getChild("water").getChild("blocks");
        assertNotNull(blocks);
        assertNotNull(blocks.getChild("pos"));
        assertNotNull(root.getChild("limit").getChild("max"));
        assertEquals(List.of("off", "on"), childNames(root.getChild("floodgates")));
    }

    private static List<String> childNames(CommandNode<CommandSourceStack> node) {
        return node.getChildren().stream().map(CommandNode::getName).sorted().collect(Collectors.toList());
    }
}
