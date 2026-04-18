/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import meteordevelopment.meteorclient.events.entity.player.ItemUseCrosshairTargetEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.AnchorAura;
import meteordevelopment.meteorclient.systems.modules.combat.BedAura;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.MaceItem;
import net.minecraft.item.TridentItem;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

public class AutoEat extends Module {
    @SuppressWarnings("unchecked")
    private static final Class<? extends Module>[] AURAS = new Class[]{ KillAura.class, CrystalAura.class, AnchorAura.class, BedAura.class };

    // Settings groups
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgThreshold = settings.createGroup("Threshold");

    // General
    public final Setting<List<Item>> blacklist = sgGeneral.add(new ItemListSetting.Builder()
        .name("blacklist")
        .description("Which items to not eat.")
        .defaultValue(
            Items.ENCHANTED_GOLDEN_APPLE,
            Items.GOLDEN_APPLE,
            Items.CHORUS_FRUIT,
            Items.POISONOUS_POTATO,
            Items.PUFFERFISH,
            Items.CHICKEN,
            Items.ROTTEN_FLESH,
            Items.SPIDER_EYE,
            Items.SUSPICIOUS_STEW
        )
        .filter(item -> item.getComponents().get(DataComponentTypes.FOOD) != null)
        .build()
    );

    private final Setting<Boolean> pauseAuras = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-auras")
        .description("Pauses all auras when eating.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseBaritone = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-baritone")
        .description("Pause baritone when eating.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> searchInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("search-inventory")
        .description("Search the full inventory for food, not only the hotbar.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> inventorySwapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("inventory-swap-back")
        .description("Moves food borrowed from inventory back to its original slot when done eating.")
        .defaultValue(true)
        .visible(searchInventory::get)
        .build()
    );

    private final Setting<Priority> prioritise = sgGeneral.add(new EnumSetting.Builder<Priority>()
        .name("food-priority")
        .description("Which aspect of the food to prioritise selecting for.")
        .defaultValue(Priority.Saturation)
        .build()
    );

    // Threshold
    private final Setting<ThresholdMode> thresholdMode = sgThreshold.add(new EnumSetting.Builder<ThresholdMode>()
        .name("threshold-mode")
        .description("The threshold mode to trigger auto eat.\n'Both' == health AND hunger, 'Any' == health OR hunger")
        .defaultValue(ThresholdMode.Any)
        .build()
    );

    private final Setting<Double> healthThreshold = sgThreshold.add(new DoubleSetting.Builder()
        .name("health-threshold")
        .description("The level of health you eat at.")
        .defaultValue(10)
        .range(1, 19)
        .sliderRange(1, 19)
        .visible(() -> thresholdMode.get() != ThresholdMode.Hunger)
        .build()
    );

    private final Setting<Integer> hungerThreshold = sgThreshold.add(new IntSetting.Builder()
        .name("hunger-threshold")
        .description("The level of hunger you eat at.")
        .defaultValue(16)
        .range(1, 19)
        .sliderRange(1, 19)
        .visible(() -> thresholdMode.get() != ThresholdMode.Health)
        .build()
    );

    // Module state
    public boolean eating;
    private int slot, prevSlot;
    private final List<InventoryBorrow> borrowedFood = new ArrayList<>();

    private final List<Class<? extends Module>> wasAura = new ReferenceArrayList<>();
    private boolean wasBaritone = false;

    public AutoEat() {
        super(Categories.Player, "auto-eat", "Automatically eats food.");
    }

    @Override
    public void onDeactivate() {
        if (eating) stopEating();
    }

    /**
     * Main tick handler for the module's eating logic
     */
    @EventHandler(priority = EventPriority.LOW)
    private void onTick(TickEvent.Pre event) {
        // Don't eat if AutoGap is already eating
        AutoGap autoGap = Modules.get().get(AutoGap.class);
        if (autoGap != null && autoGap.isEating()) return;

        // case 1: Already eating
        if (eating) {
            // Stop eating if we shouldn't eat anymore
            if (!shouldEat()) {
                stopEating();
                return;
            }

            // Check if the item in current slot is not food anymore
            if (mc.player.getInventory().getStack(slot).get(DataComponentTypes.FOOD) == null) {
                int newSlot = findSlot();

                // Stop if no food found
                if (newSlot == -1) {
                    stopEating();
                    return;
                }

                changeSlot(newSlot);
            }

            // Continue eating the food
            eat();
            return;
        }

        // case 2: Not eating yet but should start
        if (shouldEat()) {
            if (shouldWaitForAutoToolHandoff()) return;
            startEating();
        }
    }

    @EventHandler
    private void onItemUseCrosshairTarget(ItemUseCrosshairTargetEvent event) {
        if (eating) event.target = null;
    }

    private void startEating() {
        prevSlot = mc.player.getInventory().getSelectedSlot();
        borrowedFood.clear();

        // Pause auras
        wasAura.clear();
        if (pauseAuras.get()) {
            for (Class<? extends Module> klass : AURAS) {
                Module module = Modules.get().get(klass);

                if (module.isActive()) {
                    wasAura.add(klass);
                    module.toggle();
                }
            }
        }

        // Pause baritone
        if (pauseBaritone.get() && !wasBaritone) {
            wasBaritone = true;
            PathManagers.get().pause();
        }

        eat();
    }

    private void eat() {
        if (!changeSlot(slot)) return;
        setPressed(true);
        if (!mc.player.isUsingItem()) Utils.rightClick();

        eating = true;
    }

    private void stopEating() {
        if (prevSlot != SlotUtils.OFFHAND) changeSlot(prevSlot);
        restoreBorrowedFood();
        setPressed(false);

        // Resume baritone before releasing AutoEat's pause gate so AutoTool re-enters after Baritone.
        if (pauseBaritone.get() && wasBaritone) {
            wasBaritone = false;
            PathManagers.get().resume();
        }

        eating = false;

        // Resume auras
        if (pauseAuras.get()) {
            for (Class<? extends Module> klass : AURAS) {
                if (wasAura.contains(klass)) {
                    Modules.get().get(klass).enable();
                }
            }
        }
    }

    private boolean shouldWaitForAutoToolHandoff() {
        NewAutoTool autoTool = Modules.get().get(NewAutoTool.class);
        return autoTool != null && autoTool.isBorrowRestoreActive();
    }

    private void setPressed(boolean pressed) {
        mc.options.useKey.setPressed(pressed);
    }

    /**
     * Prepares a slot for eating. Uses offhand or hotbar directly.
     * Moves a main-inventory item to an empty hotbar slot; returns false if none.
     */
    private boolean changeSlot(int slot) {
        // offhand: use directly
        if (slot == SlotUtils.OFFHAND) {
            this.slot = SlotUtils.OFFHAND;
            return true;
        }

        // hotbar: select
        if (SlotUtils.isHotbar(slot)) {
            InvUtils.swap(slot, false);
            this.slot = slot;
            return true;
        }

        // main inventory: prefer empty hotbar, otherwise replace a non-weapon slot (fallback: last weapon slot).
        int emptySlot = findEmptyHotbarSlotRightToLeft();
        int targetHotbarSlot = emptySlot != -1 ? emptySlot : findHotbarBorrowSlot();
        if (targetHotbarSlot == -1) return false;

        trackBorrowedFood(slot, targetHotbarSlot);
        InvUtils.quickSwap().fromId(targetHotbarSlot).to(slot);

        InvUtils.swap(targetHotbarSlot, false);
        this.slot = targetHotbarSlot;
        return true;
    }

    private int findEmptyHotbarSlotRightToLeft() {
        for (int i = SlotUtils.HOTBAR_END; i >= SlotUtils.HOTBAR_START; i--) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }

        return -1;
    }

    private void trackBorrowedFood(int sourceSlot, int hotbarSlot) {
        ItemStack sourceStack = mc.player.getInventory().getStack(sourceSlot);
        if (sourceStack.isEmpty()) return;
        HotbarStackSignature displacedHotbar = hotbarStackSignature(mc.player.getInventory().getStack(hotbarSlot));

        borrowedFood.removeIf(borrow -> borrow.sourceSlot == sourceSlot || borrow.hotbarSlot == hotbarSlot);
        borrowedFood.add(new InventoryBorrow(sourceSlot, hotbarSlot, sourceStack.copy(), displacedHotbar));
    }

    private void restoreBorrowedFood() {
        if (!searchInventory.get() || !inventorySwapBack.get()) {
            borrowedFood.clear();
            return;
        }

        for (InventoryBorrow borrow : borrowedFood) {
            if (borrow.sourceSlot < SlotUtils.MAIN_START || borrow.sourceSlot > SlotUtils.MAIN_END) continue;

            int resolvedHotbarSlot = resolveBorrowedHotbarSlot(borrow);
            if (resolvedHotbarSlot == -1) continue;

            ItemStack sourceStack = mc.player.getInventory().getStack(borrow.sourceSlot);
            if (!sourceStack.isEmpty()
                && borrow.displacedHotbar != null
                && !hotbarSignatureMatches(borrow.displacedHotbar, sourceStack)) continue;

            // Use SWAP semantics to avoid cursor-stack side effects during restore.
            InvUtils.quickSwap().fromId(resolvedHotbarSlot).to(borrow.sourceSlot);
        }

        borrowedFood.clear();
    }

    private int resolveBorrowedHotbarSlot(InventoryBorrow borrow) {
        if (SlotUtils.isHotbar(borrow.hotbarSlot)) {
            ItemStack atRecorded = mc.player.getInventory().getStack(borrow.hotbarSlot);
            if (!atRecorded.isEmpty()
                && atRecorded.get(DataComponentTypes.FOOD) != null
                && sameItemAndComponents(atRecorded, borrow.signature)) {
                return borrow.hotbarSlot;
            }
        }

        for (int i = SlotUtils.HOTBAR_START; i <= SlotUtils.HOTBAR_END; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || stack.get(DataComponentTypes.FOOD) == null) continue;
            if (sameItemAndComponents(stack, borrow.signature)) return i;
        }

        int bestSlot = -1;
        int bestCount = -1;
        for (int i = SlotUtils.HOTBAR_START; i <= SlotUtils.HOTBAR_END; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || stack.get(DataComponentTypes.FOOD) == null) continue;
            if (!sameItemId(stack, borrow.signature)) continue;

            int count = stack.getCount();
            if (count > bestCount) {
                bestCount = count;
                bestSlot = i;
            }
        }

        return bestSlot;
    }

    private int findHotbarBorrowSlot() {
        // 1) Best: non-weapon and non-tool
        for (int i = SlotUtils.HOTBAR_END; i >= SlotUtils.HOTBAR_START; i--) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!isProtectedHotbarItem(stack) && !NewAutoTool.isTool(stack)) return i;
        }

        // 2) Acceptable: non-weapon tools
        for (int i = SlotUtils.HOTBAR_END; i >= SlotUtils.HOTBAR_START; i--) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!isProtectedHotbarItem(stack) && NewAutoTool.isTool(stack)) return i;
        }

        // 3) Last resort: protected items (weapons / fireworks)
        for (int i = SlotUtils.HOTBAR_END; i >= SlotUtils.HOTBAR_START; i--) {
            if (isProtectedHotbarItem(mc.player.getInventory().getStack(i))) return i;
        }

        return -1;
    }

    private boolean isProtectedHotbarItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return isWeapon(stack) || stack.isOf(Items.FIREWORK_ROCKET);
    }

    private boolean isWeapon(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.isIn(ItemTags.SWORDS)
            || stack.isIn(ItemTags.SPEARS)
            || stack.getItem() instanceof MaceItem
            || stack.getItem() instanceof TridentItem;
    }

    private HotbarStackSignature hotbarStackSignature(ItemStack stack) {
        if (stack.isEmpty()) return null;
        return new HotbarStackSignature(stack.copy());
    }

    private boolean hotbarSignatureMatches(HotbarStackSignature signature, ItemStack stack) {
        if (signature == null || stack.isEmpty()) return false;
        return stack.getCount() == signature.snapshot.getCount() && ItemStack.areItemsAndComponentsEqual(stack, signature.snapshot);
    }

    private boolean sameItemAndComponents(ItemStack a, ItemStack b) {
        return a.getItem() == b.getItem() && a.getComponents().equals(b.getComponents());
    }

    private boolean sameItemId(ItemStack a, ItemStack b) {
        return Registries.ITEM.getId(a.getItem()).equals(Registries.ITEM.getId(b.getItem()));
    }

    public boolean shouldEat() {
        boolean healthLow = mc.player.getHealth() <= healthThreshold.get();
        boolean hungerLow = mc.player.getHungerManager().getFoodLevel() <= hungerThreshold.get();
        if (!thresholdMode.get().test(healthLow, hungerLow)) return false;

        slot = findSlot();
        if (slot == -1) return false;

        FoodComponent food = mc.player.getInventory().getStack(slot).get(DataComponentTypes.FOOD);
        if (food == null) return false;

        return (mc.player.getHungerManager().isNotFull() || food.canAlwaysEat());
    }

    /**
     * Finds the best slot to eat from, preferring:
     * offhand => hotbar => main inventory (if allowed).
     */
    private int findSlot() {
        // prefer offhand
        Item offHandItem = mc.player.getOffHandStack().getItem();
        FoodComponent offHandFood = offHandItem.getComponents().get(DataComponentTypes.FOOD);
        if (offHandFood != null && !blacklist.get().contains(offHandItem)) return SlotUtils.OFFHAND;

        // if offhand empty, prefer best in hotbar
        int slot = findBestFood(SlotUtils.HOTBAR_START, SlotUtils.HOTBAR_END);
        if (slot != -1) return slot;

        // if allowed, search main inventory
        if (searchInventory.get()) {
            return findBestFood(SlotUtils.MAIN_START, SlotUtils.MAIN_END);
        }

        return -1; // nothing found :(
    }

    private int findBestFood(int start, int end) {
        int best = -1;
        float bestHunger = -1;

        for (int i = start; i <= end; i++) {
            // Skip if item isn't food
            ItemStack stack = mc.player.getInventory().getStack(i);
            FoodComponent food = stack.get(DataComponentTypes.FOOD);
            if (food == null) continue;

            // Skip if item is in blacklist
            Item item = stack.getItem();
            if (blacklist.get().contains(item)) continue;

            // Check if hunger value is better
            float hunger = prioritise.get().value(food);
            if (hunger > bestHunger) {
                bestHunger = hunger;
                best = i;
            }
        }

        return best;
    }

    public enum ThresholdMode {
        Health((health, hunger) -> health),
        Hunger((health, hunger) -> hunger),
        Any((health, hunger) -> health || hunger),
        Both((health, hunger) -> health && hunger);

        private final BiPredicate<Boolean, Boolean> predicate;

        ThresholdMode(BiPredicate<Boolean, Boolean> predicate) {
            this.predicate = predicate;
        }

        public boolean test(boolean health, boolean hunger) {
            return predicate.test(health, hunger);
        }
    }

    public enum Priority {
        Combined,
        Hunger,
        Saturation;

        public float value(FoodComponent food) {
            return switch (this) {
                case Combined -> food.nutrition() + food.saturation();
                case Hunger -> food.nutrition();
                case Saturation -> food.saturation();
            };
        }
    }

    private record InventoryBorrow(int sourceSlot, int hotbarSlot, ItemStack signature, HotbarStackSignature displacedHotbar) {
    }

    private record HotbarStackSignature(ItemStack snapshot) {
    }
}
