/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

import net.fabricmc.loader.api.FabricLoader;

public class BobbyUtils {
    public static final boolean IS_AVAILABLE = FabricLoader.getInstance().isModLoaded("bobby");

    private BobbyUtils() {
    }
}
