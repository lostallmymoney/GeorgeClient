/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import meteordevelopment.meteorclient.systems.modules.misc.ChatPlusCachesChatheads;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.PlayerSkinProvider;
import net.minecraft.entity.player.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Mixin(PlayerSkinProvider.class)
public class PlayerSkinProviderMixin {
    @Inject(
        method = "fetchSkinTextures(Ljava/util/UUID;Lcom/mojang/authlib/minecraft/MinecraftProfileTextures;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("RETURN"),
        cancellable = true
    )
    private void onFetchSkinTextures(UUID uuid, MinecraftProfileTextures profileTextures, CallbackInfoReturnable<CompletableFuture<SkinTextures>> cir) {
        CompletableFuture<SkinTextures> future = cir.getReturnValue();
        if (future == null) return;

        cir.setReturnValue(future.whenComplete((skinTextures, throwable) -> {
            if (throwable != null || skinTextures == null) return;

            MinecraftClient client = MinecraftClient.getInstance();
            if (!client.uuidEquals(uuid) && !skinTextures.secure()) return;

            client.execute(() -> ChatPlusCachesChatheads.onSkinTexturesReceived(uuid, skinTextures));
        }));
    }
}
