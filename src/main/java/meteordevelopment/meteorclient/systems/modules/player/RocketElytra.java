package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.entity.player.InteractItemEvent;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.entity.player.DoAttackEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;

public class RocketElytra extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgInventory = settings.createGroup("Inventory");



    private final Setting<Boolean> useRocket = sgGeneral.add(new BoolSetting.Builder()
        .name("use-rocket-on-trigger")
        .description("Automatically uses a firework rocket after equipping elytra.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> oneLClickUnequip = sgGeneral.add(new BoolSetting.Builder()
        .name("single-left-click-unequip")
        .description("Unequips elytra with a single left-click.")
        .defaultValue(false)
        .visible(this::showSingleLeftClickSetting)
        .build()
    );

    private final Setting<Boolean> doubleLClickUnequip = sgGeneral.add(new BoolSetting.Builder()
        .name("double-left-click-unequip")
        .description("Unequips elytra with a fast double left-click.")
        .defaultValue(false)
        .onChanged(enabled -> {
            if (enabled) oneLClickUnequip.set(false);
        })
        .build()
    );

    private final Setting<Integer> doubleClickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("double-click-delay")
        .description("Maximum delay in milliseconds between clicks to register as double-click.")
        .defaultValue(300)
        .range(100, 1000)
        .sliderRange(100, 1000)
        .visible(() -> doubleLClickUnequip.get() && !oneLClickUnequip.get())
        .build()
    );

    private final Setting<Boolean> oneLClickEquip = sgGeneral.add(new BoolSetting.Builder()
        .name("click-equip")
        .description("Equips elytra with a single left-click.")
        .defaultValue(false)
        .visible(this::showSingleClickEquipSetting)
        .build()
    );

    private final Setting<Integer> singleClickDebounce = sgGeneral.add(new IntSetting.Builder()
        .name("single-click-debounce")
        .description("Minimum delay in milliseconds between single-click equip/unequip actions.")
        .defaultValue(175)
        .range(0, 1000)
        .sliderRange(0, 500)
        .visible(() -> oneLClickUnequip.get() || oneLClickEquip.get())
        .build()
    );

    private final Setting<Boolean> doubleLClickEquip = sgGeneral.add(new BoolSetting.Builder()
        .name("double-click-equip")
        .description("Equips elytra with a fast double left-click.")
        .defaultValue(false)
        .onChanged(enabled -> {
            if (enabled) oneLClickEquip.set(false);
        })
        .build()
    );

    private final Setting<Integer> equipDoubleClickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("equip-double-click-delay")
        .description("Maximum delay in milliseconds between clicks to register as double-click equip.")
        .defaultValue(300)
        .range(100, 1000)
        .sliderRange(100, 1000)
        .visible(() -> doubleLClickEquip.get() && !oneLClickEquip.get())
        .build()
    );

    private final Setting<Boolean> resetOnLanding = sgGeneral.add(new BoolSetting.Builder()
        .name("reset-on-landing")
        .description("Unequips elytra when you land.")
        .defaultValue(false)
        .build()
    );

    // Inventory settings
    private final Setting<Boolean> autoReplace = sgInventory.add(new BoolSetting.Builder()
        .name("auto-replace")
        .description("Automatically replaces broken elytra with a new one.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> minDurability = sgInventory.add(new IntSetting.Builder()
        .name("min-durability")
        .description("The durability threshold your elytra will be replaced at.")
        .defaultValue(2)
        .range(1, Items.ELYTRA.getComponents().getOrDefault(DataComponentTypes.MAX_DAMAGE, 432) - 1)
        .sliderRange(1, Items.ELYTRA.getComponents().getOrDefault(DataComponentTypes.MAX_DAMAGE, 432) - 1)
        .visible(autoReplace::get)
        .build()
    );

    // Double-click tracking
    private long lastUnequipClickTime = 0;
    private long lastEquipClickTime = 0;
    private long lastSingleClickActionTime = 0;
    private boolean internalRocketUse = false;
    private boolean waitingUseRelease = false;
    private boolean wasOnGround = true;
    private boolean jumpedSinceLastLandingReset = false;

    public RocketElytra() {
        super(Categories.Player, "rocket-elytra", "Automatically equips elytra when right-clicking a rocket while in the air, then uses a rocket.");
    }

    @Override
    public void onActivate() {
        if (mc.player != null) {
            wasOnGround = mc.player.isOnGround();
            jumpedSinceLastLandingReset = false;
        }
        lastSingleClickActionTime = 0;
    }

    @EventHandler
    private void onAttack(DoAttackEvent event) {
        if (mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem() == Items.ELYTRA) {
            if (!isHoldingRocketInMainHand()) {
                lastUnequipClickTime = 0;
                return;
            }
            handleUnequipClick();
            return;
        }

        if (!canTriggerLeftClickEquip()) {
            lastEquipClickTime = 0;
            return;
        }

        handleEquipClick();
    }

    private void handleUnequipClick() {
        // Single left-click unequip
        if (oneLClickUnequip.get()) {
            if (isSingleClickDebounced()) return;
            unequipElytra();
            if (mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) {
                markSingleClickAction();
            }
            return;
        }

        // Double left-click unequip
        if (!doubleLClickUnequip.get()) return;

        long currentTime = System.currentTimeMillis();
        long timeSinceLastClick = currentTime - lastUnequipClickTime;

        // Check if this is the second click within the delay window
        if (timeSinceLastClick <= doubleClickDelay.get() && timeSinceLastClick > 0) {
            // Double-click detected, unequip elytra
            unequipElytra();
            lastUnequipClickTime = 0; // Reset
        } else {
            // This is either the first click or too late for double-click
            lastUnequipClickTime = currentTime;
        }
    }

    private void handleEquipClick() {
        // Single left-click equip
        if (oneLClickEquip.get()) {
            if (isSingleClickDebounced()) return;
            if (equipElytra()) {
                markSingleClickAction();
                tryStartElytraFlyingIfAirborne();
            }
            return;
        }

        // Double left-click equip
        if (!doubleLClickEquip.get()) return;

        long currentTime = System.currentTimeMillis();
        long timeSinceLastClick = currentTime - lastEquipClickTime;

        // Check if this is the second click within the delay window
        if (timeSinceLastClick <= equipDoubleClickDelay.get() && timeSinceLastClick > 0) {
            // Double-click detected, equip elytra (no rocket trigger here)
            if (equipElytra()) tryStartElytraFlyingIfAirborne();
            lastEquipClickTime = 0; // Reset
        } else {
            // This is either the first click or too late for double-click
            lastEquipClickTime = currentTime;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        boolean onGround = mc.player.isOnGround();

        // Arm landing reset after leaving the ground.
        if (!jumpedSinceLastLandingReset && wasOnGround && !onGround) {
            jumpedSinceLastLandingReset = true;
        }

        // Check if auto-replace is enabled and if we should replace the elytra
        if (autoReplace.get()) {
            ItemStack chestStack = mc.player.getEquippedStack(EquipmentSlot.CHEST);

            if (chestStack.getItem() == Items.ELYTRA) {
                int durability = chestStack.getMaxDamage() - chestStack.getDamage();
                if (durability <= minDurability.get()) {
                    replaceElytra();
                }
            }
        }

        if (resetOnLanding.get() && jumpedSinceLastLandingReset && !wasOnGround && onGround) {
            unequipElytra();
            jumpedSinceLastLandingReset = false;
        }

        if (doubleLClickUnequip.get() && oneLClickUnequip.get()) oneLClickUnequip.set(false);
        if (doubleLClickEquip.get() && oneLClickEquip.get()) oneLClickEquip.set(false);
        if (!mc.options.useKey.isPressed()) waitingUseRelease = false;
        wasOnGround = onGround;
    }

    @EventHandler
    private void onInteractItem(InteractItemEvent event) {
        if (tryTriggerRocketElytra(event.hand)) event.toReturn = ActionResult.SUCCESS;
    }

    @EventHandler
    private void onInteractBlock(InteractBlockEvent event) {
        if (tryTriggerRocketElytra(event.hand)) event.cancel();
    }

    private boolean showSingleLeftClickSetting() {
        return !doubleLClickUnequip.get();
    }

    private boolean showSingleClickEquipSetting() {
        return !doubleLClickEquip.get();
    }

    private void tryStartElytraFlyingIfAirborne() {
        if (mc.player.isOnGround() || mc.player.isGliding()) return;
        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
    }

    private boolean canTriggerLeftClickEquip() {
        if (!isHoldingRocketInMainHand()) return false;
        return mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.MISS;
    }

    private boolean isHoldingRocketInMainHand() {
        return mc.player.getMainHandStack().getItem() == Items.FIREWORK_ROCKET;
    }

    private boolean isSingleClickDebounced() {
        int debounceMs = singleClickDebounce.get();
        if (debounceMs <= 0) return false;
        return System.currentTimeMillis() - lastSingleClickActionTime < debounceMs;
    }

    private void markSingleClickAction() {
        lastSingleClickActionTime = System.currentTimeMillis();
    }

    private boolean tryTriggerRocketElytra(Hand hand) {
        if (internalRocketUse) return false;
        if (waitingUseRelease) return false;

        // Only trigger on main hand interaction.
        if (hand != Hand.MAIN_HAND) return false;

        // Check if the item being used is a firework rocket.
        if (mc.player.getMainHandStack().getItem() != Items.FIREWORK_ROCKET) return false;

        // Check if player is in the air and not already gliding.
        if (mc.player.isOnGround()) return false;
        if (mc.player.isGliding()) return false;

        // Only consume trigger if we have elytra to equip.
        if (!equipElytra()) return false;

        // Trigger elytra takeoff.
        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        waitingUseRelease = true;

        // Use exactly one rocket immediately after triggering elytra mode.
        if (useRocket.get()) {
            internalRocketUse = true;
            try {
                useRocket();
            } finally {
                internalRocketUse = false;
            }
        }

        return true;
    }

    /**
     * Finds and equips an elytra from the inventory with sufficient durability
     * @return true if elytra was equipped, false otherwise
     */
    private boolean equipElytra() {
        // Check if elytra is already equipped with sufficient durability
        ItemStack chestStack = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (chestStack.getItem() == Items.ELYTRA) {
            int durability = chestStack.getMaxDamage() - chestStack.getDamage();
            if (durability > 0) {
                return true;
            }
        }

        // Find elytra in inventory with sufficient durability
        FindItemResult elytra = InvUtils.find(itemStack -> 
            itemStack.getItem() == Items.ELYTRA && 
            (itemStack.getMaxDamage() - itemStack.getDamage()) > 0
        );

        if (elytra.found()) {
            // Move elytra to chest slot (armor slot 2)
            InvUtils.move().from(elytra.slot()).toArmor(2);
            return true;
        }

        return false;
    }

    /**
     * Replaces the current elytra with a new one if available
     */
    private void replaceElytra() {
        // Find a better elytra in inventory
        FindItemResult newElytra = InvUtils.find(itemStack ->
            itemStack.getItem() == Items.ELYTRA &&
            (itemStack.getMaxDamage() - itemStack.getDamage()) > minDurability.get()
        );

        if (newElytra.found()) {
            InvUtils.move().from(newElytra.slot()).toArmor(2);
        }
    }

    /**
     * Unequips the elytra by swapping with best chestplate, or unloading to empty slots
     */
    private void unequipElytra() {
        ItemStack chestStack = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (chestStack.getItem() != Items.ELYTRA) return;

        // Try to find best chestplate andswap it with elytra
        FindItemResult chestplate = InvUtils.find(itemStack ->
            itemStack.getItem() != Items.ELYTRA && itemStack.isIn(ItemTags.CHEST_ARMOR)
        );

        if (chestplate.found()) {
            // Swap chestplate to chest armor slot (this moves elytra to chestplate's location)
            InvUtils.move().from(chestplate.slot()).toArmor(2);
            return;
        }

        // No chestplate found - unload elytra to empty slots (inventory -> hotbar -> offhand)
        // Try empty inventory slot (9-35)
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                InvUtils.move().fromArmor(2).to(i);
                return;
            }
        }

        // Try empty hotbar slot (0-8)
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                InvUtils.move().fromArmor(2).toHotbar(i);
                return;
            }
        }

        // Try empty offhand
        if (mc.player.getOffHandStack().isEmpty()) {
            InvUtils.move().fromArmor(2).toOffhand();
        }
    }

    /**
     * Uses a firework rocket if it's in main hand or offhand
     */
    private void useRocket() {
        // Check main hand
        if (mc.player.getMainHandStack().getItem() == Items.FIREWORK_ROCKET) {
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);
            return;
        }

        // Check off hand
        if (mc.player.getOffHandStack().getItem() == Items.FIREWORK_ROCKET) {
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
            mc.player.swingHand(Hand.OFF_HAND);
        }
    }
}
