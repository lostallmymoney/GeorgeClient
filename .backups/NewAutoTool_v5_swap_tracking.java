/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.systems.modules.render.Xray;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.BambooBlock;
import net.minecraft.block.BambooShootBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ShearsItem;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;

import java.util.ArrayList;
import java.util.List;

/**
 * AutoTool - Automatically selects the best tool for mining blocks.
 * 
 * CORE PRINCIPLE: Track the SWAPS that happen during mining, not the final state.
 * When mining ends, undo each swap in reverse order (LIFO).
 * This avoids issues with item matching after durability changes.
 */
public class NewAutoTool extends Module {
    private static final int HOTBAR_START = 0;
    private static final int HOTBAR_END = 8;
    private static final int INVENTORY_START = 9;
    private static final int INVENTORY_END = 35;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWhitelist = settings.createGroup("Whitelist");

    // ===== SETTINGS =====

    private final Setting<Boolean> enabled = sgGeneral.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Enable automatic tool switching.")
        .defaultValue(true)
        .build()
    );

    private final Setting<EnchantmentMode> prefer = sgGeneral.add(new EnumSetting.Builder<EnchantmentMode>()
        .name("prefer")
        .description("Prefer silk touch, fortune, or neither.")
        .defaultValue(EnchantmentMode.Fortune)
        .build()
    );

    private final Setting<Boolean> silkTouchEnderChest = sgGeneral.add(new BoolSetting.Builder()
        .name("silk-touch-ender-chest")
        .description("Mine ender chests only with silk touch.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> fortuneOres = sgGeneral.add(new BoolSetting.Builder()
        .name("fortune-ores")
        .description("Mine ores only with fortune.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> fortuneCrops = sgGeneral.add(new BoolSetting.Builder()
        .name("fortune-crops")
        .description("Mine crops only with fortune.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> antiBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-break")
        .description("Stop using tools before they break.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> breakDurability = sgGeneral.add(new IntSetting.Builder()
        .name("break-durability")
        .description("Tool durability %% to stop using.")
        .defaultValue(1)
        .range(1, 100)
        .sliderRange(1, 100)
        .visible(() -> antiBreak.get())
        .build()
    );

    private final Setting<Boolean> switchBack = sgGeneral.add(new BoolSetting.Builder()
        .name("switch-back")
        .description("Switch back to previous tool after mining.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-inventory")
        .description("Take tools from inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowOffHand = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-off-hand")
        .description("Take tools from off-hand.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> inventorySwitchBack = sgGeneral.add(new BoolSetting.Builder()
        .name("inventory-switch-back")
        .description("Return borrowed inventory tools after mining.")
        .defaultValue(true)
        .visible(() -> allowInventory.get())
        .build()
    );

    private final Setting<Boolean> offHandSwitchBack = sgGeneral.add(new BoolSetting.Builder()
        .name("off-hand-switch-back")
        .description("Return borrowed off-hand tools after mining.")
        .defaultValue(true)
        .visible(() -> allowOffHand.get())
        .build()
    );

    private final Setting<Integer> switchDelay = sgGeneral.add(new IntSetting.Builder()
        .name("switch-delay")
        .description("Ticks before switching tools.")
        .defaultValue(0)
        .range(0, 10)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Boolean> pauseWhenAutoEat = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-autoeat")
        .description("Pause when AutoEat is eating.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseWhenKillAura = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-kill-aura")
        .description("Pause when KillAura swaps weapons.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Item>> doNotReplace = sgGeneral.add(new ItemListSetting.Builder()
        .name("do-not-replace")
        .description("Items to protect in hotbar when borrowing.")
        .defaultValue(
            Items.STONE_SWORD,
            Items.GOLDEN_SWORD,
            Items.IRON_SWORD,
            Items.DIAMOND_SWORD,
            Items.NETHERITE_SWORD
        )
        .build()
    );

    private final Setting<List<Item>> disableWhenHolding = sgGeneral.add(new ItemListSetting.Builder()
        .name("disable-when-holding")
        .description("Disable auto-tool when holding these items.")
        .build()
    );

    private final Setting<Boolean> useWhitelist = sgWhitelist.add(new BoolSetting.Builder()
        .name("use-whitelist")
        .description("Use whitelist (true) or blacklist (false).")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<Item>> whitelist = sgWhitelist.add(new ItemListSetting.Builder()
        .name("whitelist")
        .description("Tools to use.")
        .visible(() -> useWhitelist.get())
        .build()
    );

    private final Setting<List<Item>> blacklist = sgWhitelist.add(new ItemListSetting.Builder()
        .name("blacklist")
        .description("Tools to ignore.")
        .visible(() -> !useWhitelist.get())
        .build()
    );

    // ===== STATE =====
    private final List<SwapRecord> swapHistory = new ArrayList<>();
    private int originalHotbarSlot = -1;
    private int switchDelayCounter = 0;
    private boolean isMining = false;

    public NewAutoTool() {
        super(Categories.Player, "auto-tool", "Automatically selects the best tool for mining blocks.");
    }

    @Override
    public void onActivate() {
        clear();
    }

    @Override
    public void onDeactivate() {
        restoreTools();
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onStartBreakingBlock(StartBreakingBlockEvent event) {
        if (!enabled.get() || mc.player == null || mc.world == null) return;
        if (isPaused()) return;
        if (disableWhenHolding.get().contains(mc.player.getMainHandStack().getItem())) return;

        BlockState block = mc.world.getBlockState(event.blockPos);
        if (!BlockUtils.canBreak(event.blockPos, block)) return;

        // Start mining session only once per click
        if (!isMining) {
            isMining = true;
            swapHistory.clear();
            if (switchBack.get()) {
                originalHotbarSlot = mc.player.getInventory().getSelectedSlot();
            }
        }

        // Try to switch to better tool
        selectBestTool(block);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!enabled.get()) return;

        // Check if player stopped mining
        if (isMining && !isBreaking()) {
            isMining = false;
            restoreTools();
        }

        // Decrement switch delay
        if (switchDelayCounter > 0) switchDelayCounter--;
    }

    // ===== MAIN LOGIC =====

    private void selectBestTool(BlockState block) {
        if (isPaused() || switchDelayCounter > 0) return;

        ItemStack current = mc.player.getMainHandStack();

        // Check if we should keep current tool
        if (!shouldStopUsing(current)) {
            if (scoreItem(current, block) > 0) return;  // Current tool is fine
        }

        // Find and switch to best tool
        ToolSlot best = findBestTool(block);
        if (best != null) {
            switchToTool(best);
            switchDelayCounter = switchDelay.get();
        }
    }

    private ToolSlot findBestTool(BlockState block) {
        ToolSlot best = null;

        // Hotbar - search first for speed
        for (int i = HOTBAR_START; i <= HOTBAR_END; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isValidTool(stack, block)) {
                double score = scoreItem(stack, block);
                if (best == null || score > best.score) {
                    best = new ToolSlot(i, score, ToolSource.HOTBAR);
                }
            }
        }

        // Inventory (if enabled and hotbar wasn't good enough)
        if (allowInventory.get()) {
            for (int i = INVENTORY_START; i <= INVENTORY_END; i++) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (isValidTool(stack, block)) {
                    double score = scoreItem(stack, block);
                    if (best == null || score > best.score) {
                        best = new ToolSlot(i, score, ToolSource.INVENTORY);
                    }
                }
            }
        }

        // Offhand (if enabled)
        if (allowOffHand.get()) {
            ItemStack offhand = mc.player.getOffHandStack();
            if (isValidTool(offhand, block)) {
                double score = scoreItem(offhand, block);
                if (best == null || score > best.score) {
                    best = new ToolSlot(SlotUtils.OFFHAND, score, ToolSource.OFFHAND);
                }
            }
        }

        return best;
    }

    private void switchToTool(ToolSlot tool) {
        int currentSelected = mc.player.getInventory().getSelectedSlot();

        if (tool.source == ToolSource.HOTBAR) {
            // Simple hotbar switch - no swap record needed
            if (tool.slot != currentSelected) {
                InvUtils.swap(tool.slot, false);
            }
        } else if (tool.source == ToolSource.INVENTORY) {
            // Borrow from inventory
            if (inventorySwitchBack.get()) {
                int borrowSlot = findFreeBorrowSlot();
                
                // Perform swap and record it
                InvUtils.quickSwap().fromId(tool.slot).to(borrowSlot);
                InvUtils.swap(borrowSlot, false);
                
                // Record this swap for later restoration
                swapHistory.add(new SwapRecord(tool.slot, borrowSlot, false));
            }
        } else if (tool.source == ToolSource.OFFHAND) {
            // Borrow from offhand
            if (offHandSwitchBack.get()) {
                int borrowSlot = findFreeBorrowSlot();
                
                // Perform swap and record it
                InvUtils.quickSwap().fromId(SlotUtils.OFFHAND).to(borrowSlot);
                InvUtils.swap(borrowSlot, false);
                
                // Record this swap for later restoration
                swapHistory.add(new SwapRecord(SlotUtils.OFFHAND, borrowSlot, true));
            }
        }
    }

    private int findFreeBorrowSlot() {
        // Prefer empty slots
        for (int i = HOTBAR_START; i <= HOTBAR_END; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }

        // Use non-protected slots
        for (int i = HOTBAR_START; i <= HOTBAR_END; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!isProtected(stack)) return i;
        }

        // Worst case: use current slot (will swap back immediately)
        return mc.player.getInventory().getSelectedSlot();
    }

    // ===== RESTORATION =====

    private void restoreTools() {
        if (!switchBack.get()) {
            clear();
            return;
        }

        if (isPaused()) {
            // Don't restore while paused, wait until next call
            return;
        }

        // Undo all swaps in reverse order (LIFO)
        for (int i = swapHistory.size() - 1; i >= 0; i--) {
            SwapRecord swap = swapHistory.get(i);
            // Swap back from borrowed hotbar slot to original source
            InvUtils.quickSwap().fromId(swap.hotbarSlot).to(swap.sourceSlot);
        }

        // Return to original selected slot
        if (originalHotbarSlot >= 0 && mc.player.getInventory().getSelectedSlot() != originalHotbarSlot) {
            InvUtils.swap(originalHotbarSlot, false);
        }

        clear();
    }

    private void clear() {
        swapHistory.clear();
        originalHotbarSlot = -1;
        switchDelayCounter = 0;
        isMining = false;
    }

    // ===== TOOL VALIDATION & SCORING =====

    private boolean isValidTool(ItemStack stack, BlockState block) {
        if (stack.isEmpty() || !isTool(stack) || shouldStopUsing(stack)) return false;

        // List filter
        if (useWhitelist.get() ? !whitelist.get().contains(stack.getItem()) : blacklist.get().contains(stack.getItem())) {
            return false;
        }

        // Suitability check
        if (!isSuitableFor(stack, block)) return false;

        // Ender chest check
        if (silkTouchEnderChest.get() && block.getBlock() == Blocks.ENDER_CHEST) {
            if (!Utils.hasEnchantments(stack, Enchantments.SILK_TOUCH)) return false;
        }

        // Ore check
        if (fortuneOres.get() && Xray.ORES.contains(block.getBlock()) && block.getBlock() != Blocks.ANCIENT_DEBRIS) {
            if (!Utils.hasEnchantments(stack, Enchantments.FORTUNE)) return false;
        }

        // Crop check
        if (fortuneCrops.get() && block.getBlock() instanceof CropBlock) {
            if (!Utils.hasEnchantments(stack, Enchantments.FORTUNE)) return false;
        }

        return true;
    }

    private double scoreItem(ItemStack stack, BlockState block) {
        if (!isSuitableFor(stack, block)) return -1;

        double score = stack.getMiningSpeedMultiplier(block) * 1000.0;
        score += Utils.getEnchantmentLevel(stack, Enchantments.EFFICIENCY) * 10;
        score += Utils.getEnchantmentLevel(stack, Enchantments.UNBREAKING);

        if (prefer.get() == EnchantmentMode.Fortune) {
            score += Utils.getEnchantmentLevel(stack, Enchantments.FORTUNE);
        } else if (prefer.get() == EnchantmentMode.SilkTouch) {
            score += Utils.getEnchantmentLevel(stack, Enchantments.SILK_TOUCH);
        }

        return score;
    }

    private boolean isSuitableFor(ItemStack stack, BlockState block) {
        if (stack.isSuitableFor(block)) return true;
        if (stack.isIn(ItemTags.SWORDS) && (block.getBlock() instanceof BambooBlock || block.getBlock() instanceof BambooShootBlock)) return true;
        if (stack.getItem() instanceof ShearsItem && (block.getBlock() instanceof LeavesBlock || block.isIn(BlockTags.WOOL))) return true;
        return false;
    }

    private boolean isTool(ItemStack stack) {
        return stack.isIn(ItemTags.AXES) || stack.isIn(ItemTags.HOES) || stack.isIn(ItemTags.PICKAXES) 
            || stack.isIn(ItemTags.SHOVELS) || stack.getItem() instanceof ShearsItem;
    }

    private boolean shouldStopUsing(ItemStack stack) {
        if (!antiBreak.get() || stack.isEmpty() || !stack.isDamageable()) return false;
        int remaining = stack.getMaxDamage() - stack.getDamage();
        return remaining < (stack.getMaxDamage() * breakDurability.get() / 100);
    }

    private boolean isProtected(ItemStack stack) {
        return !stack.isEmpty() && (doNotReplace.get().contains(stack.getItem()) || isTool(stack));
    }

    private boolean isPaused() {
        if (pauseWhenAutoEat.get()) {
            AutoEat autoEat = Modules.get().get(AutoEat.class);
            if (autoEat != null && autoEat.isActive() && autoEat.eating) return true;
        }
        if (pauseWhenKillAura.get()) {
            KillAura killAura = Modules.get().get(KillAura.class);
            if (killAura != null && killAura.isActive() && killAura.swapped) return true;
        }
        return false;
    }

    private boolean isBreaking() {
        return mc.options.attackKey.isPressed() || (mc.interactionManager != null && mc.interactionManager.isBreakingBlock());
    }

    // ===== HELPER CLASSES =====

    private enum EnchantmentMode { SilkTouch, Fortune, None }
    private enum ToolSource { HOTBAR, INVENTORY, OFFHAND }

    private static class ToolSlot {
        int slot;
        double score;
        ToolSource source;

        ToolSlot(int slot, double score, ToolSource source) {
            this.slot = slot;
            this.score = score;
            this.source = source;
        }
    }

    /**
     * Records a swap between inventory/offhand and hotbar.
     * Used for LIFO restoration when mining ends.
     */
    private static class SwapRecord {
        int sourceSlot;      // Where it came from (inventory slot or OFFHAND)
        int hotbarSlot;      // Which hotbar slot it went to
        boolean isOffhand;   // Was it from offhand?

        SwapRecord(int sourceSlot, int hotbarSlot, boolean isOffhand) {
            this.sourceSlot = sourceSlot;
            this.hotbarSlot = hotbarSlot;
            this.isOffhand = isOffhand;
        }
    }
}
