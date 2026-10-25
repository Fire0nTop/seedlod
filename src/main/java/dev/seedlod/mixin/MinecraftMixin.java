package dev.seedlod.mixin;

import dev.seedlod.GenerationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    // Voxy shuts its instance down at the end of disconnect() and waits for every user of its worlds to let go.
    // Our job holds a world reference, and the render thread is stuck in disconnect() so our tick never runs:
    // stop here, before Voxy starts waiting.
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void seedlod$stopBeforeVoxyShutdown(CallbackInfo ci) {
        GenerationManager.INSTANCE.stop();
    }
}
