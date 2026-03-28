/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin.xaeroplus;

import meteordevelopment.meteorclient.utils.compat.xaeroplus.BaritoneElytraPathAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "xaeroplus.util.BaritoneHelper", remap = false)
public abstract class BaritoneHelperMixin {
    @Inject(method = "isElytraPathAccessible", at = @At("HEAD"), cancellable = true, remap = false)
    private static void meteor$resolveAccessibleElytraPath(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(BaritoneElytraPathAccess.isAccessible());
    }
}
