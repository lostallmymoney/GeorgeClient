/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.compat.xaeroplus;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.IElytraProcess;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

public final class BaritoneElytraPathAccess {
    private static final String ELYTRA_BEHAVIOR_CLASS = "baritone.process.elytra.ElytraBehavior";
    private static final String PATH_MANAGER_CLASS = "baritone.process.elytra.ElytraBehavior$PathManager";
    private static final String NETHER_PATH_CLASS = "baritone.process.elytra.NetherPath";

    private static boolean checkedAccessibility;
    private static boolean isAccessible;

    private BaritoneElytraPathAccess() {}

    public static boolean isAccessible() {
        if (!checkedAccessibility) {
            isAccessible = probeAccessibility();
            checkedAccessibility = true;
        }

        return isAccessible;
    }

    public static List<BlockPos> getElytraPath() {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone == null) return Collections.emptyList();

            IElytraProcess process = baritone.getElytraProcess();
            if (process == null || process.currentDestination() == null) return Collections.emptyList();

            Accessors accessors = resolveAccessors(process.getClass());
            if (accessors == null) return Collections.emptyList();

            Object behavior = accessors.behaviorField.get(process);
            if (behavior == null) return Collections.emptyList();

            Object pathManager = accessors.pathManagerField.get(behavior);
            if (pathManager == null) return Collections.emptyList();

            Object path = accessors.pathGetter.invoke(pathManager);
            if (!(path instanceof List<?> pathList)) return Collections.emptyList();

            return castToBlockPosList(pathList);
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private static boolean probeAccessibility() {
        try {
            return resolveAccessors(Class.forName("baritone.process.ElytraProcess")) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Accessors resolveAccessors(Class<?> processClass) throws ReflectiveOperationException {
        Class<?> behaviorClass = Class.forName(ELYTRA_BEHAVIOR_CLASS);
        Class<?> pathManagerClass = Class.forName(PATH_MANAGER_CLASS);
        Class<?> netherPathClass = Class.forName(NETHER_PATH_CLASS);

        if (!List.class.isAssignableFrom(netherPathClass)) return null;
        if (netherPathClass.getMethod("get", int.class).getReturnType() == Void.TYPE) return null;
        if (netherPathClass.getMethod("size").getReturnType() != int.class) return null;

        Field behaviorField = findField(processClass, field -> field.getType() == behaviorClass);
        Field pathManagerField = findField(behaviorClass, field -> field.getType() == pathManagerClass);
        Method pathGetter = findMethod(pathManagerClass, method -> method.getParameterCount() == 0 && method.getReturnType() == netherPathClass);

        if (behaviorField == null || pathManagerField == null || pathGetter == null) return null;

        behaviorField.setAccessible(true);
        pathManagerField.setAccessible(true);
        pathGetter.setAccessible(true);

        return new Accessors(behaviorField, pathManagerField, pathGetter);
    }

    private static Field findField(Class<?> owner, Predicate<Field> predicate) {
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (predicate.test(field)) return field;
            }
        }

        return null;
    }

    private static Method findMethod(Class<?> owner, Predicate<Method> predicate) {
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (predicate.test(method)) return method;
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> castToBlockPosList(List<?> pathList) {
        return (List<BlockPos>) (List<?>) pathList;
    }

    private record Accessors(Field behaviorField, Field pathManagerField, Method pathGetter) {}
}
