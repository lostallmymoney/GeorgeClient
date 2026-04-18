/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.network;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.ServerConnectBeginEvent;
import meteordevelopment.meteorclient.mixin.ClientPlayNetworkHandlerAccessor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.config.SelectKnownPacksS2CPacket;
import net.minecraft.network.packet.s2c.login.LoginHelloS2CPacket;
import net.minecraft.network.packet.s2c.play.CommandTreeS2CPacket;
import net.minecraft.registry.VersionedIdentifier;

import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ServerObserver {
    public static final ServerObserver INSTANCE = new ServerObserver();

    private static final Map<String, String> ANTICHEAT_KEYWORDS = new LinkedHashMap<>();

    static {
        ANTICHEAT_KEYWORDS.put("grimac", "Grim");
        ANTICHEAT_KEYWORDS.put("grim", "Grim");
        ANTICHEAT_KEYWORDS.put("vulcan", "Vulcan");
        ANTICHEAT_KEYWORDS.put("nocheatplus", "NoCheatPlus");
        ANTICHEAT_KEYWORDS.put("ncp", "NoCheatPlus");
        ANTICHEAT_KEYWORDS.put("spartan", "Spartan");
        ANTICHEAT_KEYWORDS.put("matrix", "Matrix");
        ANTICHEAT_KEYWORDS.put("kauri", "Kauri");
        ANTICHEAT_KEYWORDS.put("godseye", "GodsEye");
        ANTICHEAT_KEYWORDS.put("themis", "Themis");
        ANTICHEAT_KEYWORDS.put("witherac", "WitherAC");
        ANTICHEAT_KEYWORDS.put("lightanticheat", "LightAntiCheat");
        ANTICHEAT_KEYWORDS.put("guardianac", "GuardianAC");
        ANTICHEAT_KEYWORDS.put("anticheatreloaded", "AntiCheatReloaded");
        ANTICHEAT_KEYWORDS.put("illegalstack", "IllegalStack");
        ANTICHEAT_KEYWORDS.put("polar", "Polar");
        ANTICHEAT_KEYWORDS.put("horizon", "Horizon");
        ANTICHEAT_KEYWORDS.put("negativity", "Negativity");
        ANTICHEAT_KEYWORDS.put("wraith", "Wraith");
        ANTICHEAT_KEYWORDS.put("foxaddition", "FoxAddition");
        ANTICHEAT_KEYWORDS.put("ggintegrity", "GGIntegrity");
        ANTICHEAT_KEYWORDS.put("anarchyexploitfixes", "AnarchyExploitFixes");
        ANTICHEAT_KEYWORDS.put("exploitfixer", "ExploitFixer");
        ANTICHEAT_KEYWORDS.put("exploit", "Exploit Guard");
        ANTICHEAT_KEYWORDS.put("cheat", "Cheat Guard");
        ANTICHEAT_KEYWORDS.put("illegal", "IllegalStack");
    }

    private final Set<String> payloadChannels = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final Set<String> commandPlugins = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final Set<String> knownPacks = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final List<Detection> detections = new ArrayList<>();

    private String fullAddress = "";
    private String normalizedAddress = "";
    private String rootDomain = "";
    private String serverVersion = "";
    private String serverBrand = "";
    private boolean realms;
    private boolean needsAuthentication = true;
    private long lastJoinTime;

    private ServerObserver() {
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    public String getFullAddress() {
        return fullAddress;
    }

    public String getNormalizedAddress() {
        return normalizedAddress;
    }

    public String getRootDomain() {
        return rootDomain;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public String getServerBrand() {
        refreshSessionMetadata();
        return serverBrand;
    }

    public boolean isRealms() {
        return realms;
    }

    public boolean needsAuthentication() {
        return needsAuthentication;
    }

    public long getLastJoinTime() {
        return lastJoinTime;
    }

    public Set<String> getPayloadChannels() {
        return Collections.unmodifiableSet(payloadChannels);
    }

    public Set<String> getCommandPlugins() {
        return Collections.unmodifiableSet(commandPlugins);
    }

    public Set<String> getKnownPacks() {
        return Collections.unmodifiableSet(knownPacks);
    }

    public List<Detection> getDetections() {
        return List.copyOf(detections);
    }

    public String getDisplayAddress() {
        if (!fullAddress.isBlank()) return fullAddress;
        if (!normalizedAddress.isBlank()) return normalizedAddress;
        return realms ? "realms" : "unknown";
    }

    public void refreshSessionMetadata() {
        if (mc.getCurrentServerEntry() != null) {
            ServerInfo info = mc.getCurrentServerEntry();

            realms = info.isRealm();
            fullAddress = realms ? "realms" : info.address;
            normalizedAddress = normalizeServerAddress(fullAddress);
            rootDomain = deriveRootDomain(normalizedAddress);
            serverVersion = info.version != null ? info.version.getString() : serverVersion;
        }

        if (mc.getNetworkHandler() != null && mc.getNetworkHandler().getBrand() != null) {
            serverBrand = mc.getNetworkHandler().getBrand();
        }

        refreshDetections();
    }

    public static String normalizeServerAddress(String address) {
        if (address == null) return "";

        String normalized = address.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return "";
        if (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.startsWith("[")) {
            int end = normalized.indexOf(']');
            if (end > 1) return normalized.substring(1, end);
        }

        int colon = normalized.lastIndexOf(':');
        if (colon > 0 && normalized.indexOf(':') == colon) normalized = normalized.substring(0, colon);

        return normalized;
    }

    public static String deriveRootDomain(String host) {
        if (host == null || host.isBlank()) return "";
        if (host.equals("realms") || isIpLike(host) || host.equals("localhost")) return host;

        String[] parts = host.split("\\.");
        if (parts.length <= 2) return host;

        int take = parts[parts.length - 1].length() == 2 && parts[parts.length - 2].length() <= 3 ? 3 : 2;
        if (parts.length < take) return host;

        return String.join(".", Arrays.copyOfRange(parts, parts.length - take, parts.length));
    }

    private static boolean isIpLike(String host) {
        if (host.indexOf(':') >= 0) return true;

        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;

        for (String part : parts) {
            if (part.isEmpty()) return false;

            try {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) return false;
            }
            catch (NumberFormatException ignored) {
                return false;
            }
        }

        return true;
    }

    @EventHandler
    private void onServerConnectBegin(ServerConnectBeginEvent event) {
        clear();

        if (event.info != null) {
            realms = event.info.isRealm();
            fullAddress = realms ? "realms" : event.info.address;
        }
        else if (event.address != null) {
            fullAddress = event.address.getAddress() + ":" + event.address.getPort();
        }

        normalizedAddress = normalizeServerAddress(fullAddress);
        rootDomain = deriveRootDomain(normalizedAddress);
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        lastJoinTime = System.currentTimeMillis();
        refreshSessionMetadata();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        clear();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof CommandTreeS2CPacket packet) {
            ClientPlayNetworkHandlerAccessor handler = (ClientPlayNetworkHandlerAccessor) event.connection.getPacketListener();

            packet.getCommandTree(
                CommandRegistryAccess.of(handler.meteor$getCombinedDynamicRegistries(), handler.meteor$getEnabledFeatures()),
                ClientPlayNetworkHandlerAccessor.meteor$getCommandNodeFactory()
            ).getChildren().forEach(node -> {
                String[] split = node.getName().split(":");
                if (split.length > 1) commandPlugins.add(split[0]);
            });

            refreshDetections();
        }

        if (event.packet instanceof CustomPayloadS2CPacket packet) {
            payloadChannels.add(packet.payload().getId().id().toString());
            refreshSessionMetadata();
        }

        if (event.packet instanceof SelectKnownPacksS2CPacket packet) {
            for (VersionedIdentifier knownPack : packet.knownPacks()) {
                knownPacks.add(knownPack.namespace() + ":" + knownPack.id() + "@" + knownPack.version());
            }

            refreshDetections();
        }

        if (event.packet instanceof LoginHelloS2CPacket packet) {
            needsAuthentication = packet.needsAuthentication();
            refreshDetections();
        }
    }

    private void clear() {
        payloadChannels.clear();
        commandPlugins.clear();
        knownPacks.clear();
        detections.clear();

        fullAddress = "";
        normalizedAddress = "";
        rootDomain = "";
        serverVersion = "";
        serverBrand = "";
        realms = false;
        needsAuthentication = true;
        lastJoinTime = 0;
    }

    private void refreshDetections() {
        Map<String, Detection> merged = new LinkedHashMap<>();

        collectDetections(commandPlugins, "plugin", merged);
        collectDetections(payloadChannels, "payload", merged);
        collectDetections(knownPacks, "pack", merged);

        if (!serverBrand.isBlank()) collectDetection(serverBrand, "brand", merged);

        detections.clear();
        detections.addAll(merged.values());
    }

    private void collectDetections(Collection<String> values, String source, Map<String, Detection> merged) {
        for (String value : values) collectDetection(value, source, merged);
    }

    private void collectDetection(String value, String source, Map<String, Detection> merged) {
        String normalized = value.toLowerCase(Locale.ROOT);

        for (Map.Entry<String, String> entry : ANTICHEAT_KEYWORDS.entrySet()) {
            if (!normalized.contains(entry.getKey())) continue;

            String key = entry.getValue() + "|" + source;
            merged.putIfAbsent(key, new Detection(entry.getValue(), source, value));
            return;
        }
    }

    public record Detection(String name, String source, String signal) {}
}
