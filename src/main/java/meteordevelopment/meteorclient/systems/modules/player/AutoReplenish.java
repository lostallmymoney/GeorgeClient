/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.entity.player.ClickSlotEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.AutoTotem;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class AutoReplenish extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> minCount = sgGeneral.add(new IntSetting.Builder()
        .name("min-count")
        .description("Replenish a slot when it reaches this item count.")
        .defaultValue(8)
        .min(1)
        .sliderRange(1, 63)
        .build()
    );

    private final Setting<Integer> tickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("How long in ticks to wait between replenishing your hotbar.")
        .defaultValue(1)
        .min(0)
        .build()
    );

    private final Setting<Boolean> offhand = sgGeneral.add(new BoolSetting.Builder()
        .name("offhand")
        .description("Whether or not to replenish items in your offhand.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> unstackable = sgGeneral.add(new BoolSetting.Builder()
        .name("unstackable")
        .description("Replenish unstackable items.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sameEnchants = sgGeneral.add(new BoolSetting.Builder()
        .name("same-enchants")
        .description("Only replace unstackables with items that have the same enchants.")
        .defaultValue(true)
        .visible(unstackable::get)
        .build()
    );

    private final Setting<Boolean> searchHotbar = sgGeneral.add(new BoolSetting.Builder()
        .name("search-hotbar")
        .description("Combine stacks in your hotbar/offhand as a last resort.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<Item>> excludedItems = sgGeneral.add(new ItemListSetting.Builder()
        .name("excluded-items")
        .description("Items that won't be replenished.")
        .build()
    );

    /**
     * Represents the items the player had last tick. Indices 0-8 represent the
     * hotbar from left to right, index 9 represents the player's offhand
     */
    private final ItemStack[] items = new ItemStack[10];
    private boolean prevHadOpenScreen;
    private int tickDelayLeft;
    private int useIntentGraceTicks;
    private int inventoryActionGraceTicks;
    private boolean forceIntentGateThisTick;
    private boolean suppressReplenishThisTick;
    private boolean internalMoveInProgress;
    private boolean baritoneReflectionChecked;
    private Method baritoneGetProviderMethod;
    private Method baritoneGetPrimaryBaritoneMethod;
    private Method baritoneGetPathingControlManagerMethod;
    private Method baritoneMostRecentInControlMethod;
    private Method baritoneProcessIsActiveMethod;

    public AutoReplenish() {
        super(Categories.Player, "auto-replenish", "Automatically refills items in your hotbar, main hand, or offhand.");

        Arrays.fill(items, Items.AIR.getDefaultStack());
    }

    @Override
    public void onActivate() {
        fillItems();
        tickDelayLeft = tickDelay.get();
        useIntentGraceTicks = 0;
        inventoryActionGraceTicks = 0;
        prevHadOpenScreen = mc.currentScreen != null;
    }

    @EventHandler
    private void onClickSlot(ClickSlotEvent event) {
        if (event.player != mc.player) return;
        if (internalMoveInProgress) return;
        if (!(mc.currentScreen instanceof HandledScreen<?>)) return;

        // Suppress only for handled inventory menu interactions.
        inventoryActionGraceTicks = 2;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        forceIntentGateThisTick = false;
        suppressReplenishThisTick = false;

        if (mc.currentScreen == null && prevHadOpenScreen) {
            fillItems();
        }

        prevHadOpenScreen = mc.currentScreen != null;
        // Allow running with client-side screens open (chat, inventory, pause) but keep container menus excluded.
        if (mc.player.currentScreenHandler.getStacks().size() != 46) return;

        forceIntentGateThisTick = mc.currentScreen instanceof HandledScreen<?>;
        suppressReplenishThisTick = forceIntentGateThisTick && inventoryActionGraceTicks > 0;
        if (inventoryActionGraceTicks > 0) inventoryActionGraceTicks--;
        if (!forceIntentGateThisTick) inventoryActionGraceTicks = 0;

        AutoEat autoEat = Modules.get().get(AutoEat.class);
        if (autoEat != null && autoEat.isActive() && autoEat.eating) {
            // Avoid fighting AutoEat's inventory borrow/restore flow while it is consuming.
            fillItems();
            useIntentGraceTicks = 0;
            return;
        }

        NewAutoTool autoTool = Modules.get().get(NewAutoTool.class);
        if (autoTool != null && autoTool.isActive() && autoTool.isBorrowRestoreActive() && NewAutoTool.isTool(mc.player.getMainHandStack())) return;

        boolean keysPressed = mc.options.useKey.isPressed() || mc.options.attackKey.isPressed() || isBaritoneProcessActive();
        if (keysPressed) useIntentGraceTicks = 2;
        else if (useIntentGraceTicks > 0) useIntentGraceTicks--;

        if (tickDelayLeft > 0) {
            tickDelayLeft--;
            return;
        }

        // Hotbar
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            checkSlot(i, stack);
        }

        // Offhand
        if (offhand.get() && !Modules.get().get(AutoTotem.class).isLocked()) {
            ItemStack stack = mc.player.getOffHandStack();
            checkSlot(9, stack);
        }

        tickDelayLeft = tickDelay.get();
    }

    private void checkSlot(int slot, ItemStack stack) {
        ItemStack prevStack = items[slot];
        items[slot] = stack.copy();

        if (slot == 9) slot = SlotUtils.OFFHAND;

        if (excludedItems.get().contains(stack.getItem())) return;
        if (excludedItems.get().contains(prevStack.getItem())) return;

        int fromSlot = -1;
        boolean stackableDecrease = stack.isStackable() && !stack.isEmpty() && prevStack.isStackable()
            && ItemStack.areItemsAndComponentsEqual(prevStack, stack)
            && stack.getCount() <= minCount.get()
            && stack.getCount() < prevStack.getCount();

        if (suppressReplenishThisTick) return;

        // In handled inventory screens, require intent for all transitions to avoid replenishing on mouse moves.
        // Outside handled screens, keep stackable-decrease passthrough for legitimate delayed update edges.
        if (forceIntentGateThisTick) {
            if (useIntentGraceTicks <= 0) return;
        } else if (useIntentGraceTicks <= 0 && !stackableDecrease) {
            return;
        }

        // Trigger on real use decreases whenever the stack is at or below threshold.
        if (stackableDecrease) {
            fromSlot = findItem(stack, slot, minCount.get() - stack.getCount() + 1, true);
        }

        // Empty in one tick while using stackables (e.g. very fast placement/consumption).
        if (fromSlot == -1 && prevStack.isStackable() && !prevStack.isEmpty() && stack.isEmpty() && isRealUseTransition(prevStack, stack)) {
            fromSlot = findItem(prevStack, slot, minCount.get() - stack.getCount() + 1, false);
        }

        // Unstackable replacement only on real use transitions.
        if (fromSlot == -1 && unstackable.get() && !prevStack.isStackable() && stack.isEmpty() && !prevStack.isEmpty() && isRealUseTransition(prevStack, stack)) {
            fromSlot = findItem(prevStack, slot, 1, false);
        }

        if (fromSlot < 0) return;

        // eliminate occasional loops when moving items from hotbar to itself
        if (fromSlot == mc.player.getInventory().getSelectedSlot() || fromSlot == SlotUtils.OFFHAND) return;
        if (fromSlot < 9 && fromSlot < slot && slot != mc.player.getInventory().getSelectedSlot() && slot != SlotUtils.OFFHAND) return;

        internalMoveInProgress = true;
        try {
            InvUtils.move().from(fromSlot).to(slot);
        } finally {
            internalMoveInProgress = false;
        }
    }

    private boolean isRealUseTransition(ItemStack previousStack, ItemStack currentStack) {
        if (previousStack.isDamageable()) return true;
        if (currentStack.isEmpty()) return true;
        return false;
    }

    private int findItem(ItemStack lookForStack, int excludedSlot, int goodEnoughCount, boolean mustCombine) {
        int slot = -1;
        int count = 0;

        for (int i = mc.player.getInventory().size() - 2; i >= (searchHotbar.get() ? 0 : 9); i--) {
            if (i == excludedSlot) continue;

            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() != lookForStack.getItem()) continue;

            if (mustCombine && !ItemStack.areItemsAndComponentsEqual(lookForStack, stack)) continue;
            if (sameEnchants.get() && !stack.getEnchantments().equals(lookForStack.getEnchantments())) continue;

            if (stack.getCount() > count) {
                slot = i;
                count = stack.getCount();

                if (count >= goodEnoughCount) break;
            }
        }

        return slot;
    }

    private void fillItems() {
        for (int i = 0; i < 9; i++) {
            items[i] = mc.player.getInventory().getStack(i).copy();
        }

        items[9] = mc.player.getOffHandStack().copy();
    }

    private boolean isBaritoneProcessActive() {
        if (!baritoneReflectionChecked) {
            baritoneReflectionChecked = true;

            try {
                Class<?> baritoneApiClass = Class.forName("baritone.api.BaritoneAPI");
                Class<?> providerClass = Class.forName("baritone.api.IBaritoneProvider");
                Class<?> baritoneClass = Class.forName("baritone.api.IBaritone");
                Class<?> pathingControlManagerClass = Class.forName("baritone.api.pathing.calc.IPathingControlManager");
                Class<?> baritoneProcessClass = Class.forName("baritone.api.process.IBaritoneProcess");

                baritoneGetProviderMethod = baritoneApiClass.getMethod("getProvider");
                baritoneGetPrimaryBaritoneMethod = providerClass.getMethod("getPrimaryBaritone");
                baritoneGetPathingControlManagerMethod = baritoneClass.getMethod("getPathingControlManager");
                baritoneMostRecentInControlMethod = pathingControlManagerClass.getMethod("mostRecentInControl");
                baritoneProcessIsActiveMethod = baritoneProcessClass.getMethod("isActive");
            } catch (Throwable ignored) {
                baritoneGetProviderMethod = null;
                baritoneGetPrimaryBaritoneMethod = null;
                baritoneGetPathingControlManagerMethod = null;
                baritoneMostRecentInControlMethod = null;
                baritoneProcessIsActiveMethod = null;
            }
        }

        if (baritoneGetProviderMethod == null) return false;

        try {
            Object provider = baritoneGetProviderMethod.invoke(null);
            if (provider == null) return false;

            Object baritone = baritoneGetPrimaryBaritoneMethod.invoke(provider);
            if (baritone == null) return false;

            Object pathingControlManager = baritoneGetPathingControlManagerMethod.invoke(baritone);
            if (pathingControlManager == null) return false;

            Object inControlOptional = baritoneMostRecentInControlMethod.invoke(pathingControlManager);
            if (!(inControlOptional instanceof Optional<?> optional)) return false;

            Object process = optional.orElse(null);
            return process != null && (boolean) baritoneProcessIsActiveMethod.invoke(process);
        } catch (Throwable ignored) {
            return false;
        }
    }

}
