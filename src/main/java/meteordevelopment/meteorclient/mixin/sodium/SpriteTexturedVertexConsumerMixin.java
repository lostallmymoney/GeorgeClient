/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin.sodium;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.minecraft.client.render.SpriteTexturedVertexConsumer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.Sprite;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SpriteTexturedVertexConsumer.class)
public abstract class SpriteTexturedVertexConsumerMixin implements VertexBufferWriter {
    @Shadow
    @Final
    private VertexConsumer delegate;

    @Shadow
    @Final
    private Sprite sprite;

    @Override
    public void push(MemoryStack stack, long ptr, int count, VertexFormat format) {
        int uvOffset = format.getOffset(VertexFormatElement.UV0);
        if (uvOffset >= 0) {
            int vertexSize = format.getVertexSize();

            for (int i = 0; i < count; i++) {
                long uvPtr = ptr + (long) vertexSize * i + uvOffset;
                float u = MemoryUtil.memGetFloat(uvPtr);
                float v = MemoryUtil.memGetFloat(uvPtr + 4);

                MemoryUtil.memPutFloat(uvPtr, sprite.getFrameU(u));
                MemoryUtil.memPutFloat(uvPtr + 4, sprite.getFrameV(v));
            }
        }

        VertexBufferWriter.of(this.delegate).push(stack, ptr, count, format);
    }

    @Override
    public boolean canUseIntrinsics() {
        return VertexBufferWriter.tryOf(this.delegate) != null;
    }
}
