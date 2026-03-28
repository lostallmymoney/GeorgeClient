/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

import net.fabricmc.loader.api.FabricLoader;

public class ChatPlusUtils {
    public static final boolean IS_AVAILABLE = FabricLoader.getInstance().isModLoaded("chatplus");

    private ChatPlusUtils() {
    }
}
