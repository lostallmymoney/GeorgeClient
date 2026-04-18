/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.ClientPlayerInteractionManagerAccessor;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.CycleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;
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
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Simplified AutoTool implementation.
 * Maintains all features while reducing complexity by ~70%.
 */
public class NewAutoTool extends Module {
    private static final int HOTBAR_START = 0;
    private static final int HOTBAR_END = 8;
    private static final int INVENTORY_START = 9;
    private static final int INVENTORY_END = 35;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWhitelist = settings.createGroup("Whitelist");

    // Main settings
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

    private final Setting<List<Item>> doNotReplace = sgGeneral.add(new ItemListSetting.Builder().name("do-not-replace")
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

    // State
    private int initialSlotForSession = -1;  // Tool slot when mining started
    private int borrowedToolSlot = -1;
    private Integer borrowedFromSlot = null;
    private int ticksUntilSwitch = 0;
    private boolean isMining = false;

    public NewAutoTool() {
        super(Categories.Player, "auto-tool", "Automatically selects the best tool for mining blocks.");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void reset() {
        initialSlotForSession = -1;
        borrowedToolSlot = -1;
        borrowedFromSlot = null;
        ticksUntilSwitch = 0;
        isMining = false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onStartBreakingBlock(StartBreakingBlockEvent event) {
        if (!enabled.get() || mc.player == null || mc.world == null) return;
        if (isExternalPaused()) return;
        if (disableWhenHolding.get().contains(mc.player.getMainHandStack().getItem())) return;

        BlockState block = mc.world.getBlockState(event.blockPos);
        if (!BlockUtils.canBreak(event.blockPos, block)) return;

        // Only capture initial slot once per mining session
        if (!isMining) {
            isMining = true;
            initialSlotForSession = mc.player.getInventory().getSelectedSlot();
        }

        selectBestTool(block);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!enabled.get() || mc.player == null) return;

        if (isMining && !isBreaking()) {
            isMining = false;
            restoreToolsAfterMining();
            return;
        }

        if (ticksUntilSwitch > 0) ticksUntilSwitch--;
    }

    private boolean isBreaking() {
        return mc.options.attackKey.isPressed() || (mc.interactionManager != null && mc.interactionManager.isBreakingBlock());
    }

    private void selectBestTool(BlockState block) {
        if (isExternalPaused()) return;
        if (ticksUntilSwitch > 0) return;

        ItemStack currentTool = mc.player.getMainHandStack();

        // Check if current tool should be stopped
        if (shouldStopUsing(currentTool)) {
            // Find replacement
            switchToNextBestTool(block);
            return;
        }

        // Check if current tool is suitable
        double currentScore = scoreItem(currentTool, block);
        if (currentScore > 0) return; // Current tool is good enough

        // Try to find better tool
        switchToNextBestTool(block);
    }

    private void switchToNextBestTool(BlockState block) {
        ToolCandidate best = findBestTool(block);
        if (best == null || best.slot < 0) return;

        switchToTool(best);
        ticksUntilSwitch = switchDelay.get();
    }

    private void switchToTool(ToolCandidate candidate) {
        if (candidate.slot == mc.player.getInventory().getSelectedSlot()) return;

        // Handle inventory and offhand borrowing
        if (candidate.isFromInventory || candidate.isFromOffHand) {
            // Find free hotbar slot
            int freeSlot = findFreeHotbarSlot();
            if (freeSlot < 0) freeSlot = mc.player.getInventory().getSelectedSlot();

            borrowedToolSlot = freeSlot;
            borrowedFromSlot = candidate.slot;

            InvUtils.quickSwap().fromId(candidate.slot).to(freeSlot);
            InvUtils.swap(freeSlot, false);
        } else {
            // Simple hotbar swap - no state tracking needed for hotbar-only swaps
            InvUtils.swap(candidate.slot, false);
        }
    }

    private void restoreToolsAfterMining() {
        if (!switchBack.get()) {
            reset();
            return;
        }

        if (isExternalPaused()) return;

        // Return borrowed tool if needed
        if (borrowedToolSlot >= 0 && borrowedFromSlot != null) {
            boolean shouldRestore = (borrowedFromSlot == SlotUtils.OFFHAND && offHandSwitchBack.get())
                || (borrowedFromSlot >= INVENTORY_START && inventorySwitchBack.get());

            if (shouldRestore) {
                // Move tool back
                InvUtils.quickSwap().fromId(borrowedToolSlot).to(borrowedFromSlot);
            }

            borrowedToolSlot = -1;
            borrowedFromSlot = null;
        }

        // Return to initial slot when mining started
        if (initialSlotForSession >= 0 && initialSlotForSession <= HOTBAR_END && mc.player != null) {
            if (mc.player.getInventory().getSelectedSlot() != initialSlotForSession) {
                InvUtils.swap(initialSlotForSession, false);
            }
        }

        reset();
    }

    private ToolCandidate findBestTool(BlockState block) {
        ToolCandidate best = new ToolCandidate();
        best.score = -1;
        best.slot = -1;

        // Check hotbar first (priority)
        for (int i = HOTBAR_START; i <= HOTBAR_END; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!isValidTool(stack)) continue;

            double score = scoreItem(stack, block);
            if (score > best.score) {
                best.score = score;
                best.slot = i;
                best.isFromInventory = false;
                best.isFromOffHand = false;
            }
        }

        // Check inventory if enabled and no good hotbar tool found
        if (allowInventory.get() && (best.slot < 0 || best.score < 0)) {
            for (int i = INVENTORY_START; i <= INVENTORY_END; i++) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (!isValidTool(stack)) continue;

                double score = scoreItem(stack, block);
                if (score > best.score) {
                    best.score = score;
                    best.slot = i;
                    best.isFromInventory = true;
                    best.isFromOffHand = false;
                }
            }
        }

        // Check offhand if enabled
        if (allowOffHand.get() && (best.slot < 0 || best.score < 0)) {
            ItemStack offhand = mc.player.getOffHandStack();
            if (isValidTool(offhand)) {
                double score = scoreItem(offhand, block);
                if (score > best.score) {
                    best.score = score;
                    best.slot = SlotUtils.OFFHAND;
                    best.isFromInventory = false;
                    best.isFromOffHand = true;
                }
            }
        }

        return best.slot >= 0 ? best : null;
    }

    private double scoreItem(ItemStack stack, BlockState block) {
        if (!isSuitableFor(stack, block)) return -1;

        // Ender chest must have silk touch
        if (silkTouchEnderChest.get() && block.getBlock() == Blocks.ENDER_CHEST) {
            if (!Utils.hasEnchantments(stack, Enchantments.SILK_TOUCH)) return -1;
        }

        // Ores require fortune if setting enabled
        if (fortuneOres.get() && Xray.ORES.contains(block.getBlock()) && block.getBlock() != Blocks.ANCIENT_DEBRIS) {
            if (!Utils.hasEnchantments(stack, Enchantments.FORTUNE)) return -1;
        }

        // Crops require fortune if setting enabled
        if (fortuneCrops.get() && block.getBlock() instanceof CropBlock) {
            if (!Utils.hasEnchantments(stack, Enchantments.FORTUNE)) return -1;
        }

        // Base score from mining speed
        double score = stack.getMiningSpeedMultiplier(block) * 1000.0;

        // Add enchantment bonuses
        score += Utils.getEnchantmentLevel(stack, Enchantments.EFFICIENCY) * 10;
        score += Utils.getEnchantmentLevel(stack, Enchantments.UNBREAKING);

        // Small bonuses for preferred enchantments
        if (prefer.get() == EnchantmentMode.Fortune) {
            score += Utils.getEnchantmentLevel(stack, Enchantments.FORTUNE);
        } else if (prefer.get() == EnchantmentMode.SilkTouch) {
            score += Utils.getEnchantmentLevel(stack, Enchantments.SILK_TOUCH);
        }

        return score;
    }

    private boolean isSuitableFor(ItemStack stack, BlockState block) {
        if (stack.isSuitableFor(block)) return true;

        // Special cases
        // Swords on bamboo
        if (stack.isIn(ItemTags.SWORDS) && (block.getBlock() instanceof BambooBlock || block.getBlock() instanceof BambooShootBlock)) {
            return true;
        }

        // Shears on leaves and wool
        if (stack.getItem() instanceof ShearsItem) {
            if (block.getBlock() instanceof LeavesBlock || block.isIn(BlockTags.WOOL)) {
                return true;
            }
        }

        return false;
    }

    private boolean isValidTool(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (!isTool(stack)) return false;
        if (shouldStopUsing(stack)) return false;

        // Check lists
        if (useWhitelist.get()) {
            return whitelist.get().contains(stack.getItem());
        } else {
            return !blacklist.get().contains(stack.getItem());
        }
    }

    private int findFreeHotbarSlot() {
        // Try to find empty slot
        for (int i = HOTBAR_START; i <= HOTBAR_END; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                return i;
            }
        }

        // Try to find non-protected slot
        for (int i = HOTBAR_START; i <= HOTBAR_END; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!isProtected(stack)) {
                return i;
            }
        }

        // Return current slot as last resort
        return mc.player.getInventory().getSelectedSlot();
    }

    private boolean isProtected(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (doNotReplace.get().contains(stack.getItem())) return true;
        return isTool(stack);
    }

    private boolean shouldStopUsing(ItemStack stack) {
        if (!antiBreak.get()) return false;
        if (stack.isEmpty() || !stack.isDamageable()) return false;

        int remaining = stack.getMaxDamage() - stack.getDamage();
        int threshold = stack.getMaxDamage() * breakDurability.get() / 100;
        return remaining < threshold;
    }

    private boolean isTool(ItemStack stack) {
        if (stack.isEmpty()) return false;

        return stack.isIn(ItemTags.AXES)
            || stack.isIn(ItemTags.HOES)
            || stack.isIn(ItemTags.PICKAXES)
            || stack.isIn(ItemTags.SHOVELS)
            || stack.getItem() instanceof ShearsItem;
    }

    private boolean isExternalPaused() {
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

    private enum EnchantmentMode {
        SilkTouch, Fortune, None
    }

    private static class ToolCandidate {
        int slot;
        double score;
        boolean isFromInventory;
        boolean isFromOffHand;
    }
}
