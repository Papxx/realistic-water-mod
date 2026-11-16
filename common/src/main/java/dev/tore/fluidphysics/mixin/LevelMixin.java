// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.mixin;

import dev.tore.fluidphysics.interaction.Simulations;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells the particle simulation about every block change on both sides, including the client's block updates from
 * the server, so its voxel cache never holds a block that is gone or misses a new one and the particles around a
 * changed block wake up; before the change, the server's floodgates look at what is being removed (Plan.md §7.3).
 */
@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"))
    private void fluidphysics$beforeSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                             CallbackInfoReturnable<Boolean> cir) {
        Simulations.beforeBlockChange((Level) (Object) this, pos, state);
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void fluidphysics$onSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            Simulations.onBlockChanged((Level) (Object) this, pos);
        }
    }
}
