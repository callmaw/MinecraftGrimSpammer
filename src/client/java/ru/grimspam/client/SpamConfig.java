package ru.grimspam.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Mod settings. The file config/grimspam.json is created automatically on first launch.
 *
 * The vectors are split into two groups because Grim kicks the player not for the
 * number of flags but for the number of cancelled packets. Vectors that only flag
 * can be sent often, while vectors that cancel packets are limited by a budget.
 */
public final class SpamConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Frequency of the free vectors per second. They make Grim register a violation
     * but do not cancel the packet, so the cancel counter does not grow. This is the
     * main source of alerts and server load.
     */
    public int freePacketsPerSecond = 2000;

    /**
     * Global limit of packets per second for all vectors together.
     * Some hosts use their own limiter and kick the player for sending packets too
     * often. Lower this value for such servers.
     */
    public int totalPacketsPerSecond = 300;

    /**
     * Cancel budget per second. One cancelled packet costs two units, one Reach attack
     * costs three because several checks cancel it. The Grim limit is one hundred,
     * so this value leaves a safe margin.
     */
    public int cancelBudgetPerSecond = 70;

    /** Number of Reach attacks per second. Gives one alert per violation. */
    public int reachAttacksPerSecond = 15;

    /** Number of ordinary cancelling vectors per second. */
    public int cancelVectorsPerSecond = 8;

    /**
     * Number of movement packets per second. Each one forces Grim to run a full
     * movement simulation, which is the heaviest load of all.
     */
    public int invalidPitchPerSecond = 12;

    /** Near and far distance limits for Reach attacks in blocks. */
    public double reachMinDistance = 6.0;
    public double reachMaxDistance = 12.0;

    /** Number of entities used as Reach targets. */
    public int maxReachTargets = 8;

    /** List of disabled vectors. Available names are listed in the README. */
    public List<String> disabledVectors = new ArrayList<>();

    /** Config format version, used to fill new values into old files. */
    public int configVersion = 3;

    public static SpamConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("grimspam.json");
        try {
            if (Files.exists(path)) {
                SpamConfig cfg = GSON.fromJson(Files.readString(path), SpamConfig.class);
                if (cfg != null) {
                    if (cfg.configVersion < 2) {
                        cfg.freePacketsPerSecond = 2000;
                        cfg.cancelBudgetPerSecond = 70;
                        cfg.reachAttacksPerSecond = 15;
                        cfg.cancelVectorsPerSecond = 8;
                        cfg.invalidPitchPerSecond = 12;
                        cfg.configVersion = 2;
                    }
                    if (cfg.configVersion < 3) {
                        cfg.totalPacketsPerSecond = 300;
                        cfg.configVersion = 3;
                    }
                    cfg.normalize();
                    cfg.save();
                    return cfg;
                }
            }
        } catch (Exception e) {
            System.err.println("Could not read the config, using default values: " + e);
        }

        SpamConfig cfg = new SpamConfig();
        cfg.save();
        return cfg;
    }

    public void save() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("grimspam.json");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (Exception e) {
            System.err.println("Could not save the config: " + e);
        }
    }

    private void normalize() {
        freePacketsPerSecond = clamp(freePacketsPerSecond, 0, 4000);
        totalPacketsPerSecond = clamp(totalPacketsPerSecond, 1, 4000);
        cancelBudgetPerSecond = clamp(cancelBudgetPerSecond, 0, 90);
        reachAttacksPerSecond = clamp(reachAttacksPerSecond, 0, 40);
        cancelVectorsPerSecond = clamp(cancelVectorsPerSecond, 0, 20);
        invalidPitchPerSecond = clamp(invalidPitchPerSecond, 0, 4);
        maxReachTargets = clamp(maxReachTargets, 1, 10);
        reachMinDistance = Math.max(4.0, reachMinDistance);
        reachMaxDistance = Math.max(reachMinDistance, reachMaxDistance);
        if (disabledVectors == null) {
            disabledVectors = new ArrayList<>();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public boolean vectorEnabled(String id) {
        return !disabledVectors.contains(id);
    }
}
