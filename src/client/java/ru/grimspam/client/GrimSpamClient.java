package ru.grimspam.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;

/**
 * Client entry point of the mod.
 *
 * A single key toggles the packet sending mode. The default key is G and it can
 * be changed in the controls settings. When the player leaves or is kicked from
 * the server the mode turns off automatically and the kick reason is shown in
 * chat after the next join.
 */
public final class GrimSpamClient implements ClientModInitializer {
    private static final SpamEngine ENGINE = new SpamEngine();

    private static SpamConfig config;
    private static KeyMapping toggleKey;
    private static boolean enabled;

    /** Reason of the last kick that has not been shown to the player yet. */
    private static String pendingKickReason;

    /** Flag that the disconnect screen was already processed, so it is not read every tick. */
    private static boolean kickCaptured;

    @Override
    public void onInitializeClient() {
        config = SpamConfig.load();

        toggleKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.grimspam.toggle", InputConstants.KEY_G, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            captureKickReason(client);

            // If the connection is gone, turn off the mode and reset all counters at once.
            if (enabled && (client.getConnection() == null || client.player == null)) {
                enabled = false;
                ENGINE.reset();
                return;
            }

            // After the player rejoins, show the reason of the previous kick.
            if (pendingKickReason != null && client.player != null && client.getConnection() != null) {
                String reason = pendingKickReason;
                pendingKickReason = null;
                message(client, "Previous kick reason: " + reason);
            }

            if (toggleKey.consumeClick()) {
                enabled = !enabled;
                ENGINE.reset();
                message(client, enabled
                        ? "Enabled, cancel budget per second " + config.cancelBudgetPerSecond
                        : "Disabled, sending stopped");
            }

            if (!enabled) {
                return;
            }

            boolean ok;
            try {
                ok = ENGINE.tick(client, config);
            } catch (Exception e) {
                e.printStackTrace();
                ok = false;
            }

            if (!ok) {
                enabled = false;
                message(client, "Packet sending error, mode turned off, see the log for details");
            }
        });
    }

    /**
     * Finds the disconnect screen and remembers the reason text.
     * In version 26.3 the reason is stored in the private details field of DisconnectedScreen.
     */
    private static void captureKickReason(Minecraft client) {
        Screen screen = client.gui.screen();

        if (screen instanceof DisconnectedScreen disconnected) {
            if (!kickCaptured) {
                kickCaptured = true;
                pendingKickReason = extractReason(disconnected);
                if (enabled) {
                    enabled = false;
                    ENGINE.reset();
                }
            }
            return;
        }

        kickCaptured = false;
    }

    private static String extractReason(DisconnectedScreen screen) {
        String raw = screen.getNarrationMessage().getString();
        try {
            Field field = DisconnectedScreen.class.getDeclaredField("details");
            field.setAccessible(true);
            Object details = field.get(screen);
            Object reason = details.getClass().getMethod("reason").invoke(details);
            if (reason instanceof Component component) {
                raw = component.getString();
            }
        } catch (ReflectiveOperationException ignored) {
            // Keep the narration string that was already obtained above.
        }

        // Some hosts use their own packet limiter and disconnect the player
        // with a too many packets message. Lower the global limit automatically
        // and save it, so the next attempt is slower but survives the check.
        String lower = raw == null ? "" : raw.toLowerCase();
        if (lower.contains("too many packets")) {
            int oldLimit = config.totalPacketsPerSecond;
            int newLimit = Math.max(40, (int) (oldLimit * 0.75));
            if (newLimit < oldLimit) {
                config.totalPacketsPerSecond = newLimit;
                config.save();
                return "Too many packets kick. Global packet limit reduced from "
                        + oldLimit + " to " + newLimit + ". Press G to restart.";
            }
            return "Too many packets kick. The global packet limit is already minimal.";
        }

        // Grim disconnects the player with a plain closed connection message.
        // Add a clear explanation so the player knows it was the cancel limit.
        if (raw == null || raw.isBlank()
                || raw.toLowerCase().contains("closed")
                || raw.toLowerCase().contains("disconnect")) {
            return raw == null || raw.isBlank()
                    ? "Grim, too many cancelled packets per second"
                    : raw + ". Grim, too many cancelled packets per second";
        }
        return raw;
    }

    private static void message(Minecraft client, String text) {
        client.gui.hud.getChat().addClientSystemMessage(
                Component.literal("[GrimSpam] " + text));
    }
}
