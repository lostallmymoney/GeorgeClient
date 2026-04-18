/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.meteor.ActiveModulesChangedEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.ModuleBindChangedEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.pathing.BobbyUtils;
import meteordevelopment.meteorclient.pathing.ChatPlusUtils;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.combat.*;
import meteordevelopment.meteorclient.systems.modules.misc.*;
import meteordevelopment.meteorclient.systems.modules.misc.swarm.Swarm;
import meteordevelopment.meteorclient.systems.modules.movement.*;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.systems.modules.movement.speed.Speed;
import meteordevelopment.meteorclient.systems.modules.player.*;
import meteordevelopment.meteorclient.systems.modules.render.*;
import meteordevelopment.meteorclient.systems.modules.render.blockesp.BlockESP;
import meteordevelopment.meteorclient.systems.modules.render.marker.Marker;
import meteordevelopment.meteorclient.systems.modules.world.*;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.ValueComparableMap;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.network.ClientCommandSource;
import net.minecraft.command.CommandSource;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Pair;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class Modules extends System<Modules> {
    private static final List<Category> CATEGORIES = new ArrayList<>();
    private static final Field COMMAND_NODE_CHILDREN_FIELD = getCommandNodeField("children");
    private static final Field COMMAND_NODE_LITERALS_FIELD = getCommandNodeField("literals");
    private static final Field COMMAND_NODE_ARGUMENTS_FIELD = getCommandNodeField("arguments");

    private final Map<Class<? extends Module>, Module> moduleInstances = new Reference2ReferenceOpenHashMap<>();
    private final Map<Category, List<Module>> groups = new Reference2ReferenceOpenHashMap<>();

    private final List<Module> active = new ArrayList<>();
    private final Map<String, CommandNode<CommandSource>> registeredDotAliasNodes = new LinkedHashMap<>();
    private final Set<String> registeredSlashAliasNodes = new LinkedHashSet<>();
    private Module moduleToBind;
    private boolean awaitingKeyRelease = false;

    public Modules() {
        super("modules");
    }

    public static Modules get() {
        return Systems.get(Modules.class);
    }

    @Override
    public void init() {
        initCombat();
        initPlayer();
        initMovement();
        initRender();
        initWorld();
        initMisc();
    }

    @Override
    public void load(File folder) {
        for (Module module : getAll()) {
            for (SettingGroup group : module.settings) {
                for (Setting<?> setting : group) setting.reset();
            }
        }

        super.load(folder);
    }

    public void sortModules() {
        for (List<Module> modules : groups.values()) {
            modules.sort(Comparator.comparing(o -> o.title));
        }
    }

    public static void registerCategory(Category category) {
        if (!Categories.REGISTERING) throw new RuntimeException("Modules.registerCategory - Cannot register category outside of onRegisterCategories callback.");

        CATEGORIES.add(category);
    }

    public static Iterable<Category> loopCategories() {
        return CATEGORIES;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public <T extends Module> T get(Class<T> klass) {
        return (T) moduleInstances.get(klass);
    }

    public <T extends Module> Optional<T> getOptional(Class<T> klass) {
        return Optional.ofNullable(get(klass));
    }

    @Nullable
    public Module get(String name) {
        for (Module module : moduleInstances.values()) {
            if (module.name.equalsIgnoreCase(name)) return module;
        }

        return null;
    }

    public boolean isActive(Class<? extends Module> klass) {
        Module module = get(klass);
        return module != null && module.isActive();
    }

    public List<Module> getGroup(Category category) {
        return groups.computeIfAbsent(category, category1 -> new ArrayList<>());
    }

    public Collection<Module> getAll() {
        return moduleInstances.values();
    }


    public int getCount() {
        return moduleInstances.size();
    }

    public List<Module> getActive() {
        return active;
    }

    public List<Pair<Module, String>> searchTitles(String text) {
        Map<Pair<Module, String>, Integer> modules = new HashMap<>();

        for (Module module : this.moduleInstances.values()) {
            String title = module.title;
            int score = Utils.searchLevenshteinDefault(title, text, false);

            if (Config.get().moduleAliases.get()) {
                for (String alias : module.aliases) {
                    int aliasScore = Utils.searchLevenshteinDefault(alias, text, false);
                    if (aliasScore < score) {
                        title = module.title + " (" + alias + ")";
                        score = aliasScore;
                    }
                }
            }

            modules.put(new Pair<>(module, title), score);
        }

        List<Pair<Module, String>> l = new ArrayList<>(modules.keySet());
        l.sort(Comparator.comparingInt(modules::get));

        return l;
    }

    public Set<Module> searchSettingTitles(String text) {
        Map<Module, Integer> modules = new ValueComparableMap<>(Comparator.naturalOrder());

        for (Module module : this.moduleInstances.values()) {
            int lowest = Integer.MAX_VALUE;
            for (SettingGroup sg : module.settings) {
                for (Setting<?> setting : sg) {
                    int score = Utils.searchLevenshteinDefault(setting.title, text, false);
                    if (score < lowest) lowest = score;
                }
            }
            modules.put(module, modules.getOrDefault(module, 0) + lowest);
        }

        return modules.keySet();
    }

    void addActive(Module module) {
        synchronized (active) {
            if (!active.contains(module)) {
                active.add(module);
                MeteorClient.EVENT_BUS.post(ActiveModulesChangedEvent.get());
            }
        }
    }

    void removeActive(Module module) {
        synchronized (active) {
            if (active.remove(module)) {
                MeteorClient.EVENT_BUS.post(ActiveModulesChangedEvent.get());
            }
        }
    }

    // Binding

    public void setModuleToBind(Module moduleToBind) {
        this.moduleToBind = moduleToBind;
    }

    /***
     * @see meteordevelopment.meteorclient.commands.commands.BindCommand
     * For ensuring we don't instantly bind the module to the enter key.
     */
    public void awaitKeyRelease() {
        this.awaitingKeyRelease = true;
    }

    public boolean isBinding() {
        return moduleToBind != null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onKeyBinding(KeyEvent event) {
        if (event.action == KeyAction.Release && onBinding(true, event.key(), event.modifiers())) event.cancel();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onButtonBinding(MouseClickEvent event) {
        if (event.action == KeyAction.Release && onBinding(false, event.button(), 0)) event.cancel();
    }

    private boolean onBinding(boolean isKey, int value, int modifiers) {
        if (!isBinding()) return false;

        if (awaitingKeyRelease) {
            if (!isKey || (value != GLFW.GLFW_KEY_ENTER && value != GLFW.GLFW_KEY_KP_ENTER)) return false;

            awaitingKeyRelease = false;
            return false;
        }

        if (moduleToBind.keybind.canBindTo(isKey, value, modifiers)) {
            moduleToBind.keybind.set(isKey, value, modifiers);
            moduleToBind.info("Bound to (highlight)%s(default).", moduleToBind.keybind);
        }
        else if (value == GLFW.GLFW_KEY_ESCAPE) {
            moduleToBind.keybind.set(Keybind.none());
            moduleToBind.info("Removed bind.");
        }
        else return false;

        MeteorClient.EVENT_BUS.post(ModuleBindChangedEvent.get(moduleToBind));
        moduleToBind = null;

        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onKey(KeyEvent event) {
        if (event.action == KeyAction.Repeat) return;
        onAction(true, event.key(), event.modifiers(), event.action == KeyAction.Press);
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onMouseClick(MouseClickEvent event) {
        if (event.action == KeyAction.Repeat) return;
        onAction(false, event.button(), 0, event.action == KeyAction.Press);
    }

    private void onAction(boolean isKey, int value, int modifiers, boolean isPress) {
        if (mc.currentScreen != null || Input.isKeyPressed(GLFW.GLFW_KEY_F3)) return;

        for (Module module : moduleInstances.values()) {
            if (module.showPrimaryBindSettings()
                && module.keybind.matches(isKey, value, modifiers)
                && (isPress || (module.toggleOnBindRelease && module.isActive()))) {
                module.onPrimaryBindAction();
            }

            // Extra binds are always edge-triggered on press.
            if (!isPress) continue;

            for (Module.ExtraBindSettings bind : module.getExtraBinds()) {
                if (!bind.bind().get().matches(isKey, value, modifiers)) continue;
                module.onExtraBindAction(bind.bind());
            }
        }
    }

    public enum BindAliasMode {
        Dot,
        Slash
    }

    public boolean executeBindCommandAlias(String rawInput, BindAliasMode mode) {
        String input = normalizeInputAlias(rawInput, mode);
        if (input.isEmpty()) return false;

        int space = input.indexOf(' ');
        String command = space == -1 ? input : input.substring(0, space).trim();
        boolean hasArgs = space != -1 && !input.substring(space + 1).trim().isEmpty();

        boolean executed = false;

        for (Module module : moduleInstances.values()) {
            if (module.showPrimaryBindSettings()) {
                String mainAlias = aliasForMode(module.bindCommand, mode);
                if (!mainAlias.isEmpty() && mainAlias.equalsIgnoreCase(command)) {
                    if (hasArgs) {
                        ChatUtils.error("Command bind '%s' does not accept arguments.", command);
                        return true;
                    }

                    module.onPrimaryBindAction();
                    executed = true;
                }
            }

            for (Module.ExtraBindSettings bind : module.getExtraBinds()) {
                String extraAlias = aliasForMode(bind.command().get(), mode);
                if (extraAlias.isEmpty() || !extraAlias.equalsIgnoreCase(command)) continue;

                if (hasArgs) {
                    ChatUtils.error("Command bind '%s' does not accept arguments.", command);
                    return true;
                }

                module.onExtraBindAction(bind.bind());
                executed = true;
            }
        }

        return executed;
    }

    public Set<String> getBindCommandAliases(BindAliasMode mode) {
        Set<String> aliases = new LinkedHashSet<>();

        for (Module module : moduleInstances.values()) {
            if (module.showPrimaryBindSettings()) {
                String mainAlias = aliasForMode(module.bindCommand, mode);
                if (!mainAlias.isEmpty()) aliases.add(mainAlias);
            }

            for (Module.ExtraBindSettings bind : module.getExtraBinds()) {
                String extraAlias = aliasForMode(bind.command().get(), mode);
                if (!extraAlias.isEmpty()) aliases.add(extraAlias);
            }
        }

        return aliases;
    }

    public void syncDotBindCommandNodes() {
        Set<String> currentAliases = new LinkedHashSet<>(getBindCommandAliases(BindAliasMode.Dot));
        currentAliases.removeIf(alias -> alias.isBlank() || alias.indexOf(' ') != -1);

        CommandNode<CommandSource> root = Commands.DISPATCHER.getRoot();

        for (var entry : new LinkedHashMap<>(registeredDotAliasNodes).entrySet()) {
            String oldAlias = entry.getKey();
            if (currentAliases.contains(oldAlias)) continue;

            if (root.getChild(oldAlias) == entry.getValue()) removeCommandNode(root, oldAlias);
            registeredDotAliasNodes.remove(oldAlias);
        }

        for (String alias : currentAliases) {
            CommandNode<CommandSource> existing = root.getChild(alias);
            CommandNode<CommandSource> ours = registeredDotAliasNodes.get(alias);

            // Never override built-in Meteor commands for dot aliases.
            if (existing != null && existing != ours) continue;

            root.addChild(
                LiteralArgumentBuilder.<CommandSource>literal(alias)
                    .executes(ctx -> executeAliasNodeInput(ctx.getInput(), BindAliasMode.Dot))
                    .build()
            );

            CommandNode<CommandSource> injected = root.getChild(alias);
            if (injected != null) registeredDotAliasNodes.put(alias, injected);
        }
    }

    public void syncSlashBindCommandNodes() {
        if (mc.getNetworkHandler() == null) {
            registeredSlashAliasNodes.clear();
            return;
        }

        var dispatcher = mc.getNetworkHandler().getCommandDispatcher();
        if (dispatcher == null) return;

        Set<String> currentAliases = new LinkedHashSet<>(getBindCommandAliases(BindAliasMode.Slash));
        currentAliases.removeIf(alias -> alias.isBlank() || alias.indexOf(' ') != -1);

        CommandNode<ClientCommandSource> root = dispatcher.getRoot();

        for (String oldAlias : new LinkedHashSet<>(registeredSlashAliasNodes)) {
            if (currentAliases.contains(oldAlias)) continue;
            removeCommandNode(root, oldAlias);
            registeredSlashAliasNodes.remove(oldAlias);
        }

        for (String alias : currentAliases) {
            // Intentionally allow overriding existing slash command nodes.
            root.addChild(
                LiteralArgumentBuilder.<ClientCommandSource>literal(alias)
                    .executes(ctx -> executeAliasNodeInput(ctx.getInput(), BindAliasMode.Slash))
                    .build()
            );
            registeredSlashAliasNodes.add(alias);
        }
    }

    private int executeAliasNodeInput(String rawInput, BindAliasMode mode) {
        String token = rawInput == null ? "" : rawInput.trim();
        int firstSpace = token.indexOf(' ');
        if (firstSpace != -1) token = token.substring(0, firstSpace);

        executeBindCommandAlias(token, mode);
        return 1;
    }

    private static <S> void removeCommandNode(CommandNode<S> root, String name) {
        removeFromCommandNodeMap(root, COMMAND_NODE_CHILDREN_FIELD, name);
        removeFromCommandNodeMap(root, COMMAND_NODE_LITERALS_FIELD, name);
        removeFromCommandNodeMap(root, COMMAND_NODE_ARGUMENTS_FIELD, name);
    }

    private static Field getCommandNodeField(String name) {
        try {
            Field field = CommandNode.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
        catch (NoSuchFieldException exception) {
            throw new RuntimeException("Failed to initialize CommandNode field access for " + name, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static void removeFromCommandNodeMap(CommandNode<?> root, Field field, String name) {
        try {
            Map<String, ?> nodes = (Map<String, ?>) field.get(root);
            nodes.remove(name);
        }
        catch (IllegalAccessException exception) {
            throw new RuntimeException("Failed to update CommandNode field " + field.getName(), exception);
        }
    }

    private String normalizeInputAlias(String value, BindAliasMode mode) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.isEmpty()) return "";

        String prefix = Config.get().prefix.get();
        if (mode == BindAliasMode.Dot && !prefix.isEmpty() && normalized.startsWith(prefix)) {
            normalized = normalized.substring(prefix.length()).trim();
        }
        else if (mode == BindAliasMode.Slash && normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }

        return normalized;
    }

    private String aliasForMode(String configuredAlias, BindAliasMode mode) {
        if (configuredAlias == null) return "";
        String alias = configuredAlias.trim();
        if (alias.isEmpty()) return "";

        String prefix = Config.get().prefix.get();

        if (!prefix.isEmpty() && alias.startsWith(prefix)) {
            if (mode != BindAliasMode.Dot) return "";
            return alias.substring(prefix.length()).trim();
        }

        if (alias.startsWith("/")) {
            if (mode != BindAliasMode.Slash) return "";
            return alias.substring(1).trim();
        }

        // Bare aliases are slash-style only.
        if (mode != BindAliasMode.Slash) return "";
        return alias;
    }

    // End of binding

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onOpenScreen(OpenScreenEvent event) {
        if (!Utils.canUpdate()) return;

        for (Module module : moduleInstances.values()) {
            if (module.toggleOnBindRelease && module.isActive()) {
                module.toggle();
                module.sendToggledMsg();
            }
        }
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        synchronized (active) {
            for (Module module : getAll()) {
                if (module.isActive() && !module.runInMainMenu) {
                    MeteorClient.EVENT_BUS.subscribe(module);
                    module.onActivate();
                }
            }
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        synchronized (active) {
            for (Module module : getAll()) {
                if (module.isActive() && !module.runInMainMenu) {
                    MeteorClient.EVENT_BUS.unsubscribe(module);
                    module.onDeactivate();
                }
            }
        }
    }

    public void disableAll() {
        synchronized (active) {
            for (Module module : getAll()) {
                module.disable();
            }
        }
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();

        NbtList modulesTag = new NbtList();
        for (Module module : getAll()) {
            NbtCompound moduleTag = module.toTag();
            if (moduleTag != null) modulesTag.add(moduleTag);
        }
        tag.put("modules", modulesTag);

        return tag;
    }

    @Override
    public Modules fromTag(NbtCompound tag) {
        disableAll();

        NbtList modulesTag = tag.getListOrEmpty("modules");
        for (NbtElement moduleTagI : modulesTag) {
            NbtCompound moduleTag = (NbtCompound) moduleTagI;
            Module module = get(moduleTag.getString("name", ""));
            if (module != null) module.fromTag(moduleTag);
        }

        return this;
    }

    // INIT MODULES

    public void add(Module module) {
        // Check if the module's category is registered
        if (!CATEGORIES.contains(module.category)) {
            throw new RuntimeException("Modules.addModule - Module's category was not registered.");
        }

        // Remove the previous module with the same name
        AtomicReference<Module> removedModule = new AtomicReference<>();
        if (moduleInstances.values().removeIf(module1 -> {
            if (module1.name.equals(module.name)) {
                removedModule.set(module1);
                module1.settings.unregisterColorSettings();

                return true;
            }

            return false;
        })) {
            getGroup(removedModule.get().category).remove(removedModule.get());
        }

        // Add the module
        moduleInstances.put(module.getClass(), module);
        getGroup(module.category).add(module);

        // Register color settings for the module
        module.settings.registerColorSettings(module);
    }

    private void initCombat() {
        add(new AnchorAura());
        add(new AntiAnvil());
        add(new AntiBed());
        add(new ArrowDodge());
        add(new AttributeSwap());
        add(new AutoAnvil());
        add(new AutoArmor());
        add(new AutoCity());
        add(new AutoEXP());
        add(new AutoLog());
        add(new AutoTotem());
        add(new AutoTrap());
        add(new AutoWeapon());
        add(new AutoWeb());
        add(new BedAura());
        add(new BowAimbot());
        add(new BowSpam());
        add(new Burrow());
        add(new Criticals());
        add(new CrystalAura());
        add(new Hitboxes());
        add(new HoleFiller());
        add(new KillAura());
        add(new Offhand());
        add(new Quiver());
        add(new SelfAnvil());
        add(new SelfTrap());
        add(new SelfWeb());
        add(new Surround());
    }

    private void initPlayer() {
        add(new AirPlace());
        add(new AntiAFK());
        add(new AntiHunger());
        add(new AutoEat());
        add(new AutoClicker());
        add(new AutoFish());
        add(new AutoGap());
        add(new AutoReplenish());
        add(new AutoRespawn());
        add(new NewAutoTool());
        add(new BreakDelay());
        add(new ChestSwap());
        add(new CleanFishingChest());
        add(new EXPThrower());
        add(new FakePlayer());
        add(new FastUse());
        add(new GhostHand());
        add(new InstantRebreak());
        add(new LiquidInteract());
        add(new MiddleClickExtra());
        add(new Multitask());
        add(new NameProtect());
        add(new NoInteract());
        add(new NoMiningTrace());
        add(new NoRotate());
        add(new NoStatusEffects());
        add(new OffhandCrash());
        add(new Portals());
        add(new PotionSaver());
        add(new Reach());
        add(new RocketElytra());
        add(new Rotation());
        add(new SpeedMine());
    }

    private void initMovement() {
        add(new AirJump());
        add(new Anchor());
        add(new AntiVoid());
        add(new AutoJump());
        add(new AutoWalk());
        add(new AutoWasp());
        add(new Blink());
        add(new ClickTP());
        add(new ElytraBoost());
        add(new ElytraFly());
        add(new EntityControl());
        add(new FastClimb());
        add(new Flight());
        add(new GUIMove());
        add(new HighJump());
        add(new Jesus());
        add(new LongJump());
        add(new NoFall());
        add(new NoSlow());
        add(new Parkour());
        add(new ReverseStep());
        add(new SafeWalk());
        add(new Scaffold());
        add(new Slippy());
        add(new Sneak());
        add(new Speed());
        add(new Spider());
        add(new Sprint());
        add(new Step());
        add(new TridentBoost());
        add(new Velocity());
    }

    private void initRender() {
        add(new BetterTab());
        add(new BetterTooltips());
        add(new BlockESP());
        add(new BlockSelection());
        add(new Blur());
        add(new BossStack());
        add(new Breadcrumbs());
        add(new BreakIndicators());
        add(new CameraTweaks());
        add(new Chams());
        add(new CityESP());
        add(new EntityOwner());
        add(new ESP());
        add(new Freecam());
        add(new FreeLook());
        add(new Fullbright());
        add(new HandView());
        add(new HoleESP());
        add(new ItemPhysics());
        add(new ItemHighlight());
        add(new LightOverlay());
        add(new LogoutSpots());
        add(new Marker());
        add(new Nametags());
        add(new NewChunks());
        add(new NoFog());
        add(new NoEntityDistanceLimit());
        add(new NoRender());
        add(new PopChams());
        add(new StorageESP());
        add(new TimeChanger());
        add(new Tracers());
        add(new Trail());
        add(new Trajectories());
        add(new TunnelESP());
        add(new VoidESP());
        add(new WallHack());
        add(new WaypointsModule());
        add(new Xray());
        add(new Zoom());
    }

    private void initWorld() {
        add(new Ambience());
        add(new AutoBreed());
        add(new AutoBrewer());
        add(new AutoMount());
        add(new AutoNametag());
        add(new AutoShearer());
        add(new AutoSign());
        add(new AutoSmelter());
        add(new BuildHeight());
        add(new Collisions());
        add(new EChestFarmer());
        add(new EndermanLook());
        add(new Flamethrower());
        add(new HighwayBuilder());
        add(new LiquidFiller());
        add(new MountBypass());
        add(new NoGhostBlocks());
        add(new Nuker());
        add(new PacketMine());
        add(new StashFinder());
        add(new SpawnProofer());
        add(new StrongholdFinder());
        add(new Timer());
        add(new VeinMiner());

        if (BaritoneUtils.IS_AVAILABLE) {
            add(new Excavator());
            add(new InfinityMiner());

            if (BobbyUtils.IS_AVAILABLE) add(new BaritoneUseBobbyChunkCache());
        }
    }

    private void initMisc() {
        add(new AntiPacketKick());
        add(new AntiCheatDetect());
        add(new AutoConfig());
        add(new AutoReconnect());
        add(new BetterBeacons());
        add(new BetterChat());
        if (ChatPlusUtils.IS_AVAILABLE) {
            add(new ChatPlusCachesChatheads());
            add(new ChatPlusReconnectCompactor());
        }
        add(new BookBot());
        add(new DiscordPresence());
        add(new InventoryTweaks());
        add(new MessageAura());
        add(new Notebot());
        add(new Notifier());
        add(new PacketCanceller());
        add(new PacketLogger());
        add(new ServerSpoof());
        add(new SoundBlocker());
        add(new Spam());
        add(new Swarm());
        add(new Teams());
    }
}
