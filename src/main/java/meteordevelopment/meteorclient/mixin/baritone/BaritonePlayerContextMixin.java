/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin.baritone;

import baritone.api.utils.BetterBlockPos;
import baritone.utils.player.BaritonePlayerContext;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BaritonePlayerContext.class)
public abstract class BaritonePlayerContextMixin {
    @Inject(method = "viewerPos", at = @At("HEAD"), cancellable = true)
    private void meteor$freecamViewerPos(CallbackInfoReturnable<BetterBlockPos> cir) {
        Freecam freecam = Modules.get().get(Freecam.class);
        if (freecam.isActive()) {
            cir.setReturnValue(new BetterBlockPos(
                (int) Math.floor(freecam.pos.x),
                (int) Math.floor(freecam.pos.y),
                (int) Math.floor(freecam.pos.z)
            ));
        }
    }
}
