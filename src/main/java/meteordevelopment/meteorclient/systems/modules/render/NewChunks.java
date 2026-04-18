/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.fluid.FluidState;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class NewChunks extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> trackWindow = sgGeneral.add(new IntSetting.Builder()
        .name("track-window")
        .description("How many seconds to watch a chunk for fluid updates after it loads.")
        .defaultValue(30)
        .min(1)
        .sliderRange(5, 120)
        .build()
    );

    private final Setting<Boolean> scanChunkData = sgGeneral.add(new BoolSetting.Builder()
        .name("scan-chunk-data")
        .description("Marks chunks immediately if the initial chunk data contains flowing fluids.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> removeOnUnload = sgGeneral.add(new BoolSetting.Builder()
        .name("remove-on-unload")
        .description("Removes stored chunk markers when the server unloads the chunk.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> clearOnDimensionChange = sgGeneral.add(new BoolSetting.Builder()
        .name("clear-on-dimension-change")
        .description("Clears tracked chunks when you change dimensions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> renderY = sgRender.add(new IntSetting.Builder()
        .name("render-y")
        .description("Vertical position used for rendering chunk markers.")
        .defaultValue(0)
        .sliderRange(-64, 320)
        .build()
    );

    private final Setting<Double> slabHeight = sgRender.add(new DoubleSetting.Builder()
        .name("slab-height")
        .description("Thickness of the rendered chunk marker.")
        .defaultValue(0.1)
        .min(0.01)
        .sliderRange(0.01, 1.0)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the markers are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The fill color for marked chunks.")
        .defaultValue(new SettingColor(45, 180, 255, 35))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color for marked chunks.")
        .defaultValue(new SettingColor(45, 180, 255, 255))
        .build()
    );

    private final Map<Long, Long> trackedChunks = new HashMap<>();
    private final Map<Long, ChunkPos> newChunks = new HashMap<>();
    private final BlockPos.Mutable scanPos = new BlockPos.Mutable();

    private RegistryKey<World> lastDimension;

    public NewChunks() {
        super(Categories.Render, "new-chunks", "Highlights chunks that look newly generated from fluid behavior.");
    }

    @Override
    public void onActivate() {
        clear();
        if (mc.world != null) lastDimension = mc.world.getRegistryKey();
    }

    @Override
    public void onDeactivate() {
        clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null) return;

        if (clearOnDimensionChange.get()) {
            RegistryKey<World> currentDimension = mc.world.getRegistryKey();
            if (lastDimension != null && currentDimension != lastDimension) clear();
            lastDimension = currentDimension;
        }

        long expiration = System.currentTimeMillis() - trackWindow.get() * 1000L;
        Iterator<Map.Entry<Long, Long>> iterator = trackedChunks.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() < expiration) iterator.remove();
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        clear();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        ChunkPos chunkPos = event.chunk().getPos();
        long key = chunkPos.toLong();

        if (scanChunkData.get() && hasMovingFluid(event.chunk())) {
            markNewChunk(chunkPos);
            return;
        }

        trackedChunks.put(key, System.currentTimeMillis());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.world == null) return;

        ChunkPos chunkPos = new ChunkPos(event.pos);
        long key = chunkPos.toLong();
        if (!trackedChunks.containsKey(key)) return;

        if (isMovingFluid(event.newState.getFluidState())) markNewChunk(chunkPos);
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof UnloadChunkS2CPacket packet) || !removeOnUnload.get()) return;

        long key = packet.pos().toLong();
        trackedChunks.remove(key);
        newChunks.remove(key);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        double y = renderY.get();
        double height = slabHeight.get();

        for (ChunkPos chunkPos : newChunks.values()) {
            event.renderer.box(
                chunkPos.getStartX(),
                y,
                chunkPos.getStartZ(),
                chunkPos.getEndX() + 1,
                y + height,
                chunkPos.getEndZ() + 1,
                sideColor.get(),
                lineColor.get(),
                shapeMode.get(),
                0
            );
        }
    }

    @Override
    public String getInfoString() {
        return Integer.toString(newChunks.size());
    }

    private void markNewChunk(ChunkPos chunkPos) {
        long key = chunkPos.toLong();
        trackedChunks.remove(key);
        newChunks.put(key, chunkPos);
    }

    private boolean hasMovingFluid(WorldChunk chunk) {
        int bottomY = mc.world.getBottomY();
        int topY = bottomY + mc.world.getHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = bottomY; y < topY; y++) {
                    scanPos.set(chunk.getPos().getStartX() + x, y, chunk.getPos().getStartZ() + z);
                    if (isMovingFluid(chunk.getFluidState(scanPos))) return true;
                }
            }
        }

        return false;
    }

    private boolean isMovingFluid(FluidState fluidState) {
        return !fluidState.isEmpty() && !fluidState.isStill();
    }

    private void clear() {
        trackedChunks.clear();
        newChunks.clear();
    }
}
