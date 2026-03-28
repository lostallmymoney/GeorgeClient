/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.player;

import meteordevelopment.meteorclient.mixininterface.ISlot;
import meteordevelopment.meteorclient.utils.render.PeekScreen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Pair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class InventorySorter {
    private final HandledScreen<?> screen;
    private final InvPart originInvPart;
    private final SortMode sortMode;
    private final List<Integer> sortableSlotIds;
    private final Set<Integer> sortableSlotIdSet;

    private boolean invalid;
    private List<Action> actions;
    private int timer, currentActionI;

    public InventorySorter(HandledScreen<?> screen, Slot originSlot, SortMode sortMode) {
        this.screen = screen;
        this.sortMode = sortMode;
        this.sortableSlotIds = new ArrayList<>();
        this.sortableSlotIdSet = new HashSet<>();

        this.originInvPart = getInvPart(originSlot);
        if (originInvPart == InvPart.Invalid || originInvPart == InvPart.Hotbar || screen instanceof PeekScreen) {
            invalid = true;
            return;
        }

        this.actions = new ArrayList<>();
        generateActions();
    }

    public boolean tick(int delay, int actionsPerTick) {
        if (invalid) return true;
        if (currentActionI >= actions.size()) {
            ensureCursorEmpty(-1);
            return true;
        }

        if (timer >= delay) {
            timer = 0;
        }
        else {
            timer++;
            return false;
        }

        int budget = Math.max(1, actionsPerTick);
        while (budget > 0 && currentActionI < actions.size()) {
            Action action = actions.get(currentActionI);
            if (!runAction(action)) {
                invalid = true;
                return true;
            }

            currentActionI++;
            budget--;
        }

        if (currentActionI >= actions.size()) {
            ensureCursorEmpty(-1);
            return true;
        }

        return false;
    }

    private void generateActions() {
        // Find all slots and sort them
        List<MySlot> slots = new ArrayList<>();

        for (Slot slot : screen.getScreenHandler().slots) {
            if (getInvPart(slot) == originInvPart) {
                int slotId = ((ISlot) slot).meteor$getId();
                slots.add(new MySlot(slotId, slot.getStack()));
                sortableSlotIds.add(slotId);
                sortableSlotIdSet.add(slotId);
            }
        }

        slots.sort(Comparator.comparingInt(value -> value.id));
        sortableSlotIds.sort(Integer::compareTo);

        // Generate actions
        generateStackingActions(slots);
        generateSortingActions(slots);
    }

    private void generateStackingActions(List<MySlot> slots) {
        // Generate a map for slots that can be stacked
        SlotMap slotMap = new SlotMap();

        for (MySlot slot : slots) {
            if (slot.itemStack.isEmpty() || !slot.itemStack.isStackable() || slot.itemStack.getCount() >= slot.itemStack.getMaxCount()) continue;

            slotMap.get(slot.itemStack).add(slot);
        }

        // Stack previously found slots
        for (var entry : slotMap.map) {
            List<MySlot> slotsToStack = entry.getRight();
            MySlot slotToStackTo = null;

            for (int i = 0; i < slotsToStack.size(); i++) {
                MySlot slot = slotsToStack.get(i);

                // Check if slotToStackTo is null and update it if it is
                if (slotToStackTo == null) {
                    slotToStackTo = slot;
                    continue;
                }

                // Generate action
                actions.add(new Action(slot.id, slotToStackTo.id));

                // Handle state when the two stacks can combine without any leftovers
                if (slotToStackTo.itemStack.getCount() + slot.itemStack.getCount() <= slotToStackTo.itemStack.getMaxCount()) {
                    slotToStackTo.itemStack = new ItemStack(slotToStackTo.itemStack.getItem(), slotToStackTo.itemStack.getCount() + slot.itemStack.getCount());
                    slot.itemStack = ItemStack.EMPTY;

                    if (slotToStackTo.itemStack.getCount() >= slotToStackTo.itemStack.getMaxCount()) slotToStackTo = null;
                }
                // Handle state when combining the two stacks produces leftovers
                else {
                    int needed = slotToStackTo.itemStack.getMaxCount() - slotToStackTo.itemStack.getCount();

                    slotToStackTo.itemStack = new ItemStack(slotToStackTo.itemStack.getItem(), slotToStackTo.itemStack.getMaxCount());
                    slot.itemStack = new ItemStack(slot.itemStack.getItem(), slot.itemStack.getCount() - needed);

                    slotToStackTo = null;
                    i--;
                }
            }
        }
    }

    private void generateSortingActions(List<MySlot> slots) {
        for (int i = 0; i < slots.size(); i++) {
            // Find best slot to move here
            MySlot bestSlot = null;

            for (int j = i; j < slots.size(); j++) {
                MySlot slot = slots.get(j);

                if (bestSlot == null) {
                    bestSlot = slot;
                    continue;
                }

                if (isSlotBetter(bestSlot, slot)) bestSlot = slot;
            }

            // Generate action
            if (bestSlot != null && !bestSlot.itemStack.isEmpty()) {
                MySlot toSlot = slots.get(i);

                int from = bestSlot.id;
                int to = toSlot.id;

                if (from != to) {
                    ItemStack temp = bestSlot.itemStack;
                    bestSlot.itemStack = toSlot.itemStack;
                    toSlot.itemStack = temp;

                    actions.add(new Action(from, to));
                }
            }
        }
    }

    private boolean isSlotBetter(MySlot best, MySlot slot) {
        ItemStack bestI = best.itemStack;
        ItemStack slotI = slot.itemStack;

        if (bestI.isEmpty() && !slotI.isEmpty()) return true;
        else if (!bestI.isEmpty() && slotI.isEmpty()) return false;
        return sortMode.getComparator().compare(slotI, bestI) < 0;
    }

    private boolean runAction(Action action) {
        if (!ensureCursorEmpty(action.from)) return false;

        if (InvUtils.innovativeMoveById(action.from, action.to)) {
            return ensureCursorEmpty(action.from);
        }

        // Retry once after trying to normalize cursor around the target slot.
        if (!ensureCursorEmpty(action.to)) return false;
        if (!InvUtils.innovativeMoveById(action.from, action.to)) return false;
        return ensureCursorEmpty(action.from);
    }

    private boolean ensureCursorEmpty(int preferredSlotId) {
        if (screen.getScreenHandler().getCursorStack().isEmpty()) return true;

        if (preferredSlotId != -1 && tryStoreCursor(preferredSlotId)) {
            if (screen.getScreenHandler().getCursorStack().isEmpty()) return true;
        }

        for (int slotId : sortableSlotIds) {
            if (slotId == preferredSlotId) continue;
            if (tryStoreCursor(slotId) && screen.getScreenHandler().getCursorStack().isEmpty()) return true;
        }

        for (int slotId = 0; slotId < screen.getScreenHandler().slots.size(); slotId++) {
            if (slotId == preferredSlotId || sortableSlotIdSet.contains(slotId)) continue;
            if (tryStoreCursor(slotId) && screen.getScreenHandler().getCursorStack().isEmpty()) return true;
        }

        return screen.getScreenHandler().getCursorStack().isEmpty();
    }

    private boolean tryStoreCursor(int slotId) {
        if (slotId < 0 || slotId >= screen.getScreenHandler().slots.size()) return false;

        ItemStack cursor = screen.getScreenHandler().getCursorStack();
        if (cursor.isEmpty()) return true;

        Slot slot = screen.getScreenHandler().getSlot(slotId);
        if (!slot.canInsert(cursor) && !ItemStack.areItemsAndComponentsEqual(slot.getStack(), cursor)) return false;

        ItemStack slotStack = slot.getStack();
        boolean canPlaceInEmpty = slotStack.isEmpty();
        boolean canMerge = ItemStack.areItemsAndComponentsEqual(slotStack, cursor) && slotStack.getCount() < slotStack.getMaxCount();

        if (!canPlaceInEmpty && !canMerge) return false;

        InvUtils.click().slotId(slotId);
        return true;
    }

    public boolean isContainerSort() {
        return originInvPart == InvPart.Main;
    }

    private InvPart getInvPart(Slot slot) {
        int i = ((ISlot) slot).meteor$getIndex();

        if (slot.inventory instanceof PlayerInventory && (!(screen instanceof CreativeInventoryScreen) || ((ISlot) slot).meteor$getId() > 8)) {
            if (SlotUtils.isHotbar(i)) return InvPart.Hotbar;
            else if (SlotUtils.isMain(i)) return InvPart.Player;
        }
        else if ((screen instanceof GenericContainerScreen || screen instanceof ShulkerBoxScreen) && slot.inventory instanceof SimpleInventory) {
            return InvPart.Main;
        }

        return InvPart.Invalid;
    }

    private enum InvPart {
        Hotbar,
        Player,
        Main,
        Invalid
    }

    private static class MySlot {
        public final int id;
        public ItemStack itemStack;

        public MySlot(int id, ItemStack itemStack) {
            this.id = id;
            this.itemStack = itemStack;
        }
    }

    private static class SlotMap {
        private final List<Pair<ItemStack, List<MySlot>>> map = new ArrayList<>();

        public List<MySlot> get(ItemStack itemStack) {
            for (Pair<ItemStack, List<MySlot>> entry : map) {
                if (ItemStack.areItemsAndComponentsEqual(itemStack, entry.getLeft())) {
                    return entry.getRight();
                }
            }

            List<MySlot> list = new ArrayList<>();
            map.add(new Pair<>(itemStack, list));
            return list;
        }
    }

    private static String getEnchantmentSignature(ItemStack stack) {
        ItemEnchantmentsComponent component = stack.get(DataComponentTypes.STORED_ENCHANTMENTS);
        if (component == null) component = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (component == null) return "";

        ItemEnchantmentsComponent finalComponent = component;
        return component.getEnchantments()
            .stream()
            .map(entry -> Enchantment.getName(entry, 0).getString() + " " + finalComponent.getLevel(entry))
            .collect(Collectors.joining(", "));
    }

    public enum SortMode {
        Identifier(Comparator
            .comparing((ItemStack stack) -> Registries.ITEM.getId(stack.getItem()))
            .thenComparing(stack -> stack.getCount(), Comparator.reverseOrder())
            .thenComparing(stack -> stack.getDamage(), Comparator.reverseOrder())),
        ItemType(Comparator
            .comparingInt((ItemStack stack) -> Item.getRawId(stack.getItem()))
            .thenComparing(stack -> stack.getCount(), Comparator.reverseOrder())
            .thenComparing(InventorySorter::getEnchantmentSignature));

        private final Comparator<ItemStack> comparator;

        SortMode(Comparator<ItemStack> comparator) {
            this.comparator = comparator;
        }

        public Comparator<ItemStack> getComparator() {
            return comparator;
        }
    }

    private record Action(int from, int to) {}
}
