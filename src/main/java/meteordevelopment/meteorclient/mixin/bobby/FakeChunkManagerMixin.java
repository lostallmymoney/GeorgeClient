/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin.bobby;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "de.johni0702.minecraft.bobby.FakeChunkManager")
public abstract class FakeChunkManagerMixin {
    @Redirect(
        method = "update(ZLjava/util/function/BooleanSupplier;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;getChunkPos()Lnet/minecraft/util/math/ChunkPos;")
    )
    private ChunkPos meteor$useFreecamChunkCenter(ClientPlayerEntity player) {
        if (Modules.get() == null) return player.getChunkPos();

        Freecam freecam = Modules.get().get(Freecam.class);
        if (!freecam.isActive()) return player.getChunkPos();

        return new ChunkPos(
            ChunkSectionPos.getSectionCoord(MathHelper.floor(freecam.pos.x)),
            ChunkSectionPos.getSectionCoord(MathHelper.floor(freecam.pos.z))
        );
    }
}
