/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.events.entity.player;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;

public class ClickSlotEvent {
    private static final ClickSlotEvent INSTANCE = new ClickSlotEvent();

    public int syncId;
    public int slotId;
    public int button;
    public SlotActionType actionType;
    public PlayerEntity player;

    public static ClickSlotEvent get(int syncId, int slotId, int button, SlotActionType actionType, PlayerEntity player) {
        INSTANCE.syncId = syncId;
        INSTANCE.slotId = slotId;
        INSTANCE.button = button;
        INSTANCE.actionType = actionType;
        INSTANCE.player = player;
        return INSTANCE;
    }
}
