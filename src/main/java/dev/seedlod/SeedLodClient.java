package dev.seedlod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

public class SeedLodClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SeedLodConfig.load();
        var manager = GenerationManager.INSTANCE;
        ClientTickEvents.END_CLIENT_TICK.register(manager::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> manager.stop());
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) ->
                manager.onChunkLoad(Minecraft.getInstance(), level.dimension(), chunk.getPos().x(), chunk.getPos().z()));
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || manager.onGameMessage(message));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> SeedLodCommands.register(dispatcher));
    }
}
