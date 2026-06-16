package com.unitedlink.unitedlink;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

public class UnitedLinkLoginHandler {

    private static final String BOT_API_URL = System.getenv("UNITEDLINK_API_URL") != null
        ? System.getenv("UNITEDLINK_API_URL")
        : "https://minecraft-rank-bot.onrender.com";

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        String uuid = player.getUUID().toString();
        String username = player.getName().getString();

        CompletableFuture.runAsync(() -> {
            try {
                String body = "{\"minecraft_uuid\":\"" + uuid + "\",\"minecraft_username\":\"" + username + "\"}";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BOT_API_URL + "/updateusername"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                UnitedLinkMod.LOGGER.info("[UnitedLink] Updated username for {} ({})", username, uuid);
            } catch (Exception e) {
                UnitedLinkMod.LOGGER.warn("[UnitedLink] Could not update username: {}", e.getMessage());
            }
        });
    }
}
