/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

public class BaritoneBobbyChunkCacheBridge {
    private static volatile boolean enabled;

    private BaritoneBobbyChunkCacheBridge() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean enabled) {
        BaritoneBobbyChunkCacheBridge.enabled = enabled;
    }
}
