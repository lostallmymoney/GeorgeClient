/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import meteordevelopment.meteorclient.pathing.BaritoneBobbyChunkCacheBridge;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.pathing.BobbyUtils;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;

public class BaritoneUseBobbyChunkCache extends Module {
    private boolean previousChunkCaching;
    private boolean restoreChunkCaching;

    public BaritoneUseBobbyChunkCache() {
        super(Categories.World, "BaritoneUseBobbyChunkCache", "Disables Baritone cache writes so it relies on Bobby chunks plus existing cache reads.");
    }

    @Override
    public void onActivate() {
        if (!BaritoneUtils.IS_AVAILABLE || !BobbyUtils.IS_AVAILABLE) {
            error("This module requires both Baritone and Bobby.");
            toggle();
            return;
        }

        Settings settings = BaritoneAPI.getSettings();
        previousChunkCaching = settings.chunkCaching.value;
        restoreChunkCaching = true;
        settings.chunkCaching.value = false;

        BaritoneBobbyChunkCacheBridge.setEnabled(true);
    }

    @Override
    public void onDeactivate() {
        BaritoneBobbyChunkCacheBridge.setEnabled(false);

        if (!restoreChunkCaching || !BaritoneUtils.IS_AVAILABLE) return;
        BaritoneAPI.getSettings().chunkCaching.value = previousChunkCaching;
        restoreChunkCaching = false;
    }
}
