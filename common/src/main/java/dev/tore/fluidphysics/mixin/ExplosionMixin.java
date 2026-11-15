// SPDX-License-Identifier: Apache-2.0
package dev.tore.fluidphysics.mixin;

import dev.tore.fluidphysics.interaction.ExplosionPush;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//? if >=1.21.11 {
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?} else {
/*import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Explosions push particles away (Plan.md §4.5). Hooks the server-side explosion after it destroyed its blocks:
 * {@code ServerExplosion.explode()} in 1.21.11, {@code Explosion.explode()} in 1.21.1 (the client only replays
 * explosions with {@code finalizeExplosion}).
 */
//? if >=1.21.11 {
@Mixin(ServerExplosion.class)
public abstract class ExplosionMixin {
    @Inject(method = "explode", at = @At("RETURN"))
    private void fluidphysics$pushParticles(CallbackInfoReturnable<Integer> cir) {
        ServerExplosion self = (ServerExplosion) (Object) this;
        ExplosionPush.afterExplosion(self.level(), self.center(), self.radius());
    }
}
//?} else {
/*@Mixin(Explosion.class)
public abstract class ExplosionMixin {
    @Shadow
    @Final
    private Level level;

    @Inject(method = "explode", at = @At("RETURN"))
    private void fluidphysics$pushParticles(CallbackInfo ci) {
        Explosion self = (Explosion) (Object) this;
        ExplosionPush.afterExplosion(level, self.center(), self.radius());
    }
}
*///?}
