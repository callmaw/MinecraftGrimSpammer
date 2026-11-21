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
 * Sends packets to the server that Grim treats as violations.
 *
 * The kick in earlier versions was caused not by alerts but by the cancelled packet
 * limit. In the Grim source the onPacketCancel method increases a counter and
 * PacketLimiter resets it once per second. The default threshold is one hundred.
 *
 * All vectors are split into two groups. Free vectors make Grim register a violation
 * but do not cancel the packet. Cancelling vectors spend the budget and are therefore
 * limited by a rolling one second window.
 */
public final class SpamEngine {

    /*
     * Free vectors are repeated several times to bias the selection toward the most
     * reliable ones. The dig vector uses the ordinary BadPacketsL check and always
     * works. The other checks are experimental and depend on server settings.
     */
    private static final String[] FREE_VECTORS = {
            "dig", "dig", "inputDup", "inputDup", "sleep", "respawn"
    };

    /** Vectors cancelled by Grim, except Reach attacks and movement packets. */
    private static final String[] CANCEL_VECTORS = {
            "heldItem", "sprint", "abilities", "spectate", "input", "paddle"
    };

    /** Cost of one cancelling packet in budget units. */
    private static final int COST_CANCEL_VECTOR = 2;

    /** Cost of one Reach attack, cancelled by several checks in a row. */
    private static final int COST_REACH = 3;

    /** Cost of one movement packet with an unusual rotation. */
    private static final int COST_PITCH = 2;

    /* Keep the vertical rotation inside valid limits, otherwise vanilla disconnects
     * the player with an invalid movement packet message. */
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

    /** Timestamps of cancelled packets during the last second. */
    private final ArrayDeque<Long> cancelWindow = new ArrayDeque<>();

    /** Timestamps of all packets during the last second. */
    private final ArrayDeque<Long> totalWindow = new ArrayDeque<>();

    /** Resets all state when the mode is turned off or the player leaves the server. */
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
        totalWindow.clear();
    }

    /**
     * Runs one client tick, twenty times per second.
     * Returns false only on a critical error when the mod must be turned off.
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

            sendFree(conn, player, cfg, now);
            sendReach(mc, conn, player, cfg, now);
            sendCancelPlain(conn, player, cfg, now);
            sendMovement(conn, player, cfg, now);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /** Removes timestamps older than one second from both windows. */
    private void pruneWindow(long now) {
        long cutoff = now - WINDOW_MS;
        while (!cancelWindow.isEmpty() && cancelWindow.peekFirst() <= cutoff) {
            cancelWindow.removeFirst();
        }
        while (!totalWindow.isEmpty() && totalWindow.peekFirst() <= cutoff) {
            totalWindow.removeFirst();
        }
    }

    /**
     * Checks the global packet limit. Returns false when the limit is reached.
     * This protects against server limiters that kick for sending packets too often.
     */
    private boolean canSendTotal(SpamConfig cfg, long now) {
        if (totalWindow.size() >= cfg.totalPacketsPerSecond) {
            return false;
        }
        totalWindow.addLast(now);
        return true;
    }

    /** Checks whether one more cancelling packet can be sent. */
    private boolean canCancel(SpamConfig cfg, int cost, long now) {
        if (cancelWindow.size() + cost > cfg.cancelBudgetPerSecond) {
            return false;
        }
        for (int i = 0; i < cost; i++) {
            cancelWindow.addLast(now);
        }
        return true;
    }

    // Free vectors. They produce alerts and load without spending the cancel budget.

    private void sendFree(ClientPacketListener conn, LocalPlayer player, SpamConfig cfg, long now) {
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
            if (packet != null && canSendTotal(cfg, now)) {
                conn.send(packet);
            }
        }
    }

    private Packet<?> buildFree(String id, LocalPlayer player) {
        switch (id) {
            /*
             * BadPacketsL sees a sequence other than zero and registers a violation.
             * For this action canCancel returns false, so the packet is not cancelled.
             */
            case "dig":
                return new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                        BlockPos.ZERO, Direction.DOWN, 1);

            /*
             * A duplicate input packet in one tick triggers BadPacketsZ.
             * The Vehicle checks listen to a different packet type and do not cancel it.
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

    // Ordinary cancelling vectors. Each of them spends the cancel budget.

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
            if (packet != null && canSendTotal(cfg, now)) {
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

    // Reach attacks. They give one alert per violation but cost three units.

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
            if (!canSendTotal(cfg, now)) {
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

    // Movement packets. They force Grim to run a full simulation without a vanilla kick.

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
            if (!canSendTotal(cfg, now)) {
                return;
            }
            float yaw = ((pitchCursor++ % 2) == 0) ? 180.0F : -180.0F;
            conn.send(new ServerboundMovePlayerPacket.Rot(
                    yaw, SAFE_PITCH, player.onGround(), false));
        }
    }
}
