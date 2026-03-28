/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import com.mojang.authlib.GameProfile;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatPlusCachesChatheads extends Module {
    private final ChatPlusHeadCacheBridge bridge = new ChatPlusHeadCacheBridge();

    public static void onSkinTexturesReceived(@Nullable UUID uuid, @Nullable SkinTextures skinTextures) {
        if (uuid == null || skinTextures == null) return;

        try {
            ChatPlusCachesChatheads module = Modules.get().get(ChatPlusCachesChatheads.class);
            if (module == null || !module.isActive()) return;

            module.bridge.captureSkinUpdate(uuid, skinTextures);
        } catch (RuntimeException ignored) {
        }
    }

    public ChatPlusCachesChatheads() {
        super(Categories.Misc, "chatplus-caches-chatheads", "Keeps ChatPlus chat head cache populated by UUID.");
    }

    @Override
    public void onActivate() {
        bridge.enable();
        if (mc.getNetworkHandler() != null) bridge.refreshOnlinePlayers(mc.getNetworkHandler().getPlayerList());
    }

    @Override
    public void onDeactivate() {
        bridge.disable();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (mc.getNetworkHandler() == null) return;
        bridge.refreshOnlinePlayers(mc.getNetworkHandler().getPlayerList());
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!bridge.shouldFallbackToIncomingScan()) return;
        bridge.captureIncomingMessage(event.getMessage());
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerListS2CPacket packet) {
            if (packet.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) {
                bridge.captureAdditionEntries(packet);
            }
        } else if (event.packet instanceof PlayerRemoveS2CPacket packet && mc.getNetworkHandler() != null) {
            bridge.captureRemovedEntries(packet, mc.getNetworkHandler().getPlayerList());
        }
    }

    private static final class ChatPlusHeadCacheBridge {
        private static final String CACHE_NAMESPACE = "meteor-chatheadcache";
        private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,16}");
        private static final int LOG_MESSAGE_PREVIEW_LENGTH = 200;

        private boolean unavailable;
        private boolean initialized;

        private Map<Object, Object> playerHeads;
        private Map<Object, Object> playerNameUUIDs;

        private Constructor<?> timedUuidCtor;
        private Constructor<?> headDataCtor;
        private Class<?> function0Class;
        private Class<?> function1Class;
        private Object kotlinUnit;
        private Method timedUuidGetUuid;

        private Object eventBus;
        private Method eventBusRegister;
        private Method eventBusUnregister;
        private Object addNewMessageCallback;
        private Class<?> addNewMessageEventClass;
        private Method addNewMessageGetRawComponent;
        private Method addNewMessageGetSenderUuid;
        private Method addNewMessageSetSenderUuid;

        private Class<?> headDataClass;
        private Method headDataTextureGetter;
        private Class<?> supplierClass;
        private Method supplierInvokeMethod;

        private Object chatManager;
        private Method chatManagerGetGlobalSortedTabs;
        private Method chatTabGetMessages;
        private Method chatPlusGuiMessageGetSenderUuid;
        private Method chatPlusGuiMessageSetSenderUuid;
        private boolean messageRefreshUnavailable;

        private final Map<UUID, Object> savedHeads = new HashMap<>();
        private final Map<UUID, Identifier> savedTextures = new HashMap<>();
        private final Map<String, UUID> knownNameUuids = new HashMap<>();
        private final Set<UUID> diskCachedUuids = new HashSet<>();
        private boolean diskCacheLoaded;
        private Path cacheDirectory;
        private boolean hookRegistrationFailed;

        void enable() {
            if (!init()) return;
            registerAddNewMessageHook();
        }

        void disable() {
            unregisterAddNewMessageHook();
        }

        boolean shouldFallbackToIncomingScan() {
            return hookRegistrationFailed || addNewMessageCallback == null;
        }

        void refreshOnlinePlayers(Collection<PlayerListEntry> playerList) {
            if (!init()) return;
            long now = System.currentTimeMillis();
            try {
                ensureDiskCacheLoaded();
                snapshotSavedHeads();

                for (PlayerListEntry entry : playerList) {
                    UUID uuid = entry.getProfile().id();
                    String name = entry.getProfile().name();
                    capturePlayerName(name, uuid, now);
                    cacheHead(uuid, entry);
                }

                restoreSavedHeads();
            } catch (ReflectiveOperationException ignored) {
            } catch (RuntimeException ignored) {
            }
        }

        void captureAdditionEntries(PlayerListS2CPacket packet) {
            long now = System.currentTimeMillis();
            for (PlayerListS2CPacket.Entry entry : packet.getPlayerAdditionEntries()) {
                GameProfile profile = entry.profile();
                if (profile == null) continue;

                UUID uuid = profile.id();
                if (uuid == null) continue;

                capturePlayerName(profile.name(), uuid, now);
                requestSkinFetch(profile);

                // Resolve after add-player processing so the list entry is actually visible when we check it.
                MinecraftClient.getInstance().execute(() -> {
                    try {
                        ensureHeadAvailable(uuid);
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                    }
                });
            }
        }

        void captureRemovedEntries(PlayerRemoveS2CPacket packet, Collection<PlayerListEntry> playerList) {
            Map<UUID, String> namesByUuid = new HashMap<>();
            for (PlayerListEntry entry : playerList) {
                namesByUuid.put(entry.getProfile().id(), entry.getProfile().name());
            }

            long now = System.currentTimeMillis();
            for (UUID uuid : packet.profileIds()) {
                capturePlayerName(namesByUuid.get(uuid), uuid, now);
            }
        }

        void captureIncomingMessage(Text message) {
            if (message == null) return;

            String content = message.getString();
            if (content.isBlank()) return;

            long now = System.currentTimeMillis();
            Matcher matcher = NAME_PATTERN.matcher(content);
            while (matcher.find()) {
                String name = matcher.group();
                try {
                    UUID uuid = resolveNameToUuid(name);
                    if (uuid != null) capturePlayerName(name, uuid, now);
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }

        private void capturePlayerName(@Nullable String name, @Nullable UUID uuid, long now) {
            if (name == null || name.isBlank() || uuid == null) return;
            knownNameUuids.put(name, uuid);

            if (!init()) return;

            try {
                ensureDiskCacheLoaded();
                playerNameUUIDs.put(name, timedUuidCtor.newInstance(uuid, now));
                restoreSavedHead(uuid);
            } catch (ReflectiveOperationException ignored) {
            } catch (RuntimeException ignored) {
            }
        }

        private void snapshotSavedHeads() throws ReflectiveOperationException {
            for (Map.Entry<Object, Object> entry : playerHeads.entrySet()) {
                if (!(entry.getKey() instanceof UUID uuid) || entry.getValue() == null) continue;

                Identifier texture = getHeadDataTexture(entry.getValue());
                if (texture == null || isDefaultTexture(uuid, texture)) continue;

                savedHeads.put(uuid, entry.getValue());
                savedTextures.put(uuid, texture);
            }
        }

        private void restoreSavedHeads() throws ReflectiveOperationException {
            for (UUID uuid : savedHeads.keySet()) {
                restoreSavedHead(uuid);
            }
        }

        private void restoreSavedHead(UUID uuid) throws ReflectiveOperationException {
            Object saved = savedHeads.get(uuid);
            if (saved == null) return;

            Object live = playerHeads.get(uuid);
            if (live == null) {
                playerHeads.put(uuid, saved);
                return;
            }

            Identifier liveTexture = getHeadDataTexture(live);
            if (liveTexture == null || isDefaultTexture(uuid, liveTexture)) {
                playerHeads.put(uuid, saved);
            }
        }

        @Nullable
        private UUID resolveNameToUuid(String name) throws ReflectiveOperationException {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.getNetworkHandler() != null) {
                for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
                    if (entry == null || entry.getProfile() == null) continue;
                    if (!name.equals(entry.getProfile().name())) continue;

                    UUID uuid = entry.getProfile().id();
                    if (uuid == null) continue;

                    knownNameUuids.put(name, uuid);
                    return uuid;
                }
            }

            UUID known = knownNameUuids.get(name);
            if (known != null) return known;

            if (!init()) return null;

            Object timed = playerNameUUIDs.get(name);
            if (timed == null) return null;

            if (timedUuidGetUuid == null || timedUuidGetUuid.getDeclaringClass() != timed.getClass()) {
                timedUuidGetUuid = findNoArgMethodReturning(timed.getClass(), UUID.class);
            }

            if (timedUuidGetUuid == null) return null;
            Object value = timedUuidGetUuid.invoke(timed);
            return value instanceof UUID uuid ? uuid : null;
        }

        private void registerAddNewMessageHook() {
            if (addNewMessageCallback != null) return;

            try {
                Object priority = newConstantFunction0(1_000);
                Object skipOthers = newConstantFunction0(false);

                addNewMessageCallback = Proxy.newProxyInstance(function1Class.getClassLoader(), new Class<?>[] { function1Class }, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "invoke" -> {
                            if (args != null && args.length > 0) {
                                onChatPlusAddNewMessage(args[0]);
                            }
                            yield kotlinUnit;
                        }
                        case "equals" -> args != null && args.length == 1 && proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> null;
                    };
                });

                eventBusRegister.invoke(eventBus, priority, skipOthers, addNewMessageEventClass, addNewMessageCallback);
                hookRegistrationFailed = false;
            } catch (ReflectiveOperationException e) {
                hookRegistrationFailed = true;
                addNewMessageCallback = null;
            }
        }

        private void unregisterAddNewMessageHook() {
            if (addNewMessageCallback == null) return;

            try {
                eventBusUnregister.invoke(eventBus, addNewMessageEventClass, addNewMessageCallback);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            } finally {
                addNewMessageCallback = null;
            }
        }

        private void captureSkinUpdate(UUID uuid, SkinTextures skinTextures) {
            if (!init()) return;

            try {
                boolean changed = cacheTexture(uuid, getTexture(skinTextures));
                restoreSavedHead(uuid);
                if (changed) refreshMessagesForUuid(uuid);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }

        private void ensureHeadAvailable(UUID uuid) throws ReflectiveOperationException {
            restoreSavedHead(uuid);

            PlayerListEntry entry = findOnlinePlayer(uuid);
            if (entry != null) requestSkinFetch(entry.getProfile());

            Object liveHead = playerHeads.get(uuid);
            if (liveHead != null) {
                Identifier liveTexture = getHeadDataTexture(liveHead);
                if (liveTexture != null && !isDefaultTexture(uuid, liveTexture)) return;
            }

            if (entry == null) return;
            cacheHead(uuid, entry);
        }

        private void requestSkinFetch(@Nullable GameProfile profile) {
            if (profile == null || profile.id() == null) return;

            MinecraftClient client = MinecraftClient.getInstance();
            try {
                client.getSkinProvider().supplySkinTextures(profile, !client.uuidEquals(profile.id()));
            } catch (RuntimeException ignored) {
            }
        }

        @Nullable
        private PlayerListEntry findOnlinePlayer(UUID uuid) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.getNetworkHandler() == null) return null;

            for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
                if (entry == null || entry.getProfile() == null) continue;
                if (uuid.equals(entry.getProfile().id())) return entry;
            }

            return null;
        }

        private void onChatPlusAddNewMessage(@Nullable Object addNewMessageEvent) {
            if (addNewMessageEvent == null) return;

            try {
                Object raw = addNewMessageGetRawComponent.invoke(addNewMessageEvent);
                if (!(raw instanceof Text text)) return;

                String content = text.getString();
                if (content.isBlank()) return;

                Object sender = addNewMessageGetSenderUuid.invoke(addNewMessageEvent);
                if (sender instanceof UUID uuid) {
                    MeteorClient.LOG.info("[chatplus-caches-chatheads] Message UUID: {} | \"{}\"", uuid, preview(content));
                    ensureHeadAvailable(uuid);
                    return;
                }

                MeteorClient.LOG.info("[chatplus-caches-chatheads] Message UUID: NO UUID | \"{}\"", preview(content));

                long now = System.currentTimeMillis();
                Matcher matcher = NAME_PATTERN.matcher(content);
                while (matcher.find()) {
                    String name = matcher.group();
                    UUID uuid = resolveNameToUuid(name);
                    if (uuid == null) continue;

                    capturePlayerName(name, uuid, now);
                    addNewMessageSetSenderUuid.invoke(addNewMessageEvent, uuid);
                    ensureHeadAvailable(uuid);
                    MeteorClient.LOG.info("[chatplus-caches-chatheads] Assigned UUID {} via token \"{}\".", uuid, name);
                    return;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }

        private boolean init() {
            if (unavailable) return false;
            if (initialized) return true;

            try {
                Class<?> displayClass = Class.forName("com.ebicep.chatplus.features.PlayerHeadChatDisplay");
                Object instance = displayClass.getField("INSTANCE").get(null);

                Field headsField = displayClass.getDeclaredField("playerHeads");
                headsField.setAccessible(true);
                playerHeads = readObjectMap(instance, headsField);

                Field namesField = displayClass.getDeclaredField("playerNameUUIDs");
                namesField.setAccessible(true);
                playerNameUUIDs = readObjectMap(instance, namesField);

                Class<?> timedUuidClass = Class.forName("com.ebicep.chatplus.features.PlayerHeadChatDisplay$TimedUUID");
                timedUuidCtor = findCtor(timedUuidClass, 2);

                Class<?> headDataClass = Class.forName("com.ebicep.chatplus.features.PlayerHeadChatDisplay$HeadData");
                headDataCtor = findCtor(headDataClass, 2);

                function0Class = Class.forName("kotlin.jvm.functions.Function0");
                function1Class = Class.forName("kotlin.jvm.functions.Function1");
                kotlinUnit = Class.forName("kotlin.Unit").getField("INSTANCE").get(null);

                Class<?> eventBusClass = Class.forName("com.ebicep.chatplus.events.EventBus");
                eventBus = eventBusClass.getField("INSTANCE").get(null);
                eventBusRegister = findRegisterMethod(eventBusClass);
                eventBusUnregister = findUnregisterMethod(eventBusClass);

                addNewMessageEventClass = Class.forName("com.ebicep.chatplus.features.chattabs.AddNewMessageEvent");
                addNewMessageGetRawComponent = findNoArgMethodReturning(addNewMessageEventClass, Text.class);
                addNewMessageGetSenderUuid = findNoArgMethodReturning(addNewMessageEventClass, UUID.class);
                addNewMessageSetSenderUuid = findSingleArgMethod(addNewMessageEventClass, UUID.class);

                if (eventBusRegister == null || eventBusUnregister == null || addNewMessageGetRawComponent == null || addNewMessageGetSenderUuid == null || addNewMessageSetSenderUuid == null) {
                    throw new ReflectiveOperationException("ChatPlus event bus methods unavailable.");
                }

                initialized = true;
                return true;
            } catch (ReflectiveOperationException e) {
                unavailable = true;
                return false;
            }
        }

        private void cacheHead(UUID uuid, PlayerListEntry entry) throws ReflectiveOperationException {
            cacheTexture(uuid, getTexture(entry));
        }

        private boolean cacheTexture(UUID uuid, @Nullable Identifier texture) throws ReflectiveOperationException {
            if (texture == null) return false;
            ensureDiskCacheLoaded();
            if (isDefaultTexture(uuid, texture)) {
                // Only cache Steve/Alex when we have nothing cached yet for this UUID.
                if (hasAnyCachedHead(uuid)) return false;

                Object headData = headDataCtor.newInstance(newTextureSupplier(texture), true);
                playerHeads.put(uuid, headData);
                savedHeads.put(uuid, headData);
                savedTextures.put(uuid, texture);
                persistTextureToDisk(uuid, texture);
                return true;
            }

            Identifier previousTexture = savedTextures.get(uuid);
            Object liveHead = playerHeads.get(uuid);
            Identifier liveTexture = getHeadDataTexture(liveHead);
            if (texture.equals(previousTexture) && texture.equals(liveTexture)) {
                if (!hasDiskCacheFile(uuid)) persistTextureToDisk(uuid, texture);
                return false;
            }

            Object headData = headDataCtor.newInstance(newTextureSupplier(texture), true);
            playerHeads.put(uuid, headData);
            savedHeads.put(uuid, headData);
            savedTextures.put(uuid, texture);
            persistTextureToDisk(uuid, texture);
            return true;
        }

        private boolean hasAnyCachedHead(UUID uuid) {
            return savedHeads.containsKey(uuid) || savedTextures.containsKey(uuid) || playerHeads.containsKey(uuid) || hasDiskCacheFile(uuid);
        }

        private String preview(String content) {
            if (content.length() <= LOG_MESSAGE_PREVIEW_LENGTH) return content;
            return content.substring(0, LOG_MESSAGE_PREVIEW_LENGTH) + "...";
        }

        private void refreshMessagesForUuid(UUID uuid) {
            if (!initMessageRefresh()) return;

            try {
                Object tabsObject = chatManagerGetGlobalSortedTabs.invoke(chatManager);
                if (!(tabsObject instanceof Collection<?> tabs)) return;

                for (Object tab : tabs) {
                    if (tab == null) continue;
                    Object messagesObject = chatTabGetMessages.invoke(tab);
                    if (!(messagesObject instanceof Collection<?> messages)) continue;

                    for (Object message : messages) {
                        if (message == null) continue;
                        Object sender = chatPlusGuiMessageGetSenderUuid.invoke(message);
                        if (!(sender instanceof UUID messageUuid) || !uuid.equals(messageUuid)) continue;
                        chatPlusGuiMessageSetSenderUuid.invoke(message, uuid);
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }

        private boolean initMessageRefresh() {
            if (messageRefreshUnavailable) return false;
            if (chatManager != null
                && chatManagerGetGlobalSortedTabs != null
                && chatTabGetMessages != null
                && chatPlusGuiMessageGetSenderUuid != null
                && chatPlusGuiMessageSetSenderUuid != null
            ) {
                return true;
            }

            try {
                Class<?> chatManagerClass = Class.forName("com.ebicep.chatplus.hud.ChatManager");
                chatManager = chatManagerClass.getField("INSTANCE").get(null);
                chatManagerGetGlobalSortedTabs = findNoArgMethodByName(chatManagerClass, "getGlobalSortedTabs");

                Class<?> chatTabClass = Class.forName("com.ebicep.chatplus.features.chattabs.ChatTab");
                chatTabGetMessages = findNoArgMethodByName(chatTabClass, "getMessages");

                Class<?> chatPlusGuiMessageClass = Class.forName("com.ebicep.chatplus.features.chattabs.ChatTab$ChatPlusGuiMessage");
                chatPlusGuiMessageGetSenderUuid = findNoArgMethodReturning(chatPlusGuiMessageClass, UUID.class);
                chatPlusGuiMessageSetSenderUuid = findSingleArgMethod(chatPlusGuiMessageClass, UUID.class);

                if (chatManagerGetGlobalSortedTabs == null
                    || chatTabGetMessages == null
                    || chatPlusGuiMessageGetSenderUuid == null
                    || chatPlusGuiMessageSetSenderUuid == null
                ) {
                    throw new ReflectiveOperationException("ChatPlus message refresh hooks unavailable.");
                }

                return true;
            } catch (ReflectiveOperationException e) {
                messageRefreshUnavailable = true;
                return false;
            }
        }

        private void ensureDiskCacheLoaded() throws ReflectiveOperationException {
            if (diskCacheLoaded) return;

            cacheDirectory = MinecraftClient.getInstance().runDirectory.toPath().resolve("cache").resolve("chatheadcache");

            try {
                Files.createDirectories(cacheDirectory);

                try (var files = Files.list(cacheDirectory)) {
                    files.filter(path -> path.getFileName().toString().endsWith(".png")).forEach(path -> {
                        UUID uuid = parseUuid(path.getFileName().toString());
                        if (uuid == null) return;
                        diskCachedUuids.add(uuid);

                        Identifier id = registerDiskTexture(uuid, path);
                        if (id == null) return;

                        try {
                            Object headData = headDataCtor.newInstance(newTextureSupplier(id), true);
                            savedHeads.put(uuid, headData);
                            savedTextures.put(uuid, id);
                        } catch (ReflectiveOperationException ignored) {
                        }
                    });
                }
            } catch (IOException ignored) {
            }

            diskCacheLoaded = true;
        }

        @Nullable
        private Identifier registerDiskTexture(UUID uuid, Path file) {
            try (InputStream stream = Files.newInputStream(file)) {
                NativeImage image = NativeImage.read(stream);
                Identifier id = Identifier.of(CACHE_NAMESPACE, uuid.toString());
                NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "chatheadcache/" + uuid, image);
                MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
                return id;
            } catch (IOException ignored) {
                return null;
            }
        }

        private void persistTextureToDisk(UUID uuid, Identifier textureId) {
            if (cacheDirectory == null) return;

            try {
                var texture = MinecraftClient.getInstance().getTextureManager().getTexture(textureId);
                if (!(texture instanceof NativeImageBackedTexture nativeTexture)) return;

                NativeImage image = nativeTexture.getImage();
                if (image == null) return;

                image.writeTo(cacheDirectory.resolve(uuid + ".png"));
                diskCachedUuids.add(uuid);
            } catch (IOException ignored) {
            }
        }

        private boolean hasDiskCacheFile(UUID uuid) {
            if (diskCachedUuids.contains(uuid)) return true;
            if (cacheDirectory == null) return false;

            boolean exists = Files.isRegularFile(cacheDirectory.resolve(uuid + ".png"));
            if (exists) diskCachedUuids.add(uuid);
            return exists;
        }

        @Nullable
        private UUID parseUuid(String fileName) {
            if (!fileName.endsWith(".png")) return null;

            String base = fileName.substring(0, fileName.length() - 4);
            try {
                return UUID.fromString(base);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        @Nullable
        private Identifier getHeadDataTexture(Object headData) throws ReflectiveOperationException {
            if (headData == null) return null;

            Class<?> currentHeadDataClass = headData.getClass();
            if (headDataClass != currentHeadDataClass) {
                headDataClass = currentHeadDataClass;
                headDataTextureGetter = findNoArgMethodReturning(currentHeadDataClass, function0Class);
                supplierClass = null;
                supplierInvokeMethod = null;
            }

            if (headDataTextureGetter == null) return null;
            Object supplier = headDataTextureGetter.invoke(headData);
            if (supplier == null) return null;

            Class<?> currentSupplierClass = supplier.getClass();
            if (supplierClass != currentSupplierClass) {
                supplierClass = currentSupplierClass;
                supplierInvokeMethod = findNoArgMethodByName(currentSupplierClass, "invoke");
            }

            if (supplierInvokeMethod == null) return null;
            Object value = supplierInvokeMethod.invoke(supplier);
            return value instanceof Identifier id ? id : null;
        }

        private Object newConstantFunction0(Object value) {
            return Proxy.newProxyInstance(function0Class.getClassLoader(), new Class<?>[] { function0Class }, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "invoke" -> value;
                    case "equals" -> args != null && args.length == 1 && proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                };
            });
        }

        @Nullable
        private Method findRegisterMethod(Class<?> eventBusClass) {
            for (Method method : eventBusClass.getMethods()) {
                if (!method.getName().equals("register")) continue;
                if (method.getParameterCount() != 4) continue;
                return method;
            }

            for (Method method : eventBusClass.getDeclaredMethods()) {
                if (!method.getName().equals("register")) continue;
                if (method.getParameterCount() != 4) continue;
                method.setAccessible(true);
                return method;
            }

            return null;
        }

        @Nullable
        private Method findUnregisterMethod(Class<?> eventBusClass) {
            for (Method method : eventBusClass.getMethods()) {
                if (!method.getName().equals("unregister")) continue;
                if (method.getParameterCount() != 2) continue;
                return method;
            }

            for (Method method : eventBusClass.getDeclaredMethods()) {
                if (!method.getName().equals("unregister")) continue;
                if (method.getParameterCount() != 2) continue;
                method.setAccessible(true);
                return method;
            }

            return null;
        }

        @Nullable
        private Identifier getTexture(PlayerListEntry entry) {
            return getTexture(entry.getSkinTextures());
        }

        @Nullable
        private Identifier getTexture(@Nullable SkinTextures skinTextures) {
            if (skinTextures == null) return null;
            var body = skinTextures.body();
            if (body == null) return null;
            return body.texturePath();
        }

        private boolean isDefaultTexture(UUID uuid, Identifier texture) {
            var defaultSkin = DefaultSkinHelper.getSkinTextures(uuid);
            if (defaultSkin == null || defaultSkin.body() == null) return false;
            Identifier defaultTexture = defaultSkin.body().texturePath();
            return texture.equals(defaultTexture);
        }

        private Object newTextureSupplier(Identifier texture) {
            return Proxy.newProxyInstance(function0Class.getClassLoader(), new Class<?>[] { function0Class }, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "invoke" -> texture;
                    case "equals" -> args != null && args.length == 1 && proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                };
            });
        }

        private Constructor<?> findCtor(Class<?> clazz, int parameterCount) throws NoSuchMethodException {
            for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == parameterCount) {
                    ctor.setAccessible(true);
                    return ctor;
                }
            }

            throw new NoSuchMethodException(clazz.getName() + " constructor with " + parameterCount + " params not found.");
        }

        @Nullable
        private Method findNoArgMethodReturning(Class<?> clazz, Class<?> returnType) {
            for (Method method : clazz.getMethods()) {
                if (method.getParameterCount() == 0 && returnType.isAssignableFrom(method.getReturnType())) {
                    return method;
                }
            }

            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getParameterCount() == 0 && returnType.isAssignableFrom(method.getReturnType())) {
                    method.setAccessible(true);
                    return method;
                }
            }

            return null;
        }

        @Nullable
        private Method findNoArgMethodByName(Class<?> clazz, String name) {
            for (Method method : clazz.getMethods()) {
                if (method.getParameterCount() == 0 && method.getName().equals(name)) {
                    return method;
                }
            }

            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getParameterCount() == 0 && method.getName().equals(name)) {
                    method.setAccessible(true);
                    return method;
                }
            }

            return null;
        }

        @Nullable
        private Method findSingleArgMethod(Class<?> clazz, Class<?> parameterType) {
            for (Method method : clazz.getMethods()) {
                if (method.getParameterCount() == 1 && parameterType.isAssignableFrom(method.getParameterTypes()[0])) {
                    return method;
                }
            }

            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getParameterCount() == 1 && parameterType.isAssignableFrom(method.getParameterTypes()[0])) {
                    method.setAccessible(true);
                    return method;
                }
            }

            return null;
        }

        @SuppressWarnings("unchecked")
        private Map<Object, Object> readObjectMap(Object instance, Field field) throws ReflectiveOperationException {
            Object value = field.get(instance);
            if (!(value instanceof Map<?, ?> map)) {
                throw new ReflectiveOperationException(field.getName() + " is not a map.");
            }

            return (Map<Object, Object>) (Map<?, ?>) map;
        }
    }
}
