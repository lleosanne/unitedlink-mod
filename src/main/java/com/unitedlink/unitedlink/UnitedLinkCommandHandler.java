package com.unitedlink.unitedlink;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class UnitedLinkCommandHandler {

    private static final String BOT_API_URL = System.getenv("UNITEDLINK_API_URL") != null
        ? System.getenv("UNITEDLINK_API_URL")
        : "https://minecraft-rank-bot-production.up.railway.app";
    
    private static final Set<String> RANK_GROUPS = Set.of("supporter", "explorer", "adventurer");
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    // Check ranks every 5 minutes (6000 ticks)
    private static final int CHECK_INTERVAL = 6000;

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("link")
                .then(Commands.argument("code", StringArgumentType.word())
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        String code = StringArgumentType.getString(ctx, "code").toUpperCase();
                        handleLink(player, code);
                        return 1;
                    })
                )
                .executes(ctx -> {
                    ctx.getSource().getPlayerOrException().sendSystemMessage(
                        Component.literal("§eUsage: §6/link <code>§e - Get your code in Discord with §6/linkminecraft")
                    );
                    return 1;
                })
        );
        UnitedLinkMod.LOGGER.info("[UnitedLink] /link command registered.");
    }

    // ── Check rank on login ──────────────────────────────────────────────────
@SubscribeEvent
public void onPlayerLogin(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
    if (!(event.getEntity() instanceof ServerPlayer player)) return;
    checkRank(player);
}
    // ── Periodic rank check ──────────────────────────────────────────────────
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % CHECK_INTERVAL != 0) return;

        List<ServerPlayer> players = event.getServer().getPlayerList().getPlayers();
        for (ServerPlayer player : players) {
            checkRank(player);
        }
    }

    private void checkRank(ServerPlayer player) {
        String uuid = player.getUUID().toString();

        CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BOT_API_URL + "/check/" + uuid))
                        .GET()
                        .build();
                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (json.get("lp_group").isJsonNull()) {
                        return "REMOVE";
                    }
                    return json.get("lp_group").getAsString();
                }
                return "SKIP";
            } catch (Exception e) {
                return "SKIP";
            }
        }).thenAcceptAsync(result -> {
            if ("SKIP".equals(result)) return;

            try {
                LuckPerms lp = LuckPermsProvider.get();
                User user = lp.getUserManager().getUser(player.getUUID());
                if (user == null) return;

                if ("REMOVE".equals(result)) {
                    // Remove all rank groups
                    boolean hadRank = user.getInheritedGroups(user.getQueryOptions())
                            .stream()
                            .anyMatch(g -> RANK_GROUPS.contains(g.getName().toLowerCase()));

                    if (hadRank) {
                        user.data().clear(node ->
                            node instanceof InheritanceNode &&
                            RANK_GROUPS.contains(((InheritanceNode) node).getGroupName())
                        );
                        lp.getUserManager().saveUser(user);
                        player.sendSystemMessage(Component.literal(
                            "§cYour Patreon rank has been removed because your subscription ended."
                        ));
                        UnitedLinkMod.LOGGER.info("[UnitedLink] Removed rank from player {}", player.getName().getString());
                    }
                }
            } catch (Exception e) {
                UnitedLinkMod.LOGGER.error("[UnitedLink] Error checking rank: {}", e.getMessage());
            }
        }, player.getServer());
    }

    // ── /link <code> handler ─────────────────────────────────────────────────
    private void handleLink(ServerPlayer player, String code) {
        player.sendSystemMessage(Component.literal("§eVerifying your code, please wait..."));

        CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BOT_API_URL + "/verify/" + code))
                        .GET()
                        .build();
                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (json.get("success").getAsBoolean()) {
                        return json;
                    }
                }
                return null;
            } catch (Exception e) {
                UnitedLinkMod.LOGGER.error("[UnitedLink] Error contacting bot API: {}", e.getMessage());
                return null;
            }
        }).thenAcceptAsync(json -> {
            if (json == null) {
                player.sendSystemMessage(Component.literal(
                    "§cInvalid or expired code! Get a new one with §6/linkminecraft §cin Discord."
                ));
                return;
            }

            String lpGroup = json.get("lp_group").getAsString();
            String discordId = json.get("discord_id").getAsString();

            try {
                LuckPerms lp = LuckPermsProvider.get();
                User user = lp.getUserManager().getUser(player.getUUID());
                if (user == null) {
                    player.sendSystemMessage(Component.literal("§cError loading your data. Try again."));
                    return;
                }

                // Remove old rank groups
                user.data().clear(node ->
                    node instanceof InheritanceNode &&
                    RANK_GROUPS.contains(((InheritanceNode) node).getGroupName())
                );

                // Add new group
                user.data().add(InheritanceNode.builder(lpGroup).build());
                lp.getUserManager().saveUser(user);

                // Confirm link to bot so it can track this UUID
                String uuid = player.getUUID().toString();
                CompletableFuture.runAsync(() -> {
                    try {
                        String body = "{\"discord_id\":\"" + discordId + "\",\"minecraft_uuid\":\"" + uuid + "\",\"lp_group\":\"" + lpGroup + "\"}";
                        HttpRequest confirmRequest = HttpRequest.newBuilder()
                                .uri(URI.create(BOT_API_URL + "/confirm"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build();
                        HTTP_CLIENT.send(confirmRequest, HttpResponse.BodyHandlers.ofString());
                    } catch (Exception e) {
                        UnitedLinkMod.LOGGER.warn("[UnitedLink] Could not confirm link to bot: {}", e.getMessage());
                    }
                });

                player.sendSystemMessage(Component.literal(
                    "§aSuccessfully linked! You have been given the §6" + lpGroup + "§a rank! 🎉"
                ));
                UnitedLinkMod.LOGGER.info("[UnitedLink] Player {} linked and given rank: {}", player.getName().getString(), lpGroup);

            } catch (Exception e) {
                UnitedLinkMod.LOGGER.error("[UnitedLink] Error assigning rank: {}", e.getMessage());
                player.sendSystemMessage(Component.literal("§cError assigning rank. Contact an admin."));
            }
        }, player.getServer());
    }
}
