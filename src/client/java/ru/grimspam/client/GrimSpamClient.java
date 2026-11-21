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
 * Точка входа клиентского мода.
 *
 * Одна клавиша по умолчанию G включает и выключает режим отправки пакетов.
 * Клавишу можно переназначить в настройках управления.
 * При вылете с сервера режим выключается сам, а причина кика показывается
 * в чате после следующего входа.
 */
public final class GrimSpamClient implements ClientModInitializer {
    private static final SpamEngine ENGINE = new SpamEngine();

    private static SpamConfig config;
    private static KeyMapping toggleKey;
    private static boolean enabled;

    /** Причина последнего кика, которую ещё не показали игроку. */
    private static String pendingKickReason;

    /** Признак того, что экран кика уже обработан, чтобы не читать его каждый тик. */
    private static boolean kickCaptured;

    @Override
    public void onInitializeClient() {
        config = SpamConfig.load();

        toggleKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.grimspam.toggle", InputConstants.KEY_G, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            captureKickReason(client);

            // Если соединение пропало, сразу выключаем режим и сбрасываем счётчики.
            if (enabled && (client.getConnection() == null || client.player == null)) {
                enabled = false;
                ENGINE.reset();
                return;
            }

            // После повторного входа показываем причину прошлого кика.
            if (pendingKickReason != null && client.player != null && client.getConnection() != null) {
                String reason = pendingKickReason;
                pendingKickReason = null;
                message(client, "Причина прошлого кика: " + reason);
            }

            if (toggleKey.consumeClick()) {
                enabled = !enabled;
                ENGINE.reset();
                message(client, enabled
                        ? "Включено, лимит отмен в секунду " + config.cancelBudgetPerSecond
                        : "Выключено, отправка остановлена");
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
                message(client, "Ошибка отправки пакетов, режим выключен, подробности в логе");
            }
        });
    }

    /**
     * Находит экран отключения от сервера и запоминает текст причины.
     * В версии 26.3 причина лежит в приватном поле details класса DisconnectedScreen.
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
            // Оставляем строку из narration, она уже получена выше.
        }

        // Grim отключает игрока с безликим сообщением о закрытии соединения.
        // Добавляем понятную расшифровку, чтобы игрок знал, что это лимит отмен.
        if (raw == null || raw.isBlank()
                || raw.toLowerCase().contains("closed")
                || raw.toLowerCase().contains("disconnect")) {
            return raw == null || raw.isBlank()
                    ? "Grim, слишком много отменённых пакетов за секунду"
                    : raw + ". Grim, слишком много отменённых пакетов за секунду";
        }
        return raw;
    }

    private static void message(Minecraft client, String text) {
        client.gui.hud.getChat().addClientSystemMessage(
                Component.literal("[GrimSpam] " + text));
    }
}
