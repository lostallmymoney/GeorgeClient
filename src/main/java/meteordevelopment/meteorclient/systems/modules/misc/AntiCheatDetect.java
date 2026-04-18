/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.network.ServerObserver;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public class AntiCheatDetect extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> joinDelay = sgGeneral.add(new IntSetting.Builder()
        .name("join-delay")
        .description("How many ticks to wait after joining before sending the first report.")
        .defaultValue(40)
        .min(0)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<Boolean> notifyOnJoin = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-on-join")
        .description("Reports the current anti-cheat signals after joining.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyOnChanges = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-on-changes")
        .description("Reports new anti-cheat signals if the detected set changes later.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> reportMisses = sgGeneral.add(new BoolSetting.Builder()
        .name("report-misses")
        .description("Reports when no anti-cheat signals were detected.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> includeSignals = sgGeneral.add(new BoolSetting.Builder()
        .name("include-signals")
        .description("Includes the exact plugin/channel/brand signal in the report.")
        .defaultValue(true)
        .build()
    );

    private int ticksUntilInitialReport = -1;
    private String lastReportedSignature = null;

    public AntiCheatDetect() {
        super(Categories.Misc, "anti-cheat-detect", "Infers anti-cheat plugins from packets, brands, and command metadata.");
    }

    @Override
    public void onActivate() {
        if (Utils.canUpdate()) {
            scheduleInitialReport();
            if (!notifyOnJoin.get()) report(true);
        }
    }

    @Override
    public void onDeactivate() {
        ticksUntilInitialReport = -1;
        lastReportedSignature = null;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        scheduleInitialReport();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!Utils.canUpdate() || mc.isInSingleplayer()) return;

        if (ticksUntilInitialReport >= 0) {
            if (ticksUntilInitialReport-- > 0) return;

            if (notifyOnJoin.get()) report(true);
            ticksUntilInitialReport = -1;
            return;
        }

        if (notifyOnChanges.get()) report(false);
    }

    @Override
    public String getInfoString() {
        List<ServerObserver.Detection> detections = ServerObserver.INSTANCE.getDetections();
        if (detections.isEmpty()) return null;
        if (detections.size() == 1) return detections.getFirst().name();
        return Integer.toString(detections.size());
    }

    private void scheduleInitialReport() {
        ticksUntilInitialReport = joinDelay.get();
        lastReportedSignature = null;
    }

    private void report(boolean allowEmpty) {
        ServerObserver.INSTANCE.refreshSessionMetadata();

        List<ServerObserver.Detection> detections = ServerObserver.INSTANCE.getDetections();
        String signature = buildSignature(detections);
        if (signature.equals(lastReportedSignature)) return;

        if (detections.isEmpty()) {
            if (!allowEmpty || !reportMisses.get()) return;

            lastReportedSignature = signature;
            info("No anti-cheat signals detected on %s yet.", ServerObserver.INSTANCE.getDisplayAddress());
            return;
        }

        lastReportedSignature = signature;
        info("Signals for %s: %s", ServerObserver.INSTANCE.getDisplayAddress(), formatDetections(detections));
    }

    private String buildSignature(List<ServerObserver.Detection> detections) {
        StringJoiner joiner = new StringJoiner("|");
        for (ServerObserver.Detection detection : detections) {
            joiner.add(detection.name() + ":" + detection.source() + ":" + detection.signal());
        }
        return joiner.toString();
    }

    private String formatDetections(List<ServerObserver.Detection> detections) {
        Map<String, List<ServerObserver.Detection>> grouped = new LinkedHashMap<>();
        for (ServerObserver.Detection detection : detections) {
            grouped.computeIfAbsent(detection.name(), ignored -> new ArrayList<>()).add(detection);
        }

        StringJoiner joiner = new StringJoiner(", ");
        for (Map.Entry<String, List<ServerObserver.Detection>> entry : grouped.entrySet()) {
            StringBuilder builder = new StringBuilder(entry.getKey());
            builder.append(" [");

            StringJoiner sources = new StringJoiner(", ");
            for (ServerObserver.Detection detection : entry.getValue()) {
                if (includeSignals.get()) sources.add(detection.source() + ": " + detection.signal());
                else sources.add(detection.source());
            }

            builder.append(sources);
            builder.append("]");
            joiner.add(builder);
        }

        return joiner.toString();
    }
}
