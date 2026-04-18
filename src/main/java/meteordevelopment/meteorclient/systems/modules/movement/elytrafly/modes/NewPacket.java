/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement.elytrafly.modes;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightMode;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

public class NewPacket extends ElytraFlightMode {
    private int recastTimer;

    public NewPacket() {
        super(ElytraFlightModes.NewPacket);
    }

    @Override
    public void onActivate() {
        super.onActivate();
        recastTimer = 0;
    }

    @Override
    public void onTick() {
        super.onTick();

        if (!mc.player.getEquippedStack(EquipmentSlot.CHEST).contains(DataComponentTypes.GLIDER) || mc.player.isOnGround()) return;
        if (elytraFly.stopInWater.get() && mc.player.isTouchingWater()) return;

        if (recastTimer > 0) {
            recastTimer--;
            return;
        }

        mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));

        // Preserves the old packet exploit's grounded spoof only while idle.
        if (!PlayerUtils.isMoving() && !mc.options.jumpKey.isPressed() && !mc.options.sneakKey.isPressed()) {
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(true, mc.player.horizontalCollision));
        }

        recastTimer = PlayerUtils.isMoving() ? 1 : 2;
    }

    @Override
    public void onPacketSend(PacketEvent.Send event) {
        if (event.packet instanceof PlayerMoveC2SPacket && recastTimer <= 0) {
            mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            recastTimer = 1;
        }
    }
}
