/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potions;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.EnchantmentTags;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.Identifier;

import java.util.*;

public class CleanFishingChest extends Module {
    private final SettingGroup sgFish = settings.getDefaultGroup();
    private final SettingGroup sgJunk = settings.createGroup("Junk");
    private final SettingGroup sgTreasure = settings.createGroup("Treasure");
    private final SettingGroup sgBookEnchants = settings.createGroup("Book Enchants");

    // Fish
    private final Setting<Boolean> cod = addToggle(sgFish, "cod");
    private final Setting<Boolean> salmon = addToggle(sgFish, "salmon");
    private final Setting<Boolean> tropicalFish = addToggle(sgFish, "tropical-fish");
    private final Setting<Boolean> pufferfish = addToggle(sgFish, "pufferfish");

    // Junk
    private final Setting<Boolean> bowl = addToggle(sgJunk, "bowl");
    private final Setting<Boolean> fishingRod = addToggle(sgJunk, "fishing-rod");
    private final Setting<Boolean> spareGodRods = sgJunk.add(new BoolSetting.Builder()
        .name("spare-god-rods")
        .description("Keeps fishing rods with Mending, Unbreaking III, Lure III, and Luck of the Sea III.")
        .defaultValue(true)
        .visible(fishingRod::get)
        .build()
    );
    private final Setting<Boolean> leather = addToggle(sgJunk, "leather");
    private final Setting<Boolean> leatherBoots = addToggle(sgJunk, "leather-boots");
    private final Setting<Boolean> rottenFlesh = addToggle(sgJunk, "rotten-flesh");
    private final Setting<Boolean> stick = addToggle(sgJunk, "stick");
    private final Setting<Boolean> string = addToggle(sgJunk, "string");
    private final Setting<Boolean> waterBottle = addToggle(sgJunk, "water-bottle");
    private final Setting<Boolean> bone = addToggle(sgJunk, "bone");
    private final Setting<Boolean> inkSac = addToggle(sgJunk, "ink-sac");
    private final Setting<Boolean> tripwireHook = addToggle(sgJunk, "tripwire-hook");

    // Treasure
    private final Setting<Boolean> bow = addToggle(sgTreasure, "bow");
    private final Setting<Boolean> enchantedBook = addToggle(sgTreasure, "enchanted-book");
    private final Setting<Boolean> spareGoodEnchantedBooks = sgTreasure.add(new BoolSetting.Builder()
        .name("spare-good-enchanted-books")
        .description("Keeps enchanted books that match enabled enchant filters.")
        .defaultValue(true)
        .visible(enchantedBook::get)
        .build()
    );
    private final Setting<Boolean> spareAnyMaxLevelBook = sgTreasure.add(new BoolSetting.Builder()
        .name("spare-book-any-max-level")
        .description("Keeps enchanted books with any enchantment at or above that enchantment's fishing max level.")
        .defaultValue(false)
        .visible(() -> enchantedBook.get() && spareGoodEnchantedBooks.get())
        .build()
    );
    private final Setting<Boolean> nameTag = addToggle(sgTreasure, "name-tag");
    private final Setting<Boolean> nautilusShell = addToggle(sgTreasure, "nautilus-shell");
    private final Setting<Boolean> saddle = addToggle(sgTreasure, "saddle");

    private static final int FISHING_ENCHANT_LEVEL = 30;
    private static final int FISHING_MIN_ROLL_LEVEL = Math.round((FISHING_ENCHANT_LEVEL + 1) * 0.85f);
    private static final int FISHING_MAX_ROLL_LEVEL = Math.round((FISHING_ENCHANT_LEVEL + 1) * 1.15f);
    private final Map<Identifier, Setting<Boolean>> spareBookEnchants = new HashMap<>();
    private final Map<String, Boolean> persistedBookEnchantToggles = new HashMap<>();
    private final Map<Identifier, Integer> fishingLevelCaps = new HashMap<>();
    private boolean bookEnchantSettingsInitialized;

    public CleanFishingChest() {
        super(Categories.Player, "clean-fishing-chest", "Drops selected fishing-loot stacks from opened chests.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.currentScreen == null) return;
        ensureBookEnchantSettingsInitialized();

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (!(handler instanceof GenericContainerScreenHandler container)) return;

        if (!handler.getCursorStack().isEmpty()) return;

        int chestSlots = container.getRows() * 9;
        for (int slotId = 0; slotId < chestSlots; slotId++) {
            ItemStack stack = handler.getSlot(slotId).getStack();
            if (stack.isEmpty()) continue;

            if (shouldDrop(stack)) {
                InvUtils.drop().slotId(slotId);
                break;
            }
        }
    }

    private Setting<Boolean> addToggle(SettingGroup group, String name) {
        return group.add(new BoolSetting.Builder()
            .name(name)
            .description("Drops " + name.replace('-', ' ') + " when found in an opened chest.")
            .defaultValue(false)
            .build()
        );
    }

    private boolean shouldDrop(ItemStack stack) {
        Item item = stack.getItem();

        if (item == Items.COD) return cod.get();
        if (item == Items.SALMON) return salmon.get();
        if (item == Items.TROPICAL_FISH) return tropicalFish.get();
        if (item == Items.PUFFERFISH) return pufferfish.get();

        if (item == Items.BOWL) return bowl.get();
        if (item == Items.FISHING_ROD) return fishingRod.get() && (!spareGodRods.get() || !isGodFishingRod(stack));
        if (item == Items.LEATHER) return leather.get();
        if (item == Items.LEATHER_BOOTS) return leatherBoots.get();
        if (item == Items.ROTTEN_FLESH) return rottenFlesh.get();
        if (item == Items.STICK) return stick.get();
        if (item == Items.STRING) return string.get();
        if (item == Items.BONE) return bone.get();
        if (item == Items.INK_SAC) return inkSac.get();
        if (item == Items.TRIPWIRE_HOOK) return tripwireHook.get();

        if (item == Items.BOW) return bow.get();
        if (item == Items.ENCHANTED_BOOK) return enchantedBook.get() && (!spareGoodEnchantedBooks.get() || !isGoodFishingBook(stack));
        if (item == Items.NAME_TAG) return nameTag.get();
        if (item == Items.NAUTILUS_SHELL) return nautilusShell.get();
        if (item == Items.SADDLE) return saddle.get();

        return waterBottle.get() && isWaterBottle(stack);
    }

    private boolean isWaterBottle(ItemStack stack) {
        if (stack.getItem() != Items.POTION) return false;

        PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
        return contents != null && contents.potion().isPresent() && contents.potion().get().value() == Potions.WATER.value();
    }

    private boolean isGodFishingRod(ItemStack stack) {
        return Utils.getEnchantmentLevel(stack, Enchantments.MENDING) >= 1
            && Utils.getEnchantmentLevel(stack, Enchantments.UNBREAKING) >= 3
            && Utils.getEnchantmentLevel(stack, Enchantments.LURE) >= 3
            && Utils.getEnchantmentLevel(stack, Enchantments.LUCK_OF_THE_SEA) >= 3;
    }

    private boolean isGoodFishingBook(ItemStack stack) {
        ensureBookEnchantSettingsInitialized();
        if (spareAnyMaxLevelBook.get() && hasAnyFishingMaxLevelEnchantment(stack)) return true;

        Object2IntMap<RegistryEntry<Enchantment>> enchantments = new Object2IntArrayMap<>();
        Utils.getEnchantments(stack, enchantments);

        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : Object2IntMaps.fastIterable(enchantments)) {
            Optional<RegistryKey<Enchantment>> key = entry.getKey().getKey();
            if (key.isEmpty()) continue;

            Identifier id = key.get().getValue();
            Setting<Boolean> setting = spareBookEnchants.get(id);
            int levelCap = fishingLevelCaps.getOrDefault(id, entry.getKey().value().getMaxLevel());
            if (setting != null && setting.get() && entry.getIntValue() >= levelCap) return true;
        }

        return false;
    }

    private boolean hasAnyFishingMaxLevelEnchantment(ItemStack stack) {
        Object2IntMap<RegistryEntry<Enchantment>> enchantments = new Object2IntArrayMap<>();
        Utils.getEnchantments(stack, enchantments);

        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : Object2IntMaps.fastIterable(enchantments)) {
            Optional<RegistryKey<Enchantment>> key = entry.getKey().getKey();
            if (key.isEmpty()) continue;

            int levelCap = fishingLevelCaps.getOrDefault(key.get().getValue(), entry.getKey().value().getMaxLevel());
            if (entry.getIntValue() >= levelCap) return true;
        }

        return false;
    }

    private String toSettingToken(Identifier id) {
        return id.getNamespace().replace('_', '-') + "-" + id.getPath().replace('_', '-');
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = super.toTag();
        if (tag == null) return null;

        if (!spareBookEnchants.isEmpty()) {
            for (Setting<Boolean> setting : spareBookEnchants.values()) {
                persistedBookEnchantToggles.put(setting.name, setting.get());
            }
        }

        if (!persistedBookEnchantToggles.isEmpty()) {
            NbtCompound bookTag = new NbtCompound();
            for (Map.Entry<String, Boolean> entry : persistedBookEnchantToggles.entrySet()) {
                bookTag.putBoolean(entry.getKey(), entry.getValue());
            }
            tag.put("bookEnchantToggles", bookTag);
        }

        return tag;
    }

    @Override
    public Module fromTag(NbtCompound tag) {
        super.fromTag(tag);

        loadPersistedBookEnchantToggles(tag.getCompoundOrEmpty("bookEnchantToggles"));

        if (persistedBookEnchantToggles.isEmpty()) {
            loadLegacyBookEnchantToggles(tag.getCompoundOrEmpty("settings"));
        }

        applyPersistedBookEnchantToggles();
        return this;
    }

    private void loadPersistedBookEnchantToggles(NbtCompound tag) {
        if (tag == null) return;

        for (String key : tag.getKeys()) {
            persistedBookEnchantToggles.put(key, tag.getBoolean(key, false));
        }
    }

    private void loadLegacyBookEnchantToggles(NbtCompound settingsTag) {
        if (settingsTag == null) return;

        NbtList groupsTag = settingsTag.getListOrEmpty("groups");
        for (NbtElement groupElement : groupsTag) {
            if (!(groupElement instanceof NbtCompound groupTag)) continue;
            if (!"Book Enchants".equals(groupTag.getString("name", ""))) continue;

            NbtList settingTags = groupTag.getListOrEmpty("settings");
            for (NbtElement settingElement : settingTags) {
                if (!(settingElement instanceof NbtCompound settingTag)) continue;

                String settingName = settingTag.getString("name", "");
                if (!settingName.startsWith("spare-book-")) continue;
                persistedBookEnchantToggles.put(settingName, settingTag.getBoolean("value", false));
            }
        }
    }

    private void applyPersistedBookEnchantToggles() {
        if (spareBookEnchants.isEmpty()) return;

        for (Setting<Boolean> setting : spareBookEnchants.values()) {
            Boolean value = persistedBookEnchantToggles.get(setting.name);
            if (value != null) setting.set(value);
        }
    }

    private void ensureBookEnchantSettingsInitialized() {
        if (bookEnchantSettingsInitialized) return;
        if (mc.getNetworkHandler() == null) return;

        Optional<Registry<Enchantment>> registryOptional = mc.getNetworkHandler().getRegistryManager().getOptional(RegistryKeys.ENCHANTMENT);
        if (registryOptional.isEmpty()) return;

        List<RegistryEntry.Reference<Enchantment>> enchantments;
        try {
            enchantments = registryOptional.get().streamEntries()
                .filter(entry -> entry.isIn(EnchantmentTags.ON_RANDOM_LOOT))
                .sorted(Comparator.comparing(entry -> entry.registryKey().getValue().toString()))
                .toList();
        } catch (IllegalStateException ignored) {
            // Tag data may not be bound during early startup.
            return;
        }

        for (RegistryEntry.Reference<Enchantment> enchantment : enchantments) {
            Identifier id = enchantment.registryKey().getValue();
            int levelCap = getFishingLevelCap(enchantment.value());
            fishingLevelCaps.put(id, levelCap);
            String settingName = "spare-book-" + toSettingToken(id);

            Setting<Boolean> setting = sgBookEnchants.add(new BoolSetting.Builder()
                .name(settingName)
                .description("Keeps enchanted books with " + id + " at fishing max level (" + levelCap + ") or higher.")
                .defaultValue(persistedBookEnchantToggles.getOrDefault(settingName, false))
                .onChanged(value -> persistedBookEnchantToggles.put(settingName, value))
                .visible(() -> enchantedBook.get() && spareGoodEnchantedBooks.get())
                .build()
            );

            spareBookEnchants.put(id, setting);
        }

        bookEnchantSettingsInitialized = true;
        applyPersistedBookEnchantToggles();
    }

    private int getFishingLevelCap(Enchantment enchantment) {
        int min = enchantment.getMinLevel();
        int max = enchantment.getMaxLevel();

        for (int level = max; level >= min; level--) {
            if (enchantment.getMinPower(level) <= FISHING_MAX_ROLL_LEVEL
                && enchantment.getMaxPower(level) >= FISHING_MIN_ROLL_LEVEL) {
                return level;
            }
        }

        return min;
    }
}
