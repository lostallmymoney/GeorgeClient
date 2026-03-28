/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.screens;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.meteor.ActiveModulesChangedEvent;
import meteordevelopment.meteorclient.events.meteor.ModuleBindChangedEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WKeybind;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WFavorite;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.NbtUtils;
import meteordevelopment.meteorclient.utils.render.prompts.OkPrompt;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.nbt.NbtCompound;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static meteordevelopment.meteorclient.utils.Utils.getWindowWidth;

public class ModuleScreen extends WindowScreen {
    private final Module module;

    private WContainer settingsContainer;
    private WKeybind keybind;
    private WCheckbox active;
    private final Map<Module.ExtraBindSettings, Boolean> extraBindVisibility = new HashMap<>();

    public ModuleScreen(GuiTheme theme, Module module) {
        super(theme, theme.favorite(module.favorite), module.title);
        ((WFavorite) window.icon).action = () -> module.favorite = ((WFavorite) window.icon).checked;

        this.module = module;
    }

    @Override
    public void initWidgets() {
        extraBindVisibility.clear();

        // Description
        add(theme.label(module.description, getWindowWidth() / 2.0));

        if (module.addon != null && module.addon != MeteorClient.ADDON) {
            WHorizontalList addon = add(theme.horizontalList()).expandX().widget();
            addon.add(theme.label("From: ").color(theme.textSecondaryColor())).widget();
            addon.add(theme.label(module.addon.name).color(module.addon.color)).widget();
        }

        // Settings
        if (!module.settings.groups.isEmpty()) {
            settingsContainer = add(theme.verticalList()).expandX().widget();
            settingsContainer.add(theme.settings(module.settings)).expandX();
        }

        // Custom widget
        WWidget widget = module.getWidget(theme);

        if (widget != null) {
            add(theme.horizontalSeparator()).expandX();
            Cell<WWidget> cell = add(widget);
            if (widget instanceof WContainer) cell.expandX();
        }

        // Bind
        WSection section = add(theme.section("Bind", true)).expandX().widget();

        if (module.showPrimaryBindSettings()) {
            // Keybind
            WHorizontalList bind = section.add(theme.horizontalList()).expandX().widget();

            bind.add(theme.label("Bind: "));
            keybind = bind.add(theme.keybind(module.keybind)).expandX().widget();
            keybind.actionOnSet = () -> Modules.get().setModuleToBind(module);

            WButton reset = bind.add(theme.button(GuiRenderer.RESET)).expandCellX().right().widget();
            reset.action = keybind::resetBind;
            reset.tooltip = "Reset";

            // Command bind
            WHorizontalList cmd = section.add(theme.horizontalList()).expandX().widget();
            cmd.add(theme.label("Command Bind: "));
            WTextBox cmdBox = cmd.add(theme.textBox(module.bindCommand)).expandX().widget();
            Runnable applyMainBindCommand = () -> module.bindCommand = normalizeBindCommand(cmdBox.get());
            cmdBox.action = applyMainBindCommand;
            cmdBox.actionOnUnfocused = applyMainBindCommand;

            // Toggle on bind release
            WHorizontalList tobr = section.add(theme.horizontalList()).widget();

            tobr.add(theme.label("Toggle on bind release: "));
            WCheckbox tobrC = tobr.add(theme.checkbox(module.toggleOnBindRelease)).widget();
            tobrC.action = () -> module.toggleOnBindRelease = tobrC.checked;

            // Chat feedback
            WHorizontalList cf = section.add(theme.horizontalList()).widget();

            cf.add(theme.label("Chat Feedback: "));
            WCheckbox cfC = cf.add(theme.checkbox(module.chatFeedback)).widget();
            cfC.action = () -> module.chatFeedback = cfC.checked;
        }

        // Additional bind-related entries explicitly registered by the module.
        for (Module.ExtraBindSettings extraBind : module.getExtraBinds()) {
            boolean visible = extraBind.isVisible();
            extraBindVisibility.put(extraBind, visible);
            if (!visible) continue;

            Setting<Keybind> bindSetting = extraBind.bind();
            Setting<Boolean> feedbackSetting = extraBind.chatFeedback();
            Setting<String> commandSetting = extraBind.command();

            WHorizontalList settingBind = section.add(theme.horizontalList()).expandX().widget();
            settingBind.add(theme.label(bindSetting.title + ": "));

            WKeybind settingKeybind = settingBind.add(theme.keybind(bindSetting.get(), bindSetting.getDefaultValue())).expandX().widget();
            settingKeybind.action = bindSetting::onChanged;
            if (bindSetting instanceof KeybindSetting keybindSetting) keybindSetting.widget = settingKeybind;

            WButton settingReset = settingBind.add(theme.button(GuiRenderer.RESET)).expandCellX().right().widget();
            settingReset.action = settingKeybind::resetBind;
            settingReset.tooltip = "Reset";

            WHorizontalList settingCommand = section.add(theme.horizontalList()).expandX().widget();
            settingCommand.add(theme.label(commandSetting.title + ": "));

            WTextBox settingTextBox = settingCommand.add(theme.textBox(commandSetting.get())).expandX().widget();
            Runnable applyExtraBindCommand = () -> commandSetting.set(normalizeBindCommand(settingTextBox.get()));
            settingTextBox.action = applyExtraBindCommand;
            settingTextBox.actionOnUnfocused = applyExtraBindCommand;

            WHorizontalList settingFeedback = section.add(theme.horizontalList()).widget();
            settingFeedback.add(theme.label(feedbackSetting.title + ": "));

            WCheckbox settingCheckbox = settingFeedback.add(theme.checkbox(feedbackSetting.get())).widget();
            settingCheckbox.action = () -> feedbackSetting.set(settingCheckbox.checked);
        }

        add(theme.horizontalSeparator()).expandX();

        // Bottom
        WHorizontalList bottom = add(theme.horizontalList()).expandX().widget();

        // Active
        if (module.showModuleActiveSetting()) {
            bottom.add(theme.label("Active: "));
            active = bottom.add(theme.checkbox(module.isActive())).expandCellX().widget();
            active.action = () -> {
                if (module.isActive() != active.checked) module.toggle();
            };
        }

        // Config sharing
        WHorizontalList sharing = bottom.add(theme.horizontalList()).right().widget();
        WButton copy = sharing.add(theme.button(GuiRenderer.COPY)).widget();
        copy.action = () -> {
            if (toClipboard()) {
                OkPrompt.create()
                    .title("Module copied!")
                    .message("The settings for this module are now in your clipboard.")
                    .message("You can also copy settings using Ctrl+C.")
                    .message("Settings can be imported using Ctrl+V or the paste button.")
                    .id("config-sharing-guide")
                    .show();
            }
        };
        copy.tooltip = "Copy config";

        WButton paste = sharing.add(theme.button(GuiRenderer.PASTE)).widget();
        paste.action = this::fromClipboard;
        paste.tooltip = "Paste config";
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !Modules.get().isBinding();
    }

    private static String normalizeBindCommand(String raw) {
        return raw == null ? "" : raw.trim();
    }

    @Override
    public void tick() {
        super.tick();

        module.settings.tick(settingsContainer, theme);

        for (Module.ExtraBindSettings extraBind : module.getExtraBinds()) {
            boolean visible = extraBind.isVisible();
            Boolean last = extraBindVisibility.get(extraBind);

            if (last == null) {
                extraBindVisibility.put(extraBind, visible);
                continue;
            }

            if (visible != last) {
                reload();
                return;
            }
        }
    }

    @EventHandler
    private void onModuleBindChanged(ModuleBindChangedEvent event) {
        if (keybind != null) keybind.reset();
    }

    @EventHandler
    private void onActiveModulesChanged(ActiveModulesChangedEvent event) {
        if (this.active != null) this.active.checked = module.isActive();
    }

    @Override
    public boolean toClipboard() {
        NbtCompound tag = new NbtCompound();

        tag.putString("name", module.name);

        NbtCompound settingsTag = module.settings.toTag();
        if (!settingsTag.isEmpty()) tag.put("settings", settingsTag);

        return NbtUtils.toClipboard(tag);
    }

    @Override
    public boolean fromClipboard() {
        NbtCompound tag = NbtUtils.fromClipboard();
        if (tag == null) return false;
        if (!tag.getString("name", "").equals(module.name)) return false;

        Optional<NbtCompound> settings = tag.getCompound("settings");

        if (settings.isPresent()) module.settings.fromTag(settings.get());
        else module.settings.reset();

        if (parent instanceof WidgetScreen p) p.reload();
        reload();

        return true;
    }
}
