/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.settings;

import java.util.function.Consumer;
import java.util.List;

public class CycleSetting<T extends Enum<?>> extends EnumSetting<T> {
    private final T[] values;

    @SuppressWarnings("unchecked")
    public CycleSetting(String name, String description, T defaultValue, Consumer<T> onChanged, Consumer<Setting<T>> onModuleActivated, IVisible visible) {
        super(name, description, defaultValue, onChanged, onModuleActivated, visible);
        values = (T[]) defaultValue.getDeclaringClass().getEnumConstants();

        // Reuse EnumSetting suggestion list and append cycle command once.
        List<String> suggestions = super.getSuggestions();
        if (!suggestions.contains("cycle")) {
            suggestions.add("cycle");
        }
    }

    @Override
    protected T parseImpl(String str) {
        if (str.equalsIgnoreCase("cycle")) {
            // Special command token: advance to the next enum value instead of parsing a named value.
            int next = (get().ordinal() + 1) % values.length;
            return values[next];
        }

        return super.parseImpl(str);
    }

    public static class Builder<T extends Enum<?>> extends SettingBuilder<Builder<T>, T, CycleSetting<T>> {
        public Builder() {
            super(null);
        }

        @Override
        public CycleSetting<T> build() {
            return new CycleSetting<>(name, description, defaultValue, onChanged, onModuleActivated, visible);
        }
    }
}
