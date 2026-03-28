/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin.xaeroplus;

import meteordevelopment.meteorclient.utils.compat.xaeroplus.BaritoneElytraPathAccess;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(targets = "xaeroplus.util.BaritonePathHelper", remap = false)
public abstract class BaritonePathHelperMixin {
    @Inject(method = "getElytraPath", at = @At("HEAD"), cancellable = true, remap = false)
    private static void meteor$resolveElytraPath(CallbackInfoReturnable<List<BlockPos>> cir) {
        cir.setReturnValue(BaritoneElytraPathAccess.getElytraPath());
    }
}
