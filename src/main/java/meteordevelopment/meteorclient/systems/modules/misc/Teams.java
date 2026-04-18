/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.text.TextUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.Team;

import java.util.Locale;

public class Teams extends Module {
    private static final Color WHITE = new Color(255, 255, 255);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> scoreboardTeam = sgGeneral.add(new BoolSetting.Builder()
        .name("scoreboard-team")
        .description("Treats players in the same scoreboard team as teammates.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> nameColor = sgGeneral.add(new BoolSetting.Builder()
        .name("name-color")
        .description("Treats players with the same primary display-name color as teammates.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> prefix = sgGeneral.add(new BoolSetting.Builder()
        .name("prefix")
        .description("Treats players with the same display-name prefix as teammates.")
        .defaultValue(false)
        .build()
    );

    public Teams() {
        super(Categories.Misc, "teams", "Prevents targeting teammates on team-based servers.");
    }

    public static Teams get() {
        return Modules.get().get(Teams.class);
    }

    public static boolean shouldAttack(PlayerEntity player) {
        Teams teams = get();
        return teams == null || !teams.isActive() || !teams.isTeammate(player);
    }

    public boolean isTeammate(PlayerEntity player) {
        if (player == null || mc.player == null || player == mc.player) return false;

        if (scoreboardTeam.get() && matchesScoreboardTeam(player)) return true;
        if (nameColor.get() && matchesNameColor(player)) return true;
        return prefix.get() && matchesPrefix(player);
    }

    private boolean matchesScoreboardTeam(PlayerEntity player) {
        Team ownTeam = mc.player.getScoreboardTeam();
        Team otherTeam = player.getScoreboardTeam();

        if (ownTeam == null || otherTeam == null) return false;
        return ownTeam.isEqual(otherTeam) || mc.player.isTeammate(player);
    }

    private boolean matchesNameColor(PlayerEntity player) {
        Color ownColor = TextUtils.getMostPopularColor(mc.player.getDisplayName());
        Color otherColor = TextUtils.getMostPopularColor(player.getDisplayName());

        if (isWhite(ownColor) || isWhite(otherColor)) return false;
        return ownColor.r == otherColor.r && ownColor.g == otherColor.g && ownColor.b == otherColor.b;
    }

    private boolean matchesPrefix(PlayerEntity player) {
        String ownPrefix = getDisplayPrefix(mc.player);
        String otherPrefix = getDisplayPrefix(player);

        return !ownPrefix.isBlank() && ownPrefix.equals(otherPrefix);
    }

    private String getDisplayPrefix(PlayerEntity player) {
        String[] split = player.getDisplayName().getString().trim().split("\\s+");
        if (split.length < 2) return "";

        String first = split[0].trim().toLowerCase(Locale.ROOT);
        String name = player.getName().getString().trim().toLowerCase(Locale.ROOT);

        return first.equals(name) ? "" : first;
    }

    private boolean isWhite(Color color) {
        return color.r == WHITE.r && color.g == WHITE.g && color.b == WHITE.b;
    }
}
