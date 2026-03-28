/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin.sodium;

import com.mojang.blaze3d.vertex.VertexFormat;
import meteordevelopment.meteorclient.utils.render.NoopVertexConsumer;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = NoopVertexConsumer.class, remap = false)
public abstract class NoopVertexConsumerMixin implements VertexBufferWriter {
    @Override
    public void push(MemoryStack stack, long ptr, int count, VertexFormat format) {
        // Intentionally no-op: this consumer discards all vertices.
    }
}
