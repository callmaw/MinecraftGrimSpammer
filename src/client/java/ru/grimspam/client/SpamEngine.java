package ru.grimspam.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Abilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Отправляет серверу пакеты, которые Grim считает нарушениями.
 *
 * Причина кика в предыдущих версиях была связана не с алертами, а с лимитом
 * отменённых пакетов. В исходниках Grim метод onPacketCancel увеличивает счётчик,
 * а PacketLimiter сбрасывает его раз в секунду. Порог по умолчанию равен ста.
 *
 * Все векторы делятся на две группы. Бесплатные векторы заставляют Grim
 * зафиксировать нарушение, но не отменяют пакет. Отменяемые векторы
 * расходуют бюджет и поэтому ограничены скользящим окном в одну секунду.
 */
public final class SpamEngine {

    /*
     * Бесплатные векторы повторяются несколько раз, чтобы сместить выборку
     * в сторону самых надёжных из них. Вектор dig даёт обычную проверку BadPacketsL
     * и работает всегда. Остальные проверки экспериментальные и зависят от настроек сервера.
     */
    private static final String[] FREE_VECTORS = {
            "dig", "dig", "inputDup", "inputDup", "sleep", "respawn"
    };

    /** Векторы, которые Grim отменяет, кроме атак Reach и пакетов движения. */
    private static final String[] CANCEL_VECTORS = {
            "heldItem", "sprint", "abilities", "spectate", "input", "paddle"
    };

    /** Стоимость одного отменяемого пакета в юнитах бюджета. */
    private static final int COST_CANCEL_VECTOR = 2;

    /** Стоимость одной атаки Reach: её отменяют несколько проверок подряд. */
    private static final int COST_REACH = 3;

    /** Стоимость одного пакета движения с необычным поворотом. */
    private static final int COST_PITCH = 2;

    /* Поворот по вертикали держим в допустимых пределах, иначе ваниль
     * отключает игрока с сообщением о некорректном пакете движения. */
    private static final float SAFE_PITCH = 0.0F;

    private static final long WINDOW_MS = 1000L;

    private int freeCursor;
    private int cancelCursor;
    private int reachCursor;
    private int pitchCursor;

    private double freeAcc;
    private double cancelAcc;
    private double reachAcc;
    private double pitchAcc;

    /** Метки времени отменяемых пакетов за последнюю секунду. */
    private final ArrayDeque<Long> cancelWindow = new ArrayDeque<>();

    /** Сбрасывает всё состояние при выключении режима или вылете с сервера. */
    public void reset() {
        freeCursor = 0;
        cancelCursor = 0;
        reachCursor = 0;
        pitchCursor = 0;
        freeAcc = 0;
        cancelAcc = 0;
        reachAcc = 0;
        pitchAcc = 0;
        cancelWindow.clear();
    }

    /**
     * Выполняет один клиентский тик, то есть двадцать раз в секунду.
     * Возвращает false только при критической ошибке, когда мод надо выключить.
     */
    public boolean tick(Minecraft mc, SpamConfig cfg) {
        LocalPlayer player = mc.player;
        ClientPacketListener conn = mc.getConnection();

        if (player == null || mc.level == null || conn == null) {
            return true;
        }

        try {
            long now = System.currentTimeMillis();
            pruneWindow(now);

            sendFree(conn, player, cfg);
            sendReach(mc, conn, player, cfg, now);
            sendCancelPlain(conn, player, cfg, now);
            sendMovement(conn, player, cfg, now);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /** Удаляет из окна метки старше одной секунды. */
    private void pruneWindow(long now) {
        long cutoff = now - WINDOW_MS;
        while (!cancelWindow.isEmpty() && cancelWindow.peekFirst() <= cutoff) {
            cancelWindow.removeFirst();
        }
    }

    /** Проверяет, можно ли отправить ещё один отменяемый пакет. */
    private boolean canCancel(SpamConfig cfg, int cost, long now) {
        if (cancelWindow.size() + cost > cfg.cancelBudgetPerSecond) {
            return false;
        }
        for (int i = 0; i < cost; i++) {
            cancelWindow.addLast(now);
        }
        return true;
    }

    // Бесплатные векторы. Они дают алерты и нагрузку, но не расходуют бюджет отмен.

    private void sendFree(ClientPacketListener conn, LocalPlayer player, SpamConfig cfg) {
        freeAcc += cfg.freePacketsPerSecond / 20.0;
        int budget = (int) freeAcc;
        freeAcc -= budget;
        if (budget <= 0) {
            return;
        }

        List<String> enabled = new ArrayList<>(FREE_VECTORS.length);
        for (String id : FREE_VECTORS) {
            if (cfg.vectorEnabled(id)) {
                enabled.add(id);
            }
        }
        if (enabled.isEmpty()) {
            return;
        }

        for (int i = 0; i < budget; i++) {
            String id = enabled.get(freeCursor % enabled.size());
            freeCursor++;
            Packet<?> packet = buildFree(id, player);
            if (packet != null) {
                conn.send(packet);
            }
        }
    }

    private Packet<?> buildFree(String id, LocalPlayer player) {
        switch (id) {
            /*
             * BadPacketsL видит последовательность, отличную от нуля, и фиксирует нарушение.
             * Для этого действия метод canCancel возвращает ложь, поэтому пакет не отменяется.
             */
            case "dig":
                return new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                        BlockPos.ZERO, Direction.DOWN, 1);

            /*
             * Дубль пакета ввода в одном тике попадает под BadPacketsZ.
             * Проверки Vehicle слушают другой тип пакета и поэтому не отменяют его.
             */
            case "inputDup":
                return new ServerboundPlayerInputPacket(player.input.keyPresses);

            case "sleep":
                return new ServerboundPlayerCommandPacket(
                        player, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING);

            case "respawn":
                if (player.getHealth() <= 0.0F) {
                    return null;
                }
                return new ServerboundClientCommandPacket(
                        ServerboundClientCommandPacket.Action.PERFORM_RESPAWN);

            default:
                return null;
        }
    }

    // Обычные отменяемые векторы. Каждый из них расходует бюджет.

    private void sendCancelPlain(ClientPacketListener conn, LocalPlayer player,
                                 SpamConfig cfg, long now) {
        cancelAcc += cfg.cancelVectorsPerSecond / 20.0;
        int budget = (int) cancelAcc;
        cancelAcc -= budget;
        if (budget <= 0) {
            return;
        }

        List<String> enabled = new ArrayList<>(CANCEL_VECTORS.length);
        for (String id : CANCEL_VECTORS) {
            if (cfg.vectorEnabled(id)) {
                enabled.add(id);
            }
        }
        if (enabled.isEmpty()) {
            return;
        }

        for (int i = 0; i < budget; i++) {
            if (!canCancel(cfg, COST_CANCEL_VECTOR, now)) {
                return;
            }
            String id = enabled.get(cancelCursor % enabled.size());
            cancelCursor++;
            Packet<?> packet = buildCancel(id, player);
            if (packet != null) {
                conn.send(packet);
            }
        }
    }

    private Packet<?> buildCancel(String id, LocalPlayer player) {
        switch (id) {
            case "heldItem":
                return new ServerboundSetCarriedItemPacket(player.getInventory().getSelectedSlot());

            case "sprint":
                return new ServerboundPlayerCommandPacket(
                        player, ServerboundPlayerCommandPacket.Action.START_SPRINTING);

            case "abilities": {
                if (player.getAbilities().mayfly) {
                    return null;
                }
                Abilities spoof = new Abilities();
                spoof.flying = true;
                return new ServerboundPlayerAbilitiesPacket(spoof);
            }

            case "spectate":
                if (player.isSpectator()) {
                    return null;
                }
                return new ServerboundTeleportToEntityPacket(UUID.randomUUID());

            case "input":
                return new ServerboundPlayerInputPacket(player.input.keyPresses);

            case "paddle":
                if (player.isPassenger()) {
                    return null;
                }
                return new ServerboundPaddleBoatPacket(false, false);

            default:
                return null;
        }
    }

    // Атаки Reach. Дают один алерт на одно нарушение, но стоят три юнита.

    private void sendReach(Minecraft mc, ClientPacketListener conn, LocalPlayer player,
                           SpamConfig cfg, long now) {
        if (!cfg.vectorEnabled("reach")) {
            return;
        }

        reachAcc += cfg.reachAttacksPerSecond / 20.0;
        int budget = (int) reachAcc;
        reachAcc -= budget;
        if (budget <= 0) {
            return;
        }

        List<Entity> targets = collectTargets(mc, cfg);
        if (targets.isEmpty()) {
            return;
        }

        for (int i = 0; i < budget; i++) {
            if (!canCancel(cfg, COST_REACH, now)) {
                return;
            }
            Entity target = targets.get(reachCursor % targets.size());
            reachCursor++;
            conn.send(new ServerboundAttackPacket(target.getId()));
        }
    }

    private static List<Entity> collectTargets(Minecraft mc, SpamConfig cfg) {
        List<Entity> targets = new ArrayList<>();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return targets;
        }

        if (player.isPassenger() || player.isCreative() || player.isSpectator()) {
            return targets;
        }
        if (player.getHealth() <= 0.0F) {
            return targets;
        }

        double min = cfg.reachMinDistance;
        double max = cfg.reachMaxDistance;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == player || !(entity instanceof LivingEntity)) {
                continue;
            }
            if (entity.isRemoved() || !entity.isAlive()) {
                continue;
            }
            double distance = player.position().distanceTo(entity.position());
            if (distance < min || distance > max) {
                continue;
            }
            targets.add(entity);
            if (targets.size() >= cfg.maxReachTargets) {
                break;
            }
        }
        return targets;
    }

    // Пакеты движения. Заставляют Grim провести полную симуляцию, но не кикают сервер.

    private void sendMovement(ClientPacketListener conn, LocalPlayer player,
                              SpamConfig cfg, long now) {
        if (!cfg.vectorEnabled("pitch")) {
            return;
        }

        pitchAcc += cfg.invalidPitchPerSecond / 20.0;
        int budget = (int) pitchAcc;
        pitchAcc -= budget;
        if (budget <= 0) {
            return;
        }

        for (int i = 0; i < budget; i++) {
            if (!canCancel(cfg, COST_PITCH, now)) {
                return;
            }
            float yaw = ((pitchCursor++ % 2) == 0) ? 180.0F : -180.0F;
            conn.send(new ServerboundMovePlayerPacket.Rot(
                    yaw, SAFE_PITCH, player.onGround(), false));
        }
    }
}
