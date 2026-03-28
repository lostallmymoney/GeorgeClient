/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.mixin.ChatHudAccessor;
import meteordevelopment.meteorclient.mixininterface.IChatHudLineVisible;
import meteordevelopment.meteorclient.pathing.ChatPlusUtils;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatPlusReconnectCompactor extends Module {
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("^<[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?>\\s+");
    private static final Pattern DECORATION_PREFIX_PATTERN = Pattern.compile("^(?:\\[[^\\]]+]|\\([^\\)]+\\)|\\{[^}]+}|\\d{1,2}:[0-9]{2}(?::[0-9]{2})?)\\s+");
    private static final Pattern JOIN_PATTERN = Pattern.compile("^([A-Za-z0-9_]{1,32}) joined(?: the game)?\\.?$");
    private static final Pattern LEAVE_PATTERN = Pattern.compile("^([A-Za-z0-9_]{1,32}) left(?: the game)?\\.?$");
    private static final Pattern RECONNECTED_PATTERN = Pattern.compile("^([A-Za-z0-9_]{1,32}) reconnected(?: \\(([0-9]+)\\))?\\.?$");
    private static final Pattern RECONNECTED_LEFT_PATTERN = Pattern.compile("^([A-Za-z0-9_]{1,32}) reconnected(?: \\(([0-9]+)\\))? and left\\.?$");
    private static final Pattern ACTIVE_SESSION_PATTERN = Pattern.compile("^([A-Za-z0-9_]{1,32}) joined, reconnected(?: \\(([0-9]+)\\))?\\.?$");
    private static final Pattern JOINED_LEFT_PATTERN = Pattern.compile("^([A-Za-z0-9_]{1,32}) joined and left\\.?$");
    private static final Pattern SESSION_LEFT_PATTERN = Pattern.compile("^([A-Za-z0-9_]{1,32}) joined, reconnected(?: \\(([0-9]+)\\))?, and left\\.?$");
    private static final String JOIN_KEY = "multiplayer.player.joined";
    private static final String LEAVE_KEY = "multiplayer.player.left";

    private final ChatPlusBridge chatPlusBridge = new ChatPlusBridge();

    public ChatPlusReconnectCompactor() {
        super(Categories.Misc, "chatplus-reconnect-compactor", "Compacts consecutive leave + join messages into reconnect messages.");
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (mc.inGameHud == null) return;

        ParsedServerMessage parsed = parseIncomingServerMessage(event.getMessage(), event.getIndicator());
        if (parsed == null) return;

        if (parsed.type() == MessageType.JOIN) {
            handleJoin(event, parsed.player());
        } else {
            handleLeft(event, parsed.player());
        }
    }

    private void handleJoin(ReceiveMessageEvent event, String player) {
        int[] reconnectCount = { 1 };
        boolean[] activeSession = { false };
        boolean changed = false;

        if (ChatPlusUtils.IS_AVAILABLE && chatPlusBridge.isChatPlusEnabled()) {
            changed |= chatPlusBridge.compactJoin(player, reconnectCount, activeSession);
        }
        changed |= compactVanillaJoin(player, reconnectCount, activeSession);

        if (!changed) return;
        if (activeSession[0]) event.setMessage(buildActiveSessionMessage(player, reconnectCount[0]));
        else event.setMessage(buildReconnectMessage(player, reconnectCount[0]));
    }

    private void handleLeft(ReceiveMessageEvent event, String player) {
        int[] reconnectCount = { 0 };
        boolean[] joinedOnly = { false };
        boolean[] reconnectedOnly = { false };
        boolean changed = false;

        if (ChatPlusUtils.IS_AVAILABLE && chatPlusBridge.isChatPlusEnabled()) {
            changed |= chatPlusBridge.compactSessionLeft(player, reconnectCount, joinedOnly, reconnectedOnly);
        }
        changed |= compactVanillaSessionLeft(player, reconnectCount, joinedOnly, reconnectedOnly);

        if (!changed) return;
        if (joinedOnly[0]) event.setMessage(buildJoinedLeftMessage(player));
        else if (reconnectedOnly[0]) event.setMessage(buildReconnectedLeftMessage(player, reconnectCount[0]));
        else event.setMessage(buildSessionLeftMessage(player, reconnectCount[0]));
    }

    private boolean compactVanillaJoin(String player, int[] reconnectCount, boolean[] activeSession) {
        ChatHudAccessor chatHud = (ChatHudAccessor) mc.inGameHud.getChatHud();
        List<ChatHudLine> messages = chatHud.meteor$getMessages();
        List<ChatHudLine.Visible> visibleMessages = chatHud.meteor$getVisibleMessages();
        if (messages.isEmpty()) return false;

        ReconnectedMessage topReconnectedLeft = extractReconnectedLeft(messages.getFirst().content().getString());
        if (topReconnectedLeft != null && player.equals(topReconnectedLeft.player())) {
            reconnectCount[0] = Math.max(reconnectCount[0], topReconnectedLeft.count() + 1);
            removeMostRecentVanillaMessage(messages, visibleMessages);
            return true;
        }

        ReconnectedMessage topSession = extractSessionLeft(messages.getFirst().content().getString());
        if (topSession == null) topSession = extractJoinedLeft(messages.getFirst().content().getString());
        if (topSession == null) topSession = extractActiveSession(messages.getFirst().content().getString());

        if (topSession != null && player.equals(topSession.player())) {
            reconnectCount[0] = Math.max(reconnectCount[0], topSession.count() + 1);
            activeSession[0] = true;
            removeMostRecentVanillaMessage(messages, visibleMessages);
            return true;
        }

        String leftPlayer = extractLeftPlayer(messages.getFirst().content());
        if (!player.equals(leftPlayer)) return false;

        removeMostRecentVanillaMessage(messages, visibleMessages);

        if (!messages.isEmpty()) {
            String previous = messages.getFirst().content().getString();
            ReconnectedMessage reconnected = extractReconnected(previous);
            if (reconnected != null && player.equals(reconnected.player())) {
                reconnectCount[0] = Math.max(reconnectCount[0], reconnected.count() + 1);
                removeMostRecentVanillaMessage(messages, visibleMessages);
            } else {
                ReconnectedMessage reconnectLeft = extractReconnectedLeft(previous);
                if (reconnectLeft != null && player.equals(reconnectLeft.player())) {
                    reconnectCount[0] = Math.max(reconnectCount[0], reconnectLeft.count() + 1);
                    removeMostRecentVanillaMessage(messages, visibleMessages);
                    return true;
                }

                ReconnectedMessage session = extractSessionLeft(previous);
                if (session == null) session = extractJoinedLeft(previous);
                if (session == null) session = extractActiveSession(previous);

                if (session != null && player.equals(session.player())) {
                    reconnectCount[0] = Math.max(reconnectCount[0], session.count() + 1);
                    activeSession[0] = true;
                    removeMostRecentVanillaMessage(messages, visibleMessages);
                }
            }
        }

        return true;
    }

    private boolean compactVanillaSessionLeft(String player, int[] reconnectCount, boolean[] joinedOnly, boolean[] reconnectedOnly) {
        ChatHudAccessor chatHud = (ChatHudAccessor) mc.inGameHud.getChatHud();
        List<ChatHudLine> messages = chatHud.meteor$getMessages();
        List<ChatHudLine.Visible> visibleMessages = chatHud.meteor$getVisibleMessages();
        if (messages.isEmpty()) return false;

        String joinedTop = extractJoinedPlayer(messages.getFirst().content());
        if (player.equals(joinedTop)) {
            joinedOnly[0] = true;
            removeMostRecentVanillaMessage(messages, visibleMessages);
            return true;
        }

        ReconnectedMessage activeSession = extractActiveSession(messages.getFirst().content().getString());
        if (activeSession != null && player.equals(activeSession.player())) {
            reconnectCount[0] = Math.max(reconnectCount[0], activeSession.count());
            removeMostRecentVanillaMessage(messages, visibleMessages);
            return true;
        }

        ReconnectedMessage topReconnected = extractReconnected(messages.getFirst().content().getString());
        if (topReconnected != null && player.equals(topReconnected.player())) {
            reconnectCount[0] = Math.max(reconnectCount[0], topReconnected.count());
            reconnectedOnly[0] = true;
            removeMostRecentVanillaMessage(messages, visibleMessages);
            return true;
        }

        if (messages.size() < 2) return false;

        ReconnectedMessage reconnected = extractReconnected(messages.getFirst().content().getString());
        if (reconnected == null || !player.equals(reconnected.player())) return false;

        String joinedPlayer = extractJoinedPlayer(messages.get(1).content());
        if (!player.equals(joinedPlayer)) return false;

        reconnectCount[0] = Math.max(reconnectCount[0], reconnected.count());
        removeMostRecentVanillaMessage(messages, visibleMessages);
        removeMostRecentVanillaMessage(messages, visibleMessages);
        return true;
    }

    private void removeMostRecentVanillaMessage(List<ChatHudLine> messages, List<ChatHudLine.Visible> visibleMessages) {
        if (!messages.isEmpty()) messages.removeFirst();

        while (!visibleMessages.isEmpty()) {
            ChatHudLine.Visible line = visibleMessages.removeFirst();
            if (((IChatHudLineVisible) (Object) line).meteor$isStartOfEntry()) break;
        }
    }

    private Text buildReconnectMessage(String player, int count) {
        if (count <= 1) return Text.literal(player + " reconnected").formatted(Formatting.GRAY);
        return Text.literal(player + " reconnected (" + count + ")").formatted(Formatting.GRAY);
    }

    private Text buildActiveSessionMessage(String player, int reconnectCount) {
        String reconnectPart = reconnectCount <= 1 ? "reconnected" : "reconnected (" + reconnectCount + ")";
        return Text.literal(player + " joined, " + reconnectPart).formatted(Formatting.GRAY);
    }

    private Text buildJoinedLeftMessage(String player) {
        return Text.literal(player + " joined and left").formatted(Formatting.GRAY);
    }

    private Text buildReconnectedLeftMessage(String player, int reconnectCount) {
        String reconnectPart = reconnectCount <= 1 ? "reconnected" : "reconnected (" + reconnectCount + ")";
        return Text.literal(player + " " + reconnectPart + " and left").formatted(Formatting.GRAY);
    }

    private Text buildSessionLeftMessage(String player, int reconnectCount) {
        String reconnectPart = reconnectCount <= 1 ? "reconnected" : "reconnected (" + reconnectCount + ")";
        return Text.literal(player + " joined, " + reconnectPart + ", and left").formatted(Formatting.GRAY);
    }

    @Nullable
    private ParsedServerMessage parseIncomingServerMessage(Text text, @Nullable MessageIndicator indicator) {
        String joined = extractPlayerFromTranslatable(text, JOIN_KEY);
        if (joined != null) return new ParsedServerMessage(MessageType.JOIN, joined);

        String left = extractPlayerFromTranslatable(text, LEAVE_KEY);
        if (left != null) return new ParsedServerMessage(MessageType.LEFT, left);

        // Some mod stacks strip/replace indicator metadata; keep strict text matching as fallback.
        String raw = text.getString();
        if (!looksLikeJoinOrLeave(raw)) return null;

        String normalized = normalize(raw);
        joined = extractPlayerNormalized(JOIN_PATTERN, normalized);
        if (joined != null) return new ParsedServerMessage(MessageType.JOIN, joined);

        left = extractPlayerNormalized(LEAVE_PATTERN, normalized);
        if (left != null) return new ParsedServerMessage(MessageType.LEFT, left);

        return null;
    }

    private boolean looksLikeJoinOrLeave(String message) {
        if (message == null || message.isBlank()) return false;

        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("joined") || lower.contains("left");
    }

    @Nullable
    private String extractPlayer(Pattern pattern, String message) {
        Matcher matcher = pattern.matcher(normalize(message));
        if (!matcher.matches()) return null;
        return matcher.group(1);
    }

    @Nullable
    private String extractPlayerNormalized(Pattern pattern, String normalizedMessage) {
        Matcher matcher = pattern.matcher(normalizedMessage);
        if (!matcher.matches()) return null;
        return matcher.group(1);
    }

    @Nullable
    private ReconnectedMessage extractReconnected(String message) {
        Matcher matcher = RECONNECTED_PATTERN.matcher(normalize(message));
        if (!matcher.matches()) return null;

        int count = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
        return new ReconnectedMessage(matcher.group(1), Math.max(1, count));
    }

    @Nullable
    private ReconnectedMessage extractActiveSession(String message) {
        Matcher matcher = ACTIVE_SESSION_PATTERN.matcher(normalize(message));
        if (!matcher.matches()) return null;

        int count = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
        return new ReconnectedMessage(matcher.group(1), Math.max(1, count));
    }

    @Nullable
    private ReconnectedMessage extractJoinedLeft(String message) {
        Matcher matcher = JOINED_LEFT_PATTERN.matcher(normalize(message));
        if (!matcher.matches()) return null;
        return new ReconnectedMessage(matcher.group(1), 0);
    }

    @Nullable
    private ReconnectedMessage extractReconnectedLeft(String message) {
        Matcher matcher = RECONNECTED_LEFT_PATTERN.matcher(normalize(message));
        if (!matcher.matches()) return null;
        int count = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
        return new ReconnectedMessage(matcher.group(1), Math.max(1, count));
    }

    @Nullable
    private ReconnectedMessage extractSessionLeft(String message) {
        Matcher matcher = SESSION_LEFT_PATTERN.matcher(normalize(message));
        if (!matcher.matches()) return null;

        int count = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
        return new ReconnectedMessage(matcher.group(1), Math.max(1, count));
    }

    private String normalize(String message) {
        String normalized = TIMESTAMP_PATTERN.matcher(message.trim()).replaceFirst("");
        while (true) {
            Matcher matcher = DECORATION_PREFIX_PATTERN.matcher(normalized);
            if (!matcher.find()) break;
            normalized = normalized.substring(matcher.end()).trim();
        }
        return normalized;
    }

    @Nullable
    private String extractJoinedPlayer(Text text) {
        String player = extractPlayerFromTranslatable(text, JOIN_KEY);
        if (player != null) return player;
        return extractPlayer(JOIN_PATTERN, text.getString());
    }

    @Nullable
    private String extractLeftPlayer(Text text) {
        String player = extractPlayerFromTranslatable(text, LEAVE_KEY);
        if (player != null) return player;
        return extractPlayer(LEAVE_PATTERN, text.getString());
    }

    @Nullable
    private String extractPlayerFromTranslatable(Text text, String key) {
        if (text.getContent() instanceof TranslatableTextContent content && key.equals(content.getKey())) {
            Object[] args = content.getArgs();
            if (args.length > 0) {
                Object arg = args[0];
                if (arg instanceof Text playerText) return playerText.getString();
                return String.valueOf(arg);
            }
        }

        for (Text sibling : text.getSiblings()) {
            String player = extractPlayerFromTranslatable(sibling, key);
            if (player != null) return player;
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private final class ChatPlusBridge {
        private boolean unavailable;
        private boolean chatPlusEnabledUnavailable;
        private Object chatPlus;
        private Method chatPlusIsEnabled;

        private Object manager;
        private Method getGlobalSortedTabs;

        private Class<?> tabClass;
        private Method tabGetMessages;
        private Method tabGetDisplayedMessages;
        private Method tabGetUnfilteredDisplayedMessages;

        private Class<?> guiMessageClass;
        private Method guiMessageGetter;
        private Method guiMessageTextGetter;

        private Class<?> lineClass;
        private Method lineGetLinkedMessage;

        boolean isChatPlusEnabled() {
            if (chatPlusEnabledUnavailable) return false;

            try {
                if (chatPlus == null || chatPlusIsEnabled == null) {
                    Class<?> chatPlusClass = Class.forName("com.ebicep.chatplus.ChatPlus");
                    chatPlus = chatPlusClass.getField("INSTANCE").get(null);
                    chatPlusIsEnabled = chatPlusClass.getMethod("isEnabled");
                }

                Object result = chatPlusIsEnabled.invoke(chatPlus);
                return result instanceof Boolean enabled && enabled;
            } catch (ReflectiveOperationException e) {
                chatPlusEnabledUnavailable = true;
                return false;
            }
        }

        boolean compactJoin(String player, int[] reconnectCount, boolean[] activeSession) {
            if (!init()) return false;

            try {
                List<?> tabs = (List<?>) getGlobalSortedTabs.invoke(manager);
                boolean changed = false;

                for (Object tab : tabs) {
                    if (tab == null) continue;
                    changed |= compactJoinTab(tab, player, reconnectCount, activeSession);
                }

                return changed;
            } catch (ReflectiveOperationException e) {
                unavailable = true;
                return false;
            }
        }

        boolean compactSessionLeft(String player, int[] reconnectCount, boolean[] joinedOnly, boolean[] reconnectedOnly) {
            if (!init()) return false;

            try {
                List<?> tabs = (List<?>) getGlobalSortedTabs.invoke(manager);
                boolean changed = false;

                for (Object tab : tabs) {
                    if (tab == null) continue;
                    changed |= compactSessionLeftTab(tab, player, reconnectCount, joinedOnly, reconnectedOnly);
                }

                return changed;
            } catch (ReflectiveOperationException e) {
                unavailable = true;
                return false;
            }
        }

        private boolean compactJoinTab(Object tab, String player, int[] reconnectCount, boolean[] activeSession) throws ReflectiveOperationException {
            if (!resolveTab(tab.getClass())) return false;

            List<Object> messages = (List<Object>) tabGetMessages.invoke(tab);
            List<Object> displayed = (List<Object>) tabGetDisplayedMessages.invoke(tab);
            List<Object> unfiltered = (List<Object>) tabGetUnfilteredDisplayedMessages.invoke(tab);
            if (messages.isEmpty()) return false;

            Object last = messages.get(messages.size() - 1);
            ReconnectedMessage topReconnectLeft = extractReconnectedLeftFromChatPlusMessage(last);
            if (topReconnectLeft != null && player.equals(topReconnectLeft.player())) {
                reconnectCount[0] = Math.max(reconnectCount[0], topReconnectLeft.count() + 1);
                removeLinkedMessage(messages, displayed, unfiltered, last);
                return true;
            }

            ReconnectedMessage topSession = extractSessionLeftFromChatPlusMessage(last);
            if (topSession == null) topSession = extractJoinedLeftFromChatPlusMessage(last);
            if (topSession == null) topSession = extractActiveSessionFromChatPlusMessage(last);

            if (topSession != null && player.equals(topSession.player())) {
                reconnectCount[0] = Math.max(reconnectCount[0], topSession.count() + 1);
                activeSession[0] = true;
                removeLinkedMessage(messages, displayed, unfiltered, last);
                return true;
            }

            String leftPlayer = extractPlayerFromChatPlusMessage(last, MessageType.LEFT);
            if (!player.equals(leftPlayer)) return false;

            removeLinkedMessage(messages, displayed, unfiltered, last);

            if (!messages.isEmpty()) {
                Object previous = messages.get(messages.size() - 1);
                ReconnectedMessage reconnected = extractReconnectedFromChatPlusMessage(previous);

                if (reconnected != null && player.equals(reconnected.player())) {
                    reconnectCount[0] = Math.max(reconnectCount[0], reconnected.count() + 1);
                    removeLinkedMessage(messages, displayed, unfiltered, previous);
                } else {
                    ReconnectedMessage reconnectLeft = extractReconnectedLeftFromChatPlusMessage(previous);
                    if (reconnectLeft != null && player.equals(reconnectLeft.player())) {
                        reconnectCount[0] = Math.max(reconnectCount[0], reconnectLeft.count() + 1);
                        removeLinkedMessage(messages, displayed, unfiltered, previous);
                        return true;
                    }

                    ReconnectedMessage session = extractSessionLeftFromChatPlusMessage(previous);
                    if (session == null) session = extractJoinedLeftFromChatPlusMessage(previous);
                    if (session == null) session = extractActiveSessionFromChatPlusMessage(previous);

                    if (session != null && player.equals(session.player())) {
                        reconnectCount[0] = Math.max(reconnectCount[0], session.count() + 1);
                        activeSession[0] = true;
                        removeLinkedMessage(messages, displayed, unfiltered, previous);
                    }
                }
            }

            return true;
        }

        private boolean compactSessionLeftTab(Object tab, String player, int[] reconnectCount, boolean[] joinedOnly, boolean[] reconnectedOnly) throws ReflectiveOperationException {
            if (!resolveTab(tab.getClass())) return false;

            List<Object> messages = (List<Object>) tabGetMessages.invoke(tab);
            List<Object> displayed = (List<Object>) tabGetDisplayedMessages.invoke(tab);
            List<Object> unfiltered = (List<Object>) tabGetUnfilteredDisplayedMessages.invoke(tab);
            if (messages.isEmpty()) return false;

            Object active = messages.get(messages.size() - 1);
            String joinedTop = extractPlayerFromChatPlusMessage(active, MessageType.JOIN);
            if (player.equals(joinedTop)) {
                joinedOnly[0] = true;
                removeLinkedMessage(messages, displayed, unfiltered, active);
                return true;
            }

            ReconnectedMessage activeSession = extractActiveSessionFromChatPlusMessage(active);
            if (activeSession != null && player.equals(activeSession.player())) {
                reconnectCount[0] = Math.max(reconnectCount[0], activeSession.count());
                removeLinkedMessage(messages, displayed, unfiltered, active);
                return true;
            }

            ReconnectedMessage topReconnected = extractReconnectedFromChatPlusMessage(active);
            if (topReconnected != null && player.equals(topReconnected.player())) {
                reconnectCount[0] = Math.max(reconnectCount[0], topReconnected.count());
                reconnectedOnly[0] = true;
                removeLinkedMessage(messages, displayed, unfiltered, active);
                return true;
            }

            if (messages.size() < 2) return false;

            Object last = messages.get(messages.size() - 1);
            ReconnectedMessage reconnected = extractReconnectedFromChatPlusMessage(last);
            if (reconnected == null || !player.equals(reconnected.player())) return false;

            Object previous = messages.get(messages.size() - 2);
            String joinedPlayer = extractPlayerFromChatPlusMessage(previous, MessageType.JOIN);
            if (!player.equals(joinedPlayer)) return false;

            reconnectCount[0] = Math.max(reconnectCount[0], reconnected.count());
            removeLinkedMessage(messages, displayed, unfiltered, last);
            removeLinkedMessage(messages, displayed, unfiltered, previous);
            return true;
        }

        private void removeLinkedMessage(List<Object> messages, List<Object> displayed, List<Object> unfiltered, Object linkedMessage) throws ReflectiveOperationException {
            messages.remove(linkedMessage);
            removeLinkedLines(displayed, linkedMessage);
            removeLinkedLines(unfiltered, linkedMessage);
        }

        private void removeLinkedLines(List<Object> lines, Object linkedMessage) throws ReflectiveOperationException {
            for (int i = lines.size() - 1; i >= 0; i--) {
                Object line = lines.get(i);
                if (line == null) continue;

                if (lineClass != line.getClass()) {
                    lineClass = line.getClass();
                    lineGetLinkedMessage = lineClass.getMethod("getLinkedMessage");
                }

                Object linked = lineGetLinkedMessage.invoke(line);
                if (linked == linkedMessage) lines.remove(i);
            }
        }

        @Nullable
        private String extractPlayerFromChatPlusMessage(Object chatPlusMessage, MessageType type) throws ReflectiveOperationException {
            Text text = getChatPlusMessageText(chatPlusMessage);
            if (text == null) return null;
            return type == MessageType.JOIN ? extractJoinedPlayer(text) : extractLeftPlayer(text);
        }

        @Nullable
        private ReconnectedMessage extractReconnectedFromChatPlusMessage(Object chatPlusMessage) throws ReflectiveOperationException {
            Text text = getChatPlusMessageText(chatPlusMessage);
            if (text == null) return null;
            return extractReconnected(text.getString());
        }

        @Nullable
        private ReconnectedMessage extractActiveSessionFromChatPlusMessage(Object chatPlusMessage) throws ReflectiveOperationException {
            Text text = getChatPlusMessageText(chatPlusMessage);
            if (text == null) return null;
            return extractActiveSession(text.getString());
        }

        @Nullable
        private ReconnectedMessage extractSessionLeftFromChatPlusMessage(Object chatPlusMessage) throws ReflectiveOperationException {
            Text text = getChatPlusMessageText(chatPlusMessage);
            if (text == null) return null;
            return extractSessionLeft(text.getString());
        }

        @Nullable
        private ReconnectedMessage extractJoinedLeftFromChatPlusMessage(Object chatPlusMessage) throws ReflectiveOperationException {
            Text text = getChatPlusMessageText(chatPlusMessage);
            if (text == null) return null;
            return extractJoinedLeft(text.getString());
        }

        @Nullable
        private ReconnectedMessage extractReconnectedLeftFromChatPlusMessage(Object chatPlusMessage) throws ReflectiveOperationException {
            Text text = getChatPlusMessageText(chatPlusMessage);
            if (text == null) return null;
            return extractReconnectedLeft(text.getString());
        }

        @Nullable
        private Text getChatPlusMessageText(Object chatPlusMessage) throws ReflectiveOperationException {
            if (chatPlusMessage == null) return null;

            Class<?> messageClass = chatPlusMessage.getClass();
            if (guiMessageClass != messageClass) {
                guiMessageClass = messageClass;
                guiMessageGetter = guiMessageClass.getMethod("getGuiMessage");
                guiMessageTextGetter = null;
            }

            Object guiMessage = guiMessageGetter.invoke(chatPlusMessage);
            return extractGuiMessageText(guiMessage);
        }

        @Nullable
        private Text extractGuiMessageText(@Nullable Object guiMessage) throws ReflectiveOperationException {
            if (guiMessage == null) return null;
            if (guiMessage instanceof ChatHudLine line) return line.content();

            if (guiMessageTextGetter == null || guiMessageTextGetter.getDeclaringClass() != guiMessage.getClass()) {
                guiMessageTextGetter = findNoArgMethodReturning(guiMessage.getClass(), Text.class);
            }

            if (guiMessageTextGetter == null) return null;
            Object value = guiMessageTextGetter.invoke(guiMessage);
            return value instanceof Text text ? text : null;
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

        private boolean init() {
            if (unavailable) return false;
            if (manager != null && getGlobalSortedTabs != null) return true;

            try {
                Class<?> chatManagerClass = Class.forName("com.ebicep.chatplus.hud.ChatManager");
                manager = chatManagerClass.getField("INSTANCE").get(null);
                getGlobalSortedTabs = chatManagerClass.getMethod("getGlobalSortedTabs");
                return true;
            } catch (ReflectiveOperationException e) {
                unavailable = true;
                return false;
            }
        }

        private boolean resolveTab(Class<?> candidate) {
            if (tabClass == candidate) return true;

            try {
                tabClass = candidate;
                tabGetMessages = tabClass.getMethod("getMessages");
                tabGetDisplayedMessages = tabClass.getMethod("getDisplayedMessages");
                tabGetUnfilteredDisplayedMessages = tabClass.getMethod("getUnfilteredDisplayedMessages");
                return true;
            } catch (ReflectiveOperationException e) {
                unavailable = true;
                return false;
            }
        }
    }

    private enum MessageType {
        JOIN,
        LEFT
    }

    private record ParsedServerMessage(MessageType type, String player) {}
    private record ReconnectedMessage(String player, int count) {}
}
