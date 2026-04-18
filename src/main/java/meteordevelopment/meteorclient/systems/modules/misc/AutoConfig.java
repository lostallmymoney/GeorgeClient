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
import meteordevelopment.meteorclient.systems.profiles.Profile;
import meteordevelopment.meteorclient.systems.profiles.Profiles;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.network.ServerObserver;
import meteordevelopment.orbit.EventHandler;

import java.util.Locale;

public class AutoConfig extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("How many ticks to wait after joining before loading a matching profile.")
        .defaultValue(10)
        .min(0)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Boolean> matchFullAddress = sgGeneral.add(new BoolSetting.Builder()
        .name("match-full-address")
        .description("Matches profiles against the current server address without requiring the port.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> matchRootDomain = sgGeneral.add(new BoolSetting.Builder()
        .name("match-root-domain")
        .description("Matches profiles against the current root domain.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> matchSubdomains = sgGeneral.add(new BoolSetting.Builder()
        .name("match-subdomains")
        .description("Allows a load-on-join entry like example.com to match mc.example.com.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> matchProfileName = sgGeneral.add(new BoolSetting.Builder()
        .name("match-profile-name")
        .description("Matches the profile name against the current server host or root domain.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreExactLoadOnJoin = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-exact-load-on-join")
        .description("Skips exact address matches that the built-in Profiles system already handles.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyMisses = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-misses")
        .description("Reports when no profile matched the current server.")
        .defaultValue(false)
        .build()
    );

    private int ticksUntilLoad = -1;
    private String pendingWorldName = "";

    public AutoConfig() {
        super(Categories.Misc, "auto-config", "Loads the best matching profile for the current server.");
    }

    @Override
    public void onActivate() {
        if (Utils.canUpdate()) scheduleLoad();
    }

    @Override
    public void onDeactivate() {
        ticksUntilLoad = -1;
        pendingWorldName = "";
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        scheduleLoad();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (ticksUntilLoad < 0 || pendingWorldName.isBlank()) return;

        if (ticksUntilLoad-- > 0) return;

        tryLoadProfile();
        ticksUntilLoad = -1;
        pendingWorldName = "";
    }

    private void scheduleLoad() {
        if (mc.isInSingleplayer()) {
            ticksUntilLoad = -1;
            pendingWorldName = "";
            return;
        }

        pendingWorldName = Utils.getWorldName();
        ticksUntilLoad = delay.get();
    }

    private void tryLoadProfile() {
        if (mc.isInSingleplayer() || !pendingWorldName.equals(Utils.getWorldName())) return;

        ServerObserver.INSTANCE.refreshSessionMetadata();

        Match match = findBestMatch();
        if (match == null) {
            if (notifyMisses.get()) info("No profile matched %s.", ServerObserver.INSTANCE.getDisplayAddress());
            return;
        }

        match.profile().load();
        info("Loaded profile %s for %s via %s.", match.profile().name.get(), ServerObserver.INSTANCE.getDisplayAddress(), match.reason());
    }

    private Match findBestMatch() {
        Profiles profiles = Profiles.get();
        if (profiles == null || profiles.isEmpty()) return null;

        String worldName = Utils.getWorldName();
        String normalizedWorld = ServerObserver.normalizeServerAddress(worldName);
        String rootDomain = ServerObserver.deriveRootDomain(normalizedWorld);

        Match best = null;
        for (Profile profile : profiles) {
            Match current = matchProfile(profile, worldName, normalizedWorld, rootDomain);
            if (current == null) continue;

            if (best == null || current.score() > best.score()) best = current;
        }

        return best;
    }

    private Match matchProfile(Profile profile, String worldName, String normalizedWorld, String rootDomain) {
        int bestScore = Integer.MIN_VALUE;
        String bestReason = null;

        for (String entry : profile.loadOnJoin.get()) {
            int score = scoreEntry(entry, worldName, normalizedWorld, rootDomain);
            if (score <= bestScore) continue;

            bestScore = score;
            bestReason = "load-on-join " + entry;
        }

        if (matchProfileName.get()) {
            String profileName = profile.name.get().trim().toLowerCase(Locale.ROOT);

            if (matchFullAddress.get() && profileName.equals(normalizedWorld) && 60 > bestScore) {
                bestScore = 60;
                bestReason = "profile name";
            }

            if (matchRootDomain.get() && profileName.equals(rootDomain) && 55 > bestScore) {
                bestScore = 55;
                bestReason = "profile name";
            }
        }

        return bestScore > 0 ? new Match(profile, bestScore, bestReason) : null;
    }

    private int scoreEntry(String entry, String worldName, String normalizedWorld, String rootDomain) {
        if (entry == null || entry.isBlank()) return Integer.MIN_VALUE;
        if (ignoreExactLoadOnJoin.get() && entry.equalsIgnoreCase(worldName)) return Integer.MIN_VALUE;

        String normalizedEntry = ServerObserver.normalizeServerAddress(entry);
        if (normalizedEntry.isBlank()) return Integer.MIN_VALUE;

        if (matchFullAddress.get() && normalizedEntry.equals(normalizedWorld)) return 90;
        if (matchRootDomain.get() && normalizedEntry.equals(rootDomain)) return 80;

        if (matchSubdomains.get()) {
            if (matchFullAddress.get() && normalizedWorld.endsWith("." + normalizedEntry)) return 70;
            if (matchRootDomain.get() && rootDomain.endsWith("." + normalizedEntry)) return 65;
        }

        return Integer.MIN_VALUE;
    }

    private record Match(Profile profile, int score, String reason) {}
}
