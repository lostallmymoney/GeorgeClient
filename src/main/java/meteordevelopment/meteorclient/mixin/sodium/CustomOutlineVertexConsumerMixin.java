/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin.sodium;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.minecraft.client.render.VertexConsumer;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "meteordevelopment.meteorclient.utils.render.CustomOutlineVertexConsumerProvider$CustomVertexConsumer", remap = false)
public abstract class CustomOutlineVertexConsumerMixin implements VertexBufferWriter {
    @Shadow
    @Final
    private VertexConsumer consumer;

    @Override
    public void push(MemoryStack stack, long ptr, int count, VertexFormat format) {
        VertexBufferWriter.of(this.consumer).push(stack, ptr, count, format);
    }

    @Override
    public boolean canUseIntrinsics() {
        return VertexBufferWriter.tryOf(this.consumer) != null;
    }
}
