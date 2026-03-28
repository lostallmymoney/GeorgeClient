/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;

public class NoEntityDistanceLimit extends Module {
    public NoEntityDistanceLimit() {
        super(Categories.Render, "no-entity-distance-limit", "Disables Minecraft's entity distance limit while keeping frustum culling.");
    }
}

