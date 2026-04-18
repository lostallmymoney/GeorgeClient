/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.ClientPlayerInteractionManagerAccessor;
import meteordevelopment.meteorclient.mixininterface.IClientPlayerInteractionManager;
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
import meteordevelopment.meteorclient.systems.modules.world.InfinityMiner;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
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
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ShearsItem;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public class NewAutoTool extends Module {
    private static final int HOTBAR_START = 0;
    private static final int HOTBAR_END = 8;
    private static final int INVENTORY_START = 9;
    private static final int INVENTORY_ROW1_END = 17;
    private static final int INVENTORY_ROW2_END = 26;
    private static final int INVENTORY_END = 35;
    private static final int BREAK_ATTEMPT_GRACE_TICKS = 2;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAutoMend = settings.createGroup("Auto-mend");
    private final SettingGroup sgWhitelist = settings.createGroup("Whitelist");

    private final Setting<Boolean> autoMendMenu = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-mend")
        .description("Shows the Auto-mend submenu.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> toolSubmodule = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-auto-tool")
        .description("Enables Auto-tool switching logic.")
        .defaultValue(false)
        .onChanged(value -> syncBackboneActivation())
        .visible(this::showAutoToolSettings)
        .build()
    );

    private final Setting<Keybind> cycleTouchSettings;
    private final Setting<Boolean> touchCycleChatFeedback;
    private final Setting<Keybind> autoToolToggleBind;
    private final Setting<Boolean> autoToolToggleChatFeedback;
    private final Setting<Keybind> autoMendToggleBind;
    private final Setting<Boolean> autoMendToggleChatFeedback;

    {
        ExtraBindSettings autoToolBindSettings = addBindSettingWithChatFeedback(
            sgGeneral,
            "auto-tool-bind",
            "Bind to toggle Auto-tool enabled.",
            "auto-tool-bind-chat-feedback",
            "Sends chat feedback when toggling Auto-tool via bind.",
            this::showAutoToolSettings
        );

        autoToolToggleBind = autoToolBindSettings.bind();
        autoToolToggleChatFeedback = autoToolBindSettings.chatFeedback();

        ExtraBindSettings touchCycleBindSettings = addBindSettingWithChatFeedback(
            sgGeneral,
            "touch-cycle-bind",
            "Secondary bind to cycle touch preference: Silk Touch -> Fortune -> None.",
            "touch-cycle-chat-feedback",
            "Sends chat feedback when cycling touch preference.",
            this::showAutoToolSettings
        );

        cycleTouchSettings = touchCycleBindSettings.bind();
        touchCycleChatFeedback = touchCycleBindSettings.chatFeedback();

        ExtraBindSettings autoMendBindSettings = addBindSettingWithChatFeedback(
            sgGeneral,
            "auto-mend-bind",
            "Bind to toggle Auto-mend enabled.",
            "auto-mend-bind-chat-feedback",
            "Sends chat feedback when toggling Auto-mend via bind.",
            autoMendMenu::get
        );

        autoMendToggleBind = autoMendBindSettings.bind();
        autoMendToggleChatFeedback = autoMendBindSettings.chatFeedback();
    }

    private final CycleSetting<EnchantPreference> prefer = sgGeneral.add(new CycleSetting.Builder<EnchantPreference>()
        .name("prefer")
        .description("Either to prefer Silk Touch, Fortune, or none.")
        .defaultValue(EnchantPreference.Fortune)
        .visible(this::showAutoToolSettings)
        .build()
    );

    private final Setting<Boolean> silkTouchForEnderChest = sgGeneral.add(new BoolSetting.Builder()
        .name("silk-touch-for-ender-chest")
        .description("Mines Ender Chests only with the Silk Touch enchantment.")
        .defaultValue(true)
        .visible(this::showAutoToolSettings)
        .build()
    );

    private final Setting<Boolean> fortuneForOres = sgGeneral.add(new BoolSetting.Builder()
        .name("fortune-for-ores")
        .description("Mines Ores only with the Fortune enchantment.")
        .defaultValue(false)
        .visible(this::showAutoToolSettings)
        .build()
    );

    private final Setting<Boolean> fortuneForCrops = sgGeneral.add(new BoolSetting.Builder()
        .name("fortune-for-crops")
        .description("Mines crops only with the Fortune enchantment.")
        .defaultValue(true)
        .visible(this::showAutoToolSettings)
        .build()
    );

    private final Setting<Boolean> antiBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-break")
        .description("Stops you from breaking your tool.")
        .defaultValue(true)
        .visible(this::showAutoToolSettings)
        .build()
    );

    private final Setting<Integer> breakDurability = sgGeneral.add(new IntSetting.Builder()
        .name("anti-break-percentage")
        .description("The durability percentage to stop using a tool.")
        .defaultValue(1)
        .range(1, 100)
        .sliderRange(1, 100)
        .visible(() -> showAutoToolSettings() && antiBreak.get())
        .build()
    );

    private final Setting<Boolean> switchBack = sgGeneral.add(new BoolSetting.Builder()
        .name("switch-back")
        .description("Switches your hand to whatever was selected when releasing your attack key.")
        .defaultValue(true)
        .visible(this::showAutoToolSettings)
        .build()
    );

    private final Setting<ToolOriginPriority> toolOriginPriority = sgGeneral.add(new EnumSetting.Builder<ToolOriginPriority>()
        .name("tool-origin-priority")
        .description("Prioritizes where tools are selected from.")
        .defaultValue(ToolOriginPriority.None)
        .visible(this::showAutoToolSettings)
        .build()
    );

    private final Setting<Boolean> allowInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-inventory")
        .description("Allows taking tools from main inventory when none are valid in hotbar.")
        .defaultValue(true)
        .visible(this::showAutoToolSettings)
        .build()
    );

    private final Setting<Boolean> allowRandomInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-random-inventory")
        .description("When borrowing from inventory, uses first valid slot instead of best score. Faster.")
        .defaultValue(false)
        .visible(() -> showAutoToolSettings() && allowInventory.get())
        .build()
    );

    private final Setting<Boolean> allowOffHand = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-off-hand")
        .description("Allows taking tools from offhand when none are valid in hotbar.")
        .defaultValue(true)
        .visible(this::showAutoToolSettings)
        .build()
    );

    private final Setting<Boolean> useMultipleHotbarSlots = sgGeneral.add(new BoolSetting.Builder()
        .name("use-multiple-hotbar-slots")
        .description("When borrowing tools from inventory/offhand, tries to avoid replacing tools and do-not-replace items.")
        .defaultValue(true)
        .visible(this::showAutoToolSettings)
        .build()
    );

    private final Setting<List<Item>> doNotReplace = sgGeneral.add(new ItemListSetting.Builder()
        .name("do-not-replace")
        .description("Items to avoid replacing when borrowing tools from inventory/offhand.")
        .defaultValue(
            Items.STONE_SWORD,
            Items.GOLDEN_SWORD,
            Items.IRON_SWORD,
            Items.DIAMOND_SWORD,
            Items.NETHERITE_SWORD
        )
        .visible(this::showAutoToolSettings)
        .build()
    );

    private final Setting<List<Item>> disableWhenHolding = sgGeneral.add(new ItemListSetting.Builder()
        .name("disable-when-holding")
        .description("Disables Auto-tool switching while holding these items.")
        .visible(this::showAutoToolSettings)
        .build()
    );

    private final Setting<Integer> switchDelay = sgGeneral.add(new IntSetting.Builder()
        .name("switch-delay")
        .description("Delay in ticks before switching to a hotbar tool.")
        .defaultValue(0)
        .range(0, 10)
        .sliderRange(0, 10)
        .visible(this::showAutoToolSettings)
        .build()
    );

    private final Setting<Integer> inventoryAndOffHandSwitchDelay = sgGeneral.add(new IntSetting.Builder()
        .name("inventory-delay")
        .description("Delay in ticks before switching to tools from inventory.")
        .defaultValue(0)
        .range(0, 10)
        .sliderRange(0, 10)
        .visible(this::showAutoToolSettings)
        .build()
    );

    private final Setting<Boolean> inventorySwitchBack = sgGeneral.add(new BoolSetting.Builder()
        .name("inventory-switch-back")
        .description("When taking a tool from inventory, puts it back and restores the previous hotbar item.")
        .defaultValue(true)
        .visible(() -> showAutoToolSettings() && allowInventory.get())
        .build()
    );

    private final Setting<Boolean> offHandSwitchBack = sgGeneral.add(new BoolSetting.Builder()
        .name("off-hand-switch-back")
        .description("When taking a tool from offhand, puts it back in offhand afterwards.")
        .defaultValue(true)
        .visible(() -> showAutoToolSettings() && allowOffHand.get())
        .build()
    );

    private final Setting<Boolean> lastMoveStuckItemProtection = sgGeneral.add(new BoolSetting.Builder()
        .name("last-move-stuck-item-protection")
        .description("Pauses Auto-tool inventory moves and tries to return hidden cursor items to the last intended slot after a short rollback window.")
        .defaultValue(true)
        .visible(this::showAutoToolSettings)
        .build()
    );

    private final Setting<Integer> lastMoveStuckItemCheckTicks = sgGeneral.add(new IntSetting.Builder()
        .name("last-move-stuck-item-check-ticks")
        .description("Ticks to keep the last move target around before treating a hidden cursor stack as stuck.")
        .defaultValue(2)
        .range(0, 10)
        .sliderRange(0, 10)
        .visible(() -> showAutoToolSettings() && lastMoveStuckItemProtection.get())
        .build()
    );

    private final Setting<Boolean> pauseWhenAutoEat = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-when-auto-eat")
        .description("Pauses Auto-tool switching and restore while AutoEat is actively eating.")
        .defaultValue(true)
        .visible(this::showAutoToolSettings)
        .build()
    );

    private final Setting<Boolean> pauseWhenKillAuraSwaps = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-when-killaura-swaps")
        .description("Pauses Auto-tool switching and restore while KillAura temporarily swaps to a weapon.")
        .defaultValue(true)
        .visible(this::showAutoToolSettings)
        .build()
    );

    private final Setting<ListMode> listMode = sgWhitelist.add(new EnumSetting.Builder<ListMode>()
        .name("list-mode")
        .description("Selection mode.")
        .defaultValue(ListMode.Blacklist)
        .visible(this::showAutoToolSettings)
        .build()
    );

    private final Setting<List<Item>> whitelist = sgWhitelist.add(new ItemListSetting.Builder()
        .name("whitelist")
        .description("The tools you want to use.")
        .visible(() -> showAutoToolSettings() && listMode.get() == ListMode.Whitelist)
        .filter(NewAutoTool::isTool)
        .build()
    );

    private final Setting<List<Item>> blacklist = sgWhitelist.add(new ItemListSetting.Builder()
        .name("blacklist")
        .description("The tools you don't want to use.")
        .visible(() -> showAutoToolSettings() && listMode.get() == ListMode.Blacklist)
        .filter(NewAutoTool::isTool)
        .build()
    );

    private final AutoMendSubmodule autoMendSubmodule = new AutoMendSubmodule();

    private int breakAttemptGraceTicks;
    private int managedHotbarSlot = -1;
    private int switchBackSlot = -1;
    private StackSignature switchBackSignature;
    private PendingSwitch pendingSwitch;
    private final List<BorrowSession> borrowSessions = new ArrayList<>();
    private int cursorProtectionTicksLeft;
    private int lastMoveTargetSlot = -1;
    private int lastMoveFallbackSlot = -1;
    private boolean syncingBackboneActivation;
    private boolean baritoneReflectionChecked;
    private Method baritoneGetProviderMethod;
    private Method baritoneGetPrimaryBaritoneMethod;
    private Method baritoneGetPathingControlManagerMethod;
    private Method baritoneMostRecentInControlMethod;
    private Method baritoneProcessIsActiveMethod;

    public NewAutoTool() {
        super(Categories.Player, "auto-tool", "Automatically switches to the most effective tool when performing an action.");
        disablePrimaryBindSettings();
    }

    @Override
    public void onActivate() {
        resetToolState(false);
        autoMendSubmodule.onActivate();
    }

    @Override
    public void onDeactivate() {
        resetToolState(true);
        autoMendSubmodule.onDeactivate();
    }

    @Override
    protected void onExtraBindAction(Setting<Keybind> bindSetting) {
        if (bindSetting == autoToolToggleBind) {
            toolSubmodule.set(!toolSubmodule.get());
            syncBackboneActivation();

            if (autoToolToggleChatFeedback.get() && Config.get().chatFeedback.get()) {
                info("Auto-tool %s(default).", toolSubmodule.get() ? "(highlight)enabled" : "(highlight)disabled");
            }

            return;
        }

        if (bindSetting == cycleTouchSettings) {
            if (!toolSubmodule.get()) return;

            if (prefer.parse("cycle") && touchCycleChatFeedback.get() && Config.get().chatFeedback.get()) {
                info("AutoTool set to (highlight)%s(default).", prefer.get().name());
            }

            return;
        }

        if (bindSetting == autoMendToggleBind) autoMendSubmodule.toggleEnabled(autoMendToggleChatFeedback.get());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (handleStuckCursorProtection()) return;

        tickAutoTool();
        autoMendSubmodule.tick();
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onStartBreakingBlock(StartBreakingBlockEvent event) {
        onStartBreakingBlockAutoTool(event);
    }

    private void resetToolState(boolean restoreBorrowSession) {
        if (restoreBorrowSession && !borrowSessions.isEmpty()) closeBorrowSessions(true);

        breakAttemptGraceTicks = 0;
        managedHotbarSlot = -1;

        pendingSwitch = null;
        borrowSessions.clear();
        clearCursorProtectionState();

        clearSwitchBackState();
    }

    private void tickAutoTool() {
        if (!(toolSubmodule.get() || pendingSwitch != null || !borrowSessions.isEmpty() || breakAttemptGraceTicks > 0)) return;

        if (!toolSubmodule.get()) pendingSwitch = null;

        boolean externalSwapPause = isExternalSwapPauseActive();
        if (externalSwapPause) return;

        if (breakAttemptGraceTicks > 0) breakAttemptGraceTicks--;

        boolean attackPressed = isAttackPressed();

        if (toolSubmodule.get() && !Modules.get().isActive(InfinityMiner.class) && attackPressed && pendingSwitch == null && !isDisabledByHolding()) {
            tryScheduleFromCrosshairBlock();
        }

        executePendingSwitch(attackPressed);

        if (!attackPressed && breakAttemptGraceTicks == 0 && pendingSwitch == null) {
            if (shouldDelayRestoreForExternalHotbarControl()) return;

            restoreBorrowOnRelease();
            restoreSwitchBackOnRelease();

            if (pendingSwitch == null && borrowSessions.isEmpty()) managedHotbarSlot = -1;
        }
    }

    private void onStartBreakingBlockAutoTool(StartBreakingBlockEvent event) {
        if (Modules.get().isActive(InfinityMiner.class)) return;
        if (mc.player == null || mc.world == null || mc.player.isCreative()) return;
        if (!toolSubmodule.get()) return;
        if (isExternalSwapPauseActive()) return;
        if (isDisabledByHolding()) return;

        BlockState blockState = mc.world.getBlockState(event.blockPos);
        if (!BlockUtils.canBreak(event.blockPos, blockState)) return;

        breakAttemptGraceTicks = BREAK_ATTEMPT_GRACE_TICKS;

        tryScheduleForBlockState(blockState);

        ItemStack currentStack = mc.player.getMainHandStack();
        if (shouldStopUsing(currentStack) && isTool(currentStack)) {
            tryReturnNearBrokenBorrowedTool();
            mc.options.attackKey.setPressed(false);
            event.cancel();
        }
    }

    private void executePendingSwitch(boolean attackPressed) {
        if (pendingSwitch == null) return;

        if (!attackPressed && breakAttemptGraceTicks == 0) {
            pendingSwitch = null;
            return;
        }

        if (pendingSwitch.delayTicks > 0) {
            pendingSwitch.delayTicks--;
            return;
        }

        PendingSwitch current = pendingSwitch;
        pendingSwitch = null;

        if (!isPendingSwitchStillValid(current)) {
            if (toolSubmodule.get() && attackPressed && !isDisabledByHolding()) tryScheduleFromCrosshairBlock();
            return;
        }

        switch (current.type) {
            case Hotbar -> switchToHotbar(current.slot);
            case Inventory -> borrowFromInventory(current.slot, current.signature);
            case OffHand -> borrowFromOffHand(current.signature);
        }
    }

    private void restoreBorrowOnRelease() {
        if (borrowSessions.isEmpty()) return;

        for (int i = borrowSessions.size() - 1; i >= 0; i--) {
            BorrowSession session = borrowSessions.get(i);
            closeBorrowSession(session, session.restoreOnRelease());
        }
    }

    private void restoreSwitchBackOnRelease() {
        if (!switchBack.get()) {
            clearSwitchBackState();
            return;
        }

        if (switchBackSlot == -1 || !borrowSessions.isEmpty() || pendingSwitch != null) return;

        int targetSlot = switchBackSlot;
        if (switchBackSignature != null && !hotbarSignatureAt(targetSlot, switchBackSignature)) {
            int moved = findHotbarSlotForSignature(switchBackSignature);
            if (moved != -1) targetSlot = moved;
        }

        if (SlotUtils.isHotbar(targetSlot)) InvUtils.swap(targetSlot, false);
        clearSwitchBackState();
    }

    private void clearSwitchBackState() {
        switchBackSlot = -1;
        switchBackSignature = null;
    }

    private void captureSwitchBackIfNeeded() {
        if (!switchBack.get() || switchBackSlot != -1) return;

        int selected = mc.player.getInventory().getSelectedSlot();
        switchBackSlot = selected;

        ItemStack selectedStack = mc.player.getInventory().getStack(selected);
        switchBackSignature = signatureOf(selectedStack, false);
    }

    private boolean switchToHotbar(int slot) {
        if (!SlotUtils.isHotbar(slot)) return false;

        captureSwitchBackIfNeeded();
        managedHotbarSlot = slot;
        return InvUtils.swap(slot, false);
    }

    private boolean borrowFromInventory(int sourceSlot, StackSignature targetSignature) {
        if (!allowInventory.get()) return false;
        if (sourceSlot < INVENTORY_START || sourceSlot > INVENTORY_END) return false;

        ItemStack sourceStack = mc.player.getInventory().getStack(sourceSlot);
        if (sourceStack.isEmpty()) return false;

        StackSignature sourceSignature = signatureOf(sourceStack, true);
        if (!signatureMatches(sourceStack, targetSignature)) return false;

        BorrowSession existing = findBorrowSession(BorrowType.Inventory, sourceSlot, sourceSignature);
        int existingHotbarSlot = resolveBorrowSessionHotbarSlot(existing);
        if (SlotUtils.isHotbar(existingHotbarSlot)) {
            if (mc.player.getInventory().getSelectedSlot() != existingHotbarSlot) InvUtils.swap(existingHotbarSlot, false);
            managedHotbarSlot = existingHotbarSlot;
            return true;
        }

        int hotbarSlot = resolveManagedHotbarSlot();
        if (!SlotUtils.isHotbar(hotbarSlot)) return false;

        captureSwitchBackIfNeeded();
        if (mc.player.getInventory().getSelectedSlot() != hotbarSlot) InvUtils.swap(hotbarSlot, false);

        quickSwapAndSync(hotbarSlot, sourceSlot);

        borrowSessions.add(new BorrowSession(
            BorrowType.Inventory,
            sourceSlot,
            hotbarSlot,
            sourceSignature,
            inventorySwitchBack.get()
        ));

        managedHotbarSlot = hotbarSlot;
        return true;
    }

    private boolean borrowFromOffHand(StackSignature targetSignature) {
        if (!allowOffHand.get()) return false;

        ItemStack offHand = mc.player.getOffHandStack();
        if (offHand.isEmpty()) return false;

        StackSignature offHandSignature = signatureOf(offHand, true);
        if (!signatureMatches(offHand, targetSignature)) return false;

        BorrowSession existing = findBorrowSession(BorrowType.OffHand, SlotUtils.OFFHAND, offHandSignature);
        int existingHotbarSlot = resolveBorrowSessionHotbarSlot(existing);
        if (SlotUtils.isHotbar(existingHotbarSlot)) {
            if (mc.player.getInventory().getSelectedSlot() != existingHotbarSlot) InvUtils.swap(existingHotbarSlot, false);
            managedHotbarSlot = existingHotbarSlot;
            return true;
        }

        int hotbarSlot = resolveManagedHotbarSlot();
        if (!SlotUtils.isHotbar(hotbarSlot)) return false;

        captureSwitchBackIfNeeded();
        if (mc.player.getInventory().getSelectedSlot() != hotbarSlot) InvUtils.swap(hotbarSlot, false);

        quickSwapAndSync(SlotUtils.OFFHAND, hotbarSlot);

        borrowSessions.add(new BorrowSession(
            BorrowType.OffHand,
            SlotUtils.OFFHAND,
            hotbarSlot,
            offHandSignature,
            offHandSwitchBack.get()
        ));

        managedHotbarSlot = hotbarSlot;
        return true;
    }

    private void closeBorrowSessions(boolean restore) {
        for (int i = borrowSessions.size() - 1; i >= 0; i--) {
            closeBorrowSession(borrowSessions.get(i), restore);
        }
    }

    private void closeBorrowSession(BorrowSession session, boolean restore) {
        if (session == null) return;
        if (!restore) {
            borrowSessions.remove(session);
            return;
        }
        int hotbarSlot = resolveBorrowSessionHotbarSlot(session);
        if (!SlotUtils.isHotbar(hotbarSlot)) {
            if (isBorrowSessionRestored(session)) borrowSessions.remove(session);
            return;
        }

        boolean restored = false;
        if (session.type == BorrowType.Inventory) {
            restored = session.sourceSlot >= 0 && session.sourceSlot <= INVENTORY_END && quickSwapAndSync(hotbarSlot, session.sourceSlot);
        }
        else {
            restored = quickSwapAndSync(SlotUtils.OFFHAND, hotbarSlot);
        }

        if (restored) borrowSessions.remove(session);
    }

    private boolean hotbarSignatureAt(int hotbarSlot, StackSignature signature) {
        if (!SlotUtils.isHotbar(hotbarSlot)) return false;
        return signatureMatches(mc.player.getInventory().getStack(hotbarSlot), signature);
    }

    private boolean inventorySignatureAt(int inventorySlot, StackSignature signature) {
        return inventorySlot >= INVENTORY_START && inventorySlot <= INVENTORY_END && signatureMatches(mc.player.getInventory().getStack(inventorySlot), signature);
    }

    private int findHotbarSlotForSignature(StackSignature signature) {
        for (int i = HOTBAR_START; i <= HOTBAR_END; i++) {
            if (hotbarSignatureAt(i, signature)) return i;
        }

        return -1;
    }

    private StackSignature signatureOf(ItemStack stack, boolean ignoreDamage) {
        return stack.isEmpty() ? null : new StackSignature(stack.copy(), ignoreDamage);
    }

    private boolean signatureMatches(ItemStack stack, StackSignature signature) {
        if (signature == null || stack.isEmpty() || stack.getCount() != signature.snapshot.getCount()) return false;

        if (!signature.ignoreDamage) return ItemStack.areItemsAndComponentsEqual(stack, signature.snapshot);

        ItemStack normalizedStack = stack.copy();
        ItemStack normalizedSnapshot = signature.snapshot.copy();
        normalizedStack.set(DataComponentTypes.DAMAGE, 0);
        normalizedSnapshot.set(DataComponentTypes.DAMAGE, 0);
        return ItemStack.areItemsAndComponentsEqual(normalizedStack, normalizedSnapshot);
    }

    private boolean quickSwapAndSync(int fromSlot, int toSlot) {
        if (!prepareQuickSwap(fromSlot, toSlot)) return false;

        InvUtils.quickSwap().fromId(fromSlot).to(toSlot);
        syncSelectedSlot();
        return true;
    }

    private void syncSelectedSlot() {
        if (mc.interactionManager instanceof IClientPlayerInteractionManager interactionManager) interactionManager.meteor$syncSelected();
    }

    private int resolveManagedHotbarSlot() {
        BorrowSession latestBorrow = getLatestBorrowSession();
        int latestBorrowHotbarSlot = resolveBorrowSessionHotbarSlot(latestBorrow);
        if (SlotUtils.isHotbar(latestBorrowHotbarSlot)) return latestBorrowHotbarSlot;
        if (SlotUtils.isHotbar(managedHotbarSlot)) return managedHotbarSlot;

        int selected = mc.player.getInventory().getSelectedSlot();
        if (!SlotUtils.isHotbar(selected)) selected = HOTBAR_START;
        if (!useMultipleHotbarSlots.get()) return selected;

        ItemStack selectedStack = mc.player.getInventory().getStack(selected);
        if (!hasBorrowSessionOnHotbar(selected) && !isProtectedBorrowSlot(selectedStack)) return selected;

        return findPreferredBorrowSlot(selected);
    }

    private boolean isDoNotReplaceStack(ItemStack stack) {
        return !stack.isEmpty() && doNotReplace.get().contains(stack.getItem());
    }

    private boolean isProtectedBorrowSlot(ItemStack stack) {
        return !stack.isEmpty() && (isDoNotReplaceStack(stack) || isTool(stack));
    }

    private int findPreferredBorrowSlot(int avoidSlot) {
        for (int i = HOTBAR_START; i <= HOTBAR_END; i++) {
            if (i == avoidSlot) continue;
            if (hasBorrowSessionOnHotbar(i)) continue;
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }

        for (int i = HOTBAR_START; i <= HOTBAR_END; i++) {
            if (i == avoidSlot) continue;
            if (hasBorrowSessionOnHotbar(i)) continue;
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!isProtectedBorrowSlot(stack)) return i;
        }

        for (int i = HOTBAR_START; i <= HOTBAR_END; i++) {
            if (i == avoidSlot) continue;
            if (hasBorrowSessionOnHotbar(i)) continue;
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!isDoNotReplaceStack(stack)) return i;
        }

        for (int i = HOTBAR_START; i <= HOTBAR_END; i++) {
            if (hasBorrowSessionOnHotbar(i)) continue;
            if (i != avoidSlot) return i;
        }

        return avoidSlot;
    }

    private boolean tryReturnNearBrokenBorrowedTool() {
        for (int i = borrowSessions.size() - 1; i >= 0; i--) {
            BorrowSession session = borrowSessions.get(i);
            int hotbarSlot = resolveBorrowSessionHotbarSlot(session);
            if (session.type != BorrowType.Inventory || !SlotUtils.isHotbar(hotbarSlot)) continue;

            ItemStack borrowed = mc.player.getInventory().getStack(hotbarSlot);
            if (!isTool(borrowed) || !shouldStopUsing(borrowed)) continue;

            closeBorrowSession(session, true);
            pendingSwitch = null;
            managedHotbarSlot = -1;
            return true;
        }

        return false;
    }

    private boolean handleStuckCursorProtection() {
        if (mc.player == null || mc.interactionManager == null) return false;

        ItemStack cursorStack = mc.player.currentScreenHandler.getCursorStack();
        if (cursorStack.isEmpty()) {
            if (cursorProtectionTicksLeft > 0) cursorProtectionTicksLeft--;
            else if (cursorProtectionTicksLeft == 0) clearCursorProtectionState();
            return false;
        }

        if (mc.currentScreen != null) return true;
        if (!lastMoveStuckItemProtection.get()) return true;

        if (cursorProtectionTicksLeft > 0) {
            cursorProtectionTicksLeft--;
            return true;
        }

        if (InvUtils.tryStoreCursorInPlayerInventory(lastMoveTargetSlot, lastMoveFallbackSlot)) clearCursorProtectionState();
        return true;
    }

    private boolean prepareQuickSwap(int fromSlot, int toSlot) {
        if (mc.player == null || mc.interactionManager == null) return false;

        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            if (mc.currentScreen != null) return false;
            if (!lastMoveStuckItemProtection.get()) return false;
            if (!InvUtils.tryStoreCursorInPlayerInventory(toSlot, fromSlot)) return false;
        }

        rememberLastMove(toSlot, fromSlot);
        return true;
    }

    private void rememberLastMove(int targetSlot, int fallbackSlot) {
        lastMoveTargetSlot = sanitizeCursorProtectionSlot(targetSlot);
        lastMoveFallbackSlot = sanitizeCursorProtectionSlot(fallbackSlot);
        cursorProtectionTicksLeft = lastMoveStuckItemCheckTicks.get();
    }

    private void clearCursorProtectionState() {
        cursorProtectionTicksLeft = -1;
        lastMoveTargetSlot = -1;
        lastMoveFallbackSlot = -1;
    }

    private int sanitizeCursorProtectionSlot(int slotIndex) {
        return SlotUtils.isHotbar(slotIndex) || (slotIndex >= INVENTORY_START && slotIndex <= INVENTORY_END) || slotIndex == SlotUtils.OFFHAND
            ? slotIndex
            : -1;
    }

    private void tryScheduleFromCrosshairBlock() {
        if (mc.interactionManager != null && mc.interactionManager.isBreakingBlock() && mc.interactionManager instanceof ClientPlayerInteractionManagerAccessor accessor) {
            BlockPos breakingPos = accessor.meteor$getCurrentBreakingBlockPos();
            if (breakingPos != null) {
                BlockState blockState = mc.world.getBlockState(breakingPos);
                if (BlockUtils.canBreak(breakingPos, blockState)) {
                    tryScheduleForBlockState(blockState);
                    return;
                }
            }
        }

        if (mc.crosshairTarget instanceof BlockHitResult hit) {
            BlockPos crosshairPos = hit.getBlockPos();
            BlockState blockState = mc.world.getBlockState(crosshairPos);
            if (BlockUtils.canBreak(crosshairPos, blockState)) tryScheduleForBlockState(blockState);
        }
    }

    private void tryScheduleForBlockState(BlockState blockState) {
        ItemStack currentStack = mc.player.getMainHandStack();
        double currentScore = scoreFor(currentStack, blockState);
        boolean shouldReplaceCurrent = shouldStopUsing(currentStack) || !isTool(currentStack);

        ToolOrigin[] origins = switch (toolOriginPriority.get()) {
            case None, Hotbar -> new ToolOrigin[] { ToolOrigin.Hotbar, ToolOrigin.Inventory, ToolOrigin.OffHand };
            case Inventory -> new ToolOrigin[] { ToolOrigin.Inventory, ToolOrigin.Hotbar };
            case OffHand -> new ToolOrigin[] { ToolOrigin.OffHand, ToolOrigin.Hotbar, ToolOrigin.Inventory };
            case InventoryRow1 -> new ToolOrigin[] { ToolOrigin.InventoryRow1, ToolOrigin.Hotbar, ToolOrigin.Inventory };
            case InventoryRow2 -> new ToolOrigin[] { ToolOrigin.InventoryRow2, ToolOrigin.Hotbar, ToolOrigin.Inventory };
            case InventoryRow3 -> new ToolOrigin[] { ToolOrigin.InventoryRow3, ToolOrigin.Hotbar, ToolOrigin.Inventory };
        };

        Candidate candidate = null;
        if (hasPreferredVeto()) candidate = bestCandidateAcrossOrigins(blockState, currentScore, shouldReplaceCurrent, true, origins);
        if (candidate == null) candidate = bestCandidateAcrossOrigins(blockState, currentScore, shouldReplaceCurrent, false, origins);

        if (candidate != null) scheduleSwitch(candidate);
    }

    private void scheduleSwitch(Candidate candidate) {
        int delay = switchDelay(candidate.type);

        if (pendingSwitch == null) {
            pendingSwitch = new PendingSwitch(candidate.type, candidate.slot, candidate.signature, delay);
            return;
        }

        pendingSwitch.type = candidate.type;
        pendingSwitch.slot = candidate.slot;
        pendingSwitch.signature = candidate.signature;
        pendingSwitch.delayTicks = Math.min(pendingSwitch.delayTicks, delay);
    }

    private int switchDelay(SwitchType type) {
        return switch (type) {
            case Hotbar -> switchDelay.get();
            case Inventory, OffHand -> inventoryAndOffHandSwitchDelay.get();
        };
    }

    private Candidate bestCandidateAcrossOrigins(BlockState blockState, double currentScore, boolean shouldReplaceCurrent, boolean requirePreferredEnchant, ToolOrigin... origins) {
        Candidate best = null;
        int bestPriority = Integer.MAX_VALUE;

        for (int i = 0; i < origins.length; i++) {
            Candidate candidate = bestCandidate(origins[i], blockState, requirePreferredEnchant);
            if (candidate == null) continue;
            if (!(shouldReplaceCurrent || candidate.score > currentScore)) continue;

            if (best == null || candidate.score > best.score || (candidate.score == best.score && i < bestPriority)) {
                best = candidate;
                bestPriority = i;
            }
        }

        return best;
    }

    private Candidate bestCandidate(ToolOrigin origin, BlockState blockState, boolean requirePreferredEnchant) {
        boolean randomInventory = allowRandomInventory.get();

        return switch (origin) {
            case Hotbar -> findBestCandidateInRange(HOTBAR_START, HOTBAR_END, SwitchType.Hotbar, blockState, requirePreferredEnchant);
            case Inventory -> allowInventory.get()
                ? (randomInventory
                ? findFirstCandidateInRange(INVENTORY_START, INVENTORY_END, SwitchType.Inventory, blockState, requirePreferredEnchant)
                : findBestCandidateInRange(INVENTORY_START, INVENTORY_END, SwitchType.Inventory, blockState, requirePreferredEnchant))
                : null;
            case InventoryRow1 -> allowInventory.get()
                ? (randomInventory
                ? findFirstCandidateInRange(INVENTORY_START, INVENTORY_ROW1_END, SwitchType.Inventory, blockState, requirePreferredEnchant)
                : findBestCandidateInRange(INVENTORY_START, INVENTORY_ROW1_END, SwitchType.Inventory, blockState, requirePreferredEnchant))
                : null;
            case InventoryRow2 -> allowInventory.get()
                ? (randomInventory
                ? findFirstCandidateInRange(INVENTORY_ROW1_END + 1, INVENTORY_ROW2_END, SwitchType.Inventory, blockState, requirePreferredEnchant)
                : findBestCandidateInRange(INVENTORY_ROW1_END + 1, INVENTORY_ROW2_END, SwitchType.Inventory, blockState, requirePreferredEnchant))
                : null;
            case InventoryRow3 -> allowInventory.get()
                ? (randomInventory
                ? findFirstCandidateInRange(INVENTORY_ROW2_END + 1, INVENTORY_END, SwitchType.Inventory, blockState, requirePreferredEnchant)
                : findBestCandidateInRange(INVENTORY_ROW2_END + 1, INVENTORY_END, SwitchType.Inventory, blockState, requirePreferredEnchant))
                : null;
            case OffHand -> getOffhandCandidate(blockState, requirePreferredEnchant);
        };
    }

    private Candidate findBestCandidateInRange(int start, int end, SwitchType type, BlockState blockState, boolean requirePreferredEnchant) {
        double bestScore = -1;
        int bestSlot = -1;

        for (int i = start; i <= end; i++) {
            ItemStack itemStack = mc.player.getInventory().getStack(i);
            if (!isItemInList(itemStack)) continue;
            if (requirePreferredEnchant && !isPreferredEnchantTool(itemStack)) continue;

            double score = scoreFor(itemStack, blockState);
            if (score < 0) continue;

            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        return bestSlot == -1 ? null : new Candidate(bestSlot, type, bestScore, signatureOf(mc.player.getInventory().getStack(bestSlot), true));
    }

    private Candidate findFirstCandidateInRange(int start, int end, SwitchType type, BlockState blockState, boolean requirePreferredEnchant) {
        for (int i = start; i <= end; i++) {
            ItemStack itemStack = mc.player.getInventory().getStack(i);
            if (!isItemInList(itemStack)) continue;
            if (requirePreferredEnchant && !isPreferredEnchantTool(itemStack)) continue;

            double score = scoreFor(itemStack, blockState);
            if (score >= 0) return new Candidate(i, type, score, signatureOf(itemStack, true));
        }

        return null;
    }

    private Candidate getOffhandCandidate(BlockState blockState, boolean requirePreferredEnchant) {
        if (!allowOffHand.get()) return null;

        ItemStack offhand = mc.player.getOffHandStack();
        if (!isItemInList(offhand)) return null;
        if (requirePreferredEnchant && !isPreferredEnchantTool(offhand)) return null;

        double score = scoreFor(offhand, blockState);
        return score >= 0 ? new Candidate(-1, SwitchType.OffHand, score, signatureOf(offhand, true)) : null;
    }

    private boolean shouldStopUsing(ItemStack itemStack) {
        if (!antiBreak.get()) return false;
        if (itemStack.isEmpty() || !itemStack.isDamageable()) return false;

        int remaining = itemStack.getMaxDamage() - itemStack.getDamage();
        int threshold = itemStack.getMaxDamage() * breakDurability.get() / 100;
        return remaining < threshold;
    }

    private boolean hasPreferredVeto() {
        return prefer.get() != EnchantPreference.None;
    }

    private boolean isPreferredEnchantTool(ItemStack stack) {
        return switch (prefer.get()) {
            case Fortune -> Utils.hasEnchantments(stack, Enchantments.FORTUNE);
            case SilkTouch -> Utils.hasEnchantments(stack, Enchantments.SILK_TOUCH);
            case None -> true;
        };
    }

    private boolean isItemInList(ItemStack itemStack) {
        return listMode.get() == ListMode.Whitelist
            ? whitelist.get().contains(itemStack.getItem())
            : !blacklist.get().contains(itemStack.getItem());
    }

    private double scoreFor(ItemStack itemStack, BlockState blockState) {
        return getScore(
            itemStack,
            blockState,
            silkTouchForEnderChest.get(),
            fortuneForOres.get(),
            fortuneForCrops.get(),
            prefer.get(),
            stack -> !shouldStopUsing(stack)
        );
    }

    private boolean isDisabledByHolding() {
        return disableWhenHolding.get().contains(mc.player.getMainHandStack().getItem());
    }

    private boolean isAutoEatActive() {
        AutoEat autoEat = Modules.get().get(AutoEat.class);
        return autoEat != null && autoEat.isActive() && autoEat.eating;
    }

    private boolean isKillAuraSwapActive() {
        KillAura killAura = Modules.get().get(KillAura.class);
        return killAura != null && killAura.isActive() && killAura.swapped;
    }

    private boolean isExternalSwapPauseActive() {
        return (pauseWhenAutoEat.get() && isAutoEatActive()) || (pauseWhenKillAuraSwaps.get() && isKillAuraSwapActive());
    }

    private boolean isAttackPressed() {
        return isAttackKeyHeldOnBreakableBlock()
            || (mc.interactionManager != null && mc.interactionManager.isBreakingBlock())
            || isBaritoneProcessActive();
    }

    private boolean isAttackKeyHeldOnBreakableBlock() {
        if (!mc.options.attackKey.isPressed()) return false;
        if (mc.world == null) return false;
        if (!(mc.crosshairTarget instanceof BlockHitResult hit)) return false;

        BlockPos pos = hit.getBlockPos();
        BlockState blockState = mc.world.getBlockState(pos);
        return BlockUtils.canBreak(pos, blockState);
    }

    private boolean shouldDelayRestoreForExternalHotbarControl() {
        if (borrowSessions.isEmpty()) return false;

        if (isBaritoneProcessActive()) return true;
        return isExternalSwapPauseActive();
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

    public boolean shouldExposeInventoryToolsToBaritone() {
        return isActive() && toolSubmodule.get() && allowInventory.get();
    }

    public int getBestInventoryToolSlotForBaritone(BlockState blockState, boolean preferSilkTouch) {
        if (!shouldExposeInventoryToolsToBaritone()) return -1;
        if (mc.player == null || mc.world == null) return -1;

        EnchantPreference preference = preferSilkTouch ? EnchantPreference.SilkTouch : prefer.get();
        int bestSlot = -1;
        double bestScore = -1;

        for (int i = HOTBAR_START; i <= INVENTORY_END; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!isItemInList(stack)) continue;

            double score = getScore(
                stack,
                blockState,
                silkTouchForEnderChest.get(),
                fortuneForOres.get(),
                fortuneForCrops.get(),
                preference,
                s -> !shouldStopUsing(s)
            );

            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        return bestSlot;
    }

    private boolean showAutoToolSettings() {
        return !autoMendMenu.get();
    }

    @Override
    public boolean showModuleActiveSetting() {
        return showAutoToolSettings();
    }

    private void syncBackboneActivation() {
        if (syncingBackboneActivation) return;
        syncingBackboneActivation = true;

        try {
            boolean shouldBeActive = toolSubmodule.get() || autoMendSubmodule.isEnabled();
            if (shouldBeActive && !isActive()) enable();
            else if (!shouldBeActive && isActive()) disable();
        } finally {
            syncingBackboneActivation = false;
        }
    }

    public boolean isBorrowRestoreActive() {
        return toolSubmodule.get() && (pendingSwitch != null || !borrowSessions.isEmpty());
    }

    private boolean isBorrowingOffHandForAutoMend() {
        if (pendingSwitch != null && pendingSwitch.type == SwitchType.OffHand) return true;

        for (BorrowSession session : borrowSessions) {
            if (session.type == BorrowType.OffHand) return true;
        }

        return false;
    }

    private class AutoMendSubmodule {
        private final Setting<Boolean> enabled = sgAutoMend.add(new BoolSetting.Builder()
            .name("enabled")
            .description("Enables Auto-mend logic.")
            .defaultValue(false)
            .visible(autoMendMenu::get)
            .onChanged(value -> {
                resetState(value && mc.player != null && isMending(mc.player.getOffHandStack()));
                syncBackboneActivation();
            })
            .build()
        );

        private final Setting<List<Item>> mendBlacklist = sgAutoMend.add(new ItemListSetting.Builder()
            .name("blacklist")
            .description("Item blacklist.")
            .filter(item -> item.getComponents().get(DataComponentTypes.DAMAGE) != null)
            .visible(autoMendMenu::get)
            .build()
        );

        private final Setting<Boolean> mendForce = sgAutoMend.add(new BoolSetting.Builder()
            .name("force")
            .description("Replaces item in offhand even if there is some other non-repairable item.")
            .defaultValue(false)
            .visible(autoMendMenu::get)
            .build()
        );

        private final Setting<Boolean> mendForceOnce = sgAutoMend.add(new BoolSetting.Builder()
            .name("force-once")
            .description("Forces one repairable mending item into offhand, then waits for you to move it manually.")
            .defaultValue(true)
            .visible(autoMendMenu::get)
            .onChanged(value -> {
                if (value) mendForce.set(false);
            })
            .build()
        );

        private final Setting<Boolean> mendAutoAbort = sgAutoMend.add(new BoolSetting.Builder()
            .name("auto-abort")
            .description("Automatically aborts when the tracked offhand item is replaced by a non-repairable item or removed.")
            .defaultValue(true)
            .visible(autoMendMenu::get)
            .onChanged(value -> {
                if (value) mendForce.set(false);
            })
            .build()
        );

        private final Setting<Boolean> mendAutoDisable = sgAutoMend.add(new BoolSetting.Builder()
            .name("auto-disable")
            .description("Automatically disables Auto-mend when there are no more items to repair.")
            .defaultValue(true)
            .visible(autoMendMenu::get)
            .build()
        );

        private final Setting<Boolean> mendAllowBorrow = sgAutoMend.add(new BoolSetting.Builder()
            .name("allow-borrow")
            .description("Pauses Auto-mend while Auto-tool borrows off-hand tools.")
            .defaultValue(true)
            .visible(autoMendMenu::get)
            .build()
        );

        private final Setting<Integer> mendBorrowTimeout = sgAutoMend.add(new IntSetting.Builder()
            .name("auto-tool-borrow-timeout")
            .description("Ticks to keep Auto-mend paused after Auto-tool borrow activity.")
            .defaultValue(3)
            .range(0, 10)
            .sliderRange(0, 10)
            .visible(autoMendMenu::get)
            .build()
        );

        private boolean didMove;
        private boolean hasTrackedItem;
        private boolean abortPending;
        private int borrowTimeoutTicksLeft;

        void onActivate() {
            resetState(mc.player != null && isMending(mc.player.getOffHandStack()));
        }

        void onDeactivate() {
            resetState(false);
        }

        private void resetState(boolean trackedItem) {
            didMove = false;
            hasTrackedItem = trackedItem;
            abortPending = false;
            borrowTimeoutTicksLeft = 0;
        }

        void tick() {
            if (!enabled.get()) return;
            if (isAutoMendPaused()) return;

            if (mendForceOnce.get()) {
                ItemStack offHand = mc.player.getOffHandStack();

                if (shouldAbortTrackedItem(offHand)) {
                    if (abortPending) disable("Offhand item changed to non-repairable, disabling", false);
                    else abortPending = true;
                    return;
                }
                abortPending = false;

                if (isMending(offHand)) {
                    hasTrackedItem = true;
                    if (offHand.isDamaged()) return;
                }

                if (tryMoveRepairableToOffhand()) {
                    hasTrackedItem = true;
                    return;
                }

                if (mendAutoDisable.get()) disable("Repaired all items, disabling", false);
                return;
            }

            ItemStack offHand = mc.player.getOffHandStack();
            if (isOffhandReadyForRepair(offHand) || tryMoveRepairableToOffhand()) return;
            if (mendAutoDisable.get()) disable("Repaired all items, disabling", true);
        }

        private boolean isAutoMendPaused() {
            if (!mendAllowBorrow.get()) {
                borrowTimeoutTicksLeft = 0;
                return false;
            }

            boolean borrowingNow = isBorrowingOffHandForAutoMend();
            if (borrowingNow) borrowTimeoutTicksLeft = mendBorrowTimeout.get();
            else if (borrowTimeoutTicksLeft > 0) borrowTimeoutTicksLeft--;

            if (borrowingNow || borrowTimeoutTicksLeft > 0) {
                abortPending = false;
                return true;
            }

            return false;
        }

        private boolean shouldAbortTrackedItem(ItemStack offHand) {
            return mendAutoAbort.get() && hasTrackedItem && !isMending(offHand) && !isBorrowingOffHandForAutoMend();
        }

        private boolean isOffhandReadyForRepair(ItemStack offHand) {
            boolean forceEnabled = mendForce.get() && !mendForceOnce.get() && !mendAutoAbort.get();
            return !offHand.isEmpty() && (isMending(offHand) ? offHand.isDamaged() : !forceEnabled);
        }

        private int findRepairableSlot(int start, int end) {
            for (int i = start; i <= end; i++) {
                ItemStack itemStack = mc.player.getInventory().getStack(i);
                if (mendBlacklist.get().contains(itemStack.getItem())) continue;
                if (isMending(itemStack) && itemStack.isDamaged()) return i;
            }

            return -1;
        }

        private void disable(String message, boolean moveOffhandOut) {
            info(message);

            if (moveOffhandOut && didMove) {
                int emptySlot = findEmptyInventorySlot();
                if (emptySlot != -1) quickSwapPlainAndSync(SlotUtils.OFFHAND, emptySlot);
            }

            enabled.set(false);
        }

        private int findEmptyInventorySlot() {
            for (int i = INVENTORY_START; i <= INVENTORY_END; i++) {
                if (mc.player.getInventory().getStack(i).isEmpty()) return i;
            }
            return -1;
        }

        private boolean isMending(ItemStack itemStack) {
            return !itemStack.isEmpty() && Utils.hasEnchantments(itemStack, Enchantments.MENDING);
        }

        private boolean tryMoveRepairableToOffhand() {
            int slot = findRepairableSlot(INVENTORY_START, INVENTORY_END);
            if (slot == -1) slot = findRepairableSlot(HOTBAR_START, HOTBAR_END);
            if (slot == -1) return false;

            quickSwapPlainAndSync(SlotUtils.OFFHAND, slot);
            didMove = true;
            return true;
        }

        private boolean quickSwapPlainAndSync(int fromSlot, int toSlot) {
            return quickSwapAndSync(fromSlot, toSlot);
        }

        void toggleEnabled(boolean sendFeedback) {
            enabled.set(!enabled.get());

            if (sendFeedback && Config.get().chatFeedback.get()) {
                info("Auto-mend %s(default).", enabled.get() ? "(highlight)enabled" : "(highlight)disabled");
            }
        }

        boolean isEnabled() {
            return enabled.get();
        }
    }

    @Override
    public String getInfoString() {
        return toolSubmodule.get() ? prefer.get().name() : null;
    }

    @Override
    public String getInfoString(String hudTitle) {
        return title.equals(hudTitle) ? getInfoString() : null;
    }

    @Override
    public String getHudTitle() {
        List<String> titles = getHudTitles();
        if (titles.isEmpty()) return null;
        return titles.getFirst();
    }

    @Override
    public List<String> getHudTitles() {
        List<String> titles = new ArrayList<>(2);
        if (toolSubmodule.get()) titles.add(title);
        if (autoMendSubmodule.isEnabled()) titles.add("Auto-mend");
        return titles;
    }

    public enum EnchantPreference {
        None,
        Fortune,
        SilkTouch
    }

    public enum ListMode {
        Whitelist,
        Blacklist
    }

    private enum ToolOriginPriority {
        None,
        Hotbar,
        Inventory,
        OffHand,
        InventoryRow1,
        InventoryRow2,
        InventoryRow3
    }

    private enum ToolOrigin {
        Hotbar,
        Inventory,
        OffHand,
        InventoryRow1,
        InventoryRow2,
        InventoryRow3
    }

    private enum SwitchType {
        Hotbar,
        Inventory,
        OffHand
    }

    private enum BorrowType {
        Inventory,
        OffHand
    }

    private record Candidate(int slot, SwitchType type, double score, StackSignature signature) {}
    private record BorrowSession(BorrowType type, int sourceSlot, int hotbarSlot, StackSignature borrowedSignature, boolean restoreOnRelease) {}
    private record StackSignature(ItemStack snapshot, boolean ignoreDamage) {}

    private BorrowSession findBorrowSession(BorrowType type, int sourceSlot, StackSignature sourceSignature) {
        for (int i = borrowSessions.size() - 1; i >= 0; i--) {
            BorrowSession session = borrowSessions.get(i);
            if (session.type == type
                && session.sourceSlot == sourceSlot
                && session.borrowedSignature != null
                && signatureMatches(session.borrowedSignature.snapshot, sourceSignature)
                && resolveBorrowSessionHotbarSlot(session) != -1) {
                return session;
            }
        }

        return null;
    }

    private BorrowSession getLatestBorrowSession() {
        if (borrowSessions.isEmpty()) return null;
        return borrowSessions.getLast();
    }

    private boolean hasBorrowSessionOnHotbar(int hotbarSlot) {
        for (BorrowSession session : borrowSessions) {
            if (resolveBorrowSessionHotbarSlot(session) == hotbarSlot) return true;
        }

        return false;
    }

    private int resolveBorrowSessionHotbarSlot(BorrowSession session) {
        if (session == null || session.borrowedSignature == null) return -1;
        if (hotbarSignatureAt(session.hotbarSlot, session.borrowedSignature)) return session.hotbarSlot;
        return findHotbarSlotForSignature(session.borrowedSignature);
    }

    private boolean isBorrowSessionRestored(BorrowSession session) {
        if (session == null || session.borrowedSignature == null) return false;

        return switch (session.type) {
            case Inventory -> inventorySignatureAt(session.sourceSlot, session.borrowedSignature);
            case OffHand -> signatureMatches(mc.player.getOffHandStack(), session.borrowedSignature);
        };
    }

    private boolean isPendingSwitchStillValid(PendingSwitch switchState) {
        if (switchState == null || switchState.signature == null) return false;

        return switch (switchState.type) {
            case Hotbar -> hotbarSignatureAt(switchState.slot, switchState.signature);
            case Inventory -> inventorySignatureAt(switchState.slot, switchState.signature);
            case OffHand -> signatureMatches(mc.player.getOffHandStack(), switchState.signature);
        };
    }

    private static class PendingSwitch {
        private SwitchType type;
        private int slot;
        private StackSignature signature;
        private int delayTicks;

        private PendingSwitch(SwitchType type, int slot, StackSignature signature, int delayTicks) {
            this.type = type;
            this.slot = slot;
            this.signature = signature;
            this.delayTicks = delayTicks;
        }
    }

    public static double getScore(ItemStack itemStack, BlockState state, boolean silkTouchEnderChest, boolean fortuneOre, boolean fortuneCrop, EnchantPreference enchantPreference, Predicate<ItemStack> good) {
        if (!good.test(itemStack) || !isTool(itemStack)) return -1;

        if (!itemStack.isSuitableFor(state)
            && !(itemStack.isIn(ItemTags.SWORDS) && (state.getBlock() instanceof BambooBlock || state.getBlock() instanceof BambooShootBlock))
            && !(itemStack.getItem() instanceof ShearsItem && (state.getBlock() instanceof LeavesBlock || state.isIn(BlockTags.WOOL)))) {
            return -1;
        }

        if (silkTouchEnderChest && state.getBlock() == Blocks.ENDER_CHEST && !Utils.hasEnchantments(itemStack, Enchantments.SILK_TOUCH)) {
            return -1;
        }

        if (fortuneOre
            && state.getBlock() != Blocks.ANCIENT_DEBRIS
            && Xray.ORES.contains(state.getBlock())
            && !Utils.hasEnchantments(itemStack, Enchantments.FORTUNE)) {
            return -1;
        }

        if (fortuneCrop && state.getBlock() instanceof CropBlock && !Utils.hasEnchantments(itemStack, Enchantments.FORTUNE)) {
            return -1;
        }

        double score = 0;

        score += itemStack.getMiningSpeedMultiplier(state) * 1000;
        score += Utils.getEnchantmentLevel(itemStack, Enchantments.UNBREAKING);
        score += Utils.getEnchantmentLevel(itemStack, Enchantments.EFFICIENCY);
        score += Utils.getEnchantmentLevel(itemStack, Enchantments.MENDING);

        if (enchantPreference == EnchantPreference.Fortune) score += Utils.getEnchantmentLevel(itemStack, Enchantments.FORTUNE);
        if (enchantPreference == EnchantPreference.SilkTouch) score += Utils.getEnchantmentLevel(itemStack, Enchantments.SILK_TOUCH);

        if (itemStack.isIn(ItemTags.SWORDS) && (state.getBlock() instanceof BambooBlock || state.getBlock() instanceof BambooShootBlock)) {
            score += 9000;
            var toolComponent = itemStack.get(DataComponentTypes.TOOL);
            if (toolComponent != null) score += toolComponent.getSpeed(state) * 1000;
        }

        return score;
    }

    public static boolean isTool(Item item) {
        return isTool(item.getDefaultStack());
    }

    public static boolean isTool(ItemStack itemStack) {
        return itemStack.isIn(ItemTags.AXES)
            || itemStack.isIn(ItemTags.HOES)
            || itemStack.isIn(ItemTags.PICKAXES)
            || itemStack.isIn(ItemTags.SHOVELS)
            || itemStack.getItem() instanceof ShearsItem;
    }
}
