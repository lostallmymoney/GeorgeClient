/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.entity.EntityRemovedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
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
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EyeOfEnderEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;

public class StrongholdFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> minimumThrowDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("minimum-throw-distance")
        .description("Minimum horizontal distance an eye has to travel before it is used for triangulation.")
        .defaultValue(12)
        .min(1)
        .sliderRange(1, 64)
        .build()
    );

    private final Setting<Integer> maxThrows = sgGeneral.add(new IntSetting.Builder()
        .name("max-throws")
        .description("Maximum amount of stored eye throws.")
        .defaultValue(8)
        .min(2)
        .sliderRange(2, 20)
        .build()
    );

    private final Setting<Boolean> detectPortalFrames = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-portal-frames")
        .description("Records end portal frames from chunk data and block updates.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> clearOnDimensionChange = sgGeneral.add(new BoolSetting.Builder()
        .name("clear-on-dimension-change")
        .description("Clears stored throws when you change dimensions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> rayLength = sgRender.add(new DoubleSetting.Builder()
        .name("ray-length")
        .description("How far to render throw rays when there is no estimate yet.")
        .defaultValue(2048)
        .min(64)
        .sliderRange(256, 5000)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How to render estimate markers and portal frames.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> rayColor = sgRender.add(new ColorSetting.Builder()
        .name("ray-color")
        .description("Color used for eye throw rays.")
        .defaultValue(new SettingColor(255, 170, 0, 255))
        .build()
    );

    private final Setting<SettingColor> estimateSideColor = sgRender.add(new ColorSetting.Builder()
        .name("estimate-side-color")
        .description("Fill color for the estimated stronghold position.")
        .defaultValue(new SettingColor(0, 200, 120, 35))
        .build()
    );

    private final Setting<SettingColor> estimateLineColor = sgRender.add(new ColorSetting.Builder()
        .name("estimate-line-color")
        .description("Line color for the estimated stronghold position.")
        .defaultValue(new SettingColor(0, 200, 120, 255))
        .build()
    );

    private final Setting<SettingColor> portalFrameSideColor = sgRender.add(new ColorSetting.Builder()
        .name("portal-frame-side-color")
        .description("Fill color used for detected end portal frames.")
        .defaultValue(new SettingColor(120, 90, 255, 30))
        .visible(detectPortalFrames::get)
        .build()
    );

    private final Setting<SettingColor> portalFrameLineColor = sgRender.add(new ColorSetting.Builder()
        .name("portal-frame-line-color")
        .description("Line color used for detected end portal frames.")
        .defaultValue(new SettingColor(120, 90, 255, 255))
        .visible(detectPortalFrames::get)
        .build()
    );

    private final Map<Integer, ActiveEye> activeEyes = new HashMap<>();
    private final List<ThrowRay> throwsData = new ArrayList<>();
    private final Set<BlockPos> portalFrames = new LinkedHashSet<>();
    private final BlockPos.Mutable scanPos = new BlockPos.Mutable();

    private Vec3d estimate;
    private double estimateError;
    private RegistryKey<World> lastDimension;

    public StrongholdFinder() {
        super(Categories.World, "stronghold-finder", "Triangulates strongholds from eye throws and tracks end portal frames.");
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
            if (lastDimension != null && currentDimension != lastDimension) {
                clear();
                lastDimension = currentDimension;
            }
        }

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EyeOfEnderEntity)) continue;

            activeEyes.compute(entity.getId(), (ignored, activeEye) -> {
                Vec3d position = entityPos(entity);
                if (activeEye == null) return new ActiveEye(position);

                activeEye.last = position;
                return activeEye;
            });
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        clear();
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (!(event.entity instanceof EyeOfEnderEntity)) return;
        activeEyes.put(event.entity.getId(), new ActiveEye(entityPos(event.entity)));
    }

    @EventHandler
    private void onEntityRemoved(EntityRemovedEvent event) {
        if (!(event.entity instanceof EyeOfEnderEntity eye)) return;

        ActiveEye activeEye = activeEyes.remove(eye.getId());
        if (activeEye == null) activeEye = new ActiveEye(entityPos(eye));
        activeEye.last = entityPos(eye);

        double distance = horizontalDistance(activeEye.start, activeEye.last);
        if (distance < minimumThrowDistance.get()) return;

        throwsData.add(new ThrowRay(activeEye.start, activeEye.last));
        while (throwsData.size() > maxThrows.get()) throwsData.removeFirst();

        recalculateEstimate();
        info("Saved eye throw #%d (%.1f blocks).", throwsData.size(), distance);

        if (estimate != null) {
            info("Estimate: %.0f %.0f (avg line error %.1f).", estimate.x, estimate.z, estimateError);
        }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (detectPortalFrames.get()) scanChunkForPortalFrames(event.chunk());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (!detectPortalFrames.get()) return;

        if (event.newState.isOf(Blocks.END_PORTAL_FRAME)) portalFrames.add(event.pos.toImmutable());
        else if (event.oldState.isOf(Blocks.END_PORTAL_FRAME)) portalFrames.remove(event.pos);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        for (ThrowRay throwRay : throwsData) {
            Vec3d target = estimate != null ? estimate : throwRay.extend(rayLength.get());
            event.renderer.line(throwRay.start.x, throwRay.start.y, throwRay.start.z, target.x, throwRay.start.y, target.z, rayColor.get());
        }

        if (estimate != null) {
            int bottomY = mc.world.getBottomY();
            int topY = bottomY + mc.world.getHeight();

            event.renderer.box(estimate.x - 2, mc.player.getY() - 1, estimate.z - 2, estimate.x + 2, mc.player.getY() + 3, estimate.z + 2, estimateSideColor.get(), estimateLineColor.get(), shapeMode.get(), 0);
            event.renderer.line(estimate.x, bottomY, estimate.z, estimate.x, topY, estimate.z, estimateLineColor.get());
        }

        for (BlockPos portalFrame : portalFrames) {
            event.renderer.box(
                portalFrame.getX(),
                portalFrame.getY(),
                portalFrame.getZ(),
                portalFrame.getX() + 1,
                portalFrame.getY() + 1,
                portalFrame.getZ() + 1,
                portalFrameSideColor.get(),
                portalFrameLineColor.get(),
                shapeMode.get(),
                0
            );
        }
    }

    @Override
    public String getInfoString() {
        if (estimate != null) return Math.round(estimate.x) + ", " + Math.round(estimate.z);
        return throwsData.isEmpty() ? null : Integer.toString(throwsData.size());
    }

    private void scanChunkForPortalFrames(WorldChunk chunk) {
        int bottomY = mc.world.getBottomY();
        int topY = bottomY + mc.world.getHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = bottomY; y < topY; y++) {
                    scanPos.set(chunk.getPos().getStartX() + x, y, chunk.getPos().getStartZ() + z);
                    if (chunk.getBlockState(scanPos).isOf(Blocks.END_PORTAL_FRAME)) portalFrames.add(scanPos.toImmutable());
                }
            }
        }
    }

    private void recalculateEstimate() {
        if (throwsData.size() < 2) {
            estimate = null;
            estimateError = 0;
            return;
        }

        List<Vec3d> intersections = new ArrayList<>();

        for (int i = 0; i < throwsData.size(); i++) {
            for (int j = i + 1; j < throwsData.size(); j++) {
                Vec3d intersection = getIntersection(throwsData.get(i), throwsData.get(j));
                if (intersection != null) intersections.add(intersection);
            }
        }

        if (intersections.isEmpty()) {
            estimate = null;
            estimateError = 0;
            return;
        }

        double x = 0;
        double z = 0;
        for (Vec3d intersection : intersections) {
            x += intersection.x;
            z += intersection.z;
        }

        estimate = new Vec3d(x / intersections.size(), 0, z / intersections.size());
        estimateError = computeAverageError(estimate);
    }

    private Vec3d getIntersection(ThrowRay first, ThrowRay second) {
        double delta = first.directionX * second.directionZ - first.directionZ * second.directionX;
        if (Math.abs(delta) < 1.0e-5) return null;

        double dx = second.start.x - first.start.x;
        double dz = second.start.z - first.start.z;

        double firstT = (dx * second.directionZ - dz * second.directionX) / delta;
        double secondT = (dx * first.directionZ - dz * first.directionX) / delta;

        if (firstT < 0 || secondT < 0) return null;

        return new Vec3d(first.start.x + first.directionX * firstT, 0, first.start.z + first.directionZ * firstT);
    }

    private double computeAverageError(Vec3d candidate) {
        double totalError = 0;

        for (ThrowRay throwRay : throwsData) {
            double dx = candidate.x - throwRay.start.x;
            double dz = candidate.z - throwRay.start.z;
            double projected = dx * throwRay.directionX + dz * throwRay.directionZ;
            double closestX = throwRay.start.x + throwRay.directionX * Math.max(0, projected);
            double closestZ = throwRay.start.z + throwRay.directionZ * Math.max(0, projected);

            totalError += MathHelper.sqrt((float) ((candidate.x - closestX) * (candidate.x - closestX) + (candidate.z - closestZ) * (candidate.z - closestZ)));
        }

        return totalError / throwsData.size();
    }

    private double horizontalDistance(Vec3d first, Vec3d second) {
        double dx = second.x - first.x;
        double dz = second.z - first.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private Vec3d entityPos(Entity entity) {
        return new Vec3d(entity.getX(), entity.getY(), entity.getZ());
    }

    private void clear() {
        activeEyes.clear();
        throwsData.clear();
        portalFrames.clear();
        estimate = null;
        estimateError = 0;
    }

    private static class ActiveEye {
        private final Vec3d start;
        private Vec3d last;

        private ActiveEye(Vec3d start) {
            this.start = start;
            this.last = start;
        }
    }

    private static class ThrowRay {
        private final Vec3d start;
        private final Vec3d end;
        private final double directionX;
        private final double directionZ;

        private ThrowRay(Vec3d start, Vec3d end) {
            this.start = start;
            this.end = end;

            double dx = end.x - start.x;
            double dz = end.z - start.z;
            double length = Math.sqrt(dx * dx + dz * dz);

            directionX = dx / length;
            directionZ = dz / length;
        }

        private Vec3d extend(double length) {
            return new Vec3d(start.x + directionX * length, end.y, start.z + directionZ * length);
        }
    }
}
