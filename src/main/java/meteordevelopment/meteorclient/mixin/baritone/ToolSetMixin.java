/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin.baritone;

import baritone.api.BaritoneAPI;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.NewAutoTool;
import net.minecraft.block.Block;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "baritone.utils.ToolSet", remap = false)
public abstract class ToolSetMixin {
    @Inject(method = "a", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    @Dynamic("The published Baritone artifact on Meteor's classpath keeps the intermediary ToolSet method name.")
    private void meteor$allowAutoToolInventoryDuringPathingObf(Block block, boolean preferSilkTouch, boolean pathingCalculation, CallbackInfoReturnable<Integer> cir) {
        meteor$allowAutoToolInventoryDuringPathing(block, preferSilkTouch, pathingCalculation, cir);
    }

    private static void meteor$allowAutoToolInventoryDuringPathing(Block block, boolean preferSilkTouch, boolean pathingCalculation, CallbackInfoReturnable<Integer> cir) {
        if (!pathingCalculation) return;
        if (BaritoneAPI.getSettings().allowInventory.value) return;
        if (BaritoneAPI.getSettings().autoTool.value) return;

        NewAutoTool autoTool = Modules.get().get(NewAutoTool.class);
        if (autoTool == null || !autoTool.shouldExposeInventoryToolsToBaritone()) return;

        int bestSlot = autoTool.getBestInventoryToolSlotForBaritone(block.getDefaultState(), preferSilkTouch);
        if (bestSlot >= 0) cir.setReturnValue(bestSlot);
    }
}
