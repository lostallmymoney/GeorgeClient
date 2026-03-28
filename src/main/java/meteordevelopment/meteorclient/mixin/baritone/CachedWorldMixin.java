/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin.baritone;

import baritone.cache.CachedWorld;
import meteordevelopment.meteorclient.pathing.BaritoneBobbyChunkCacheBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CachedWorld.class)
public abstract class CachedWorldMixin {
    @Inject(method = "queueForPacking", at = @At("HEAD"), cancellable = true, remap = false)
    private void meteor$skipBaritoneChunkPacking(CallbackInfo ci) {
        if (BaritoneBobbyChunkCacheBridge.isEnabled()) ci.cancel();
    }
}
