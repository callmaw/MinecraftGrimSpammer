package ru.grimspam.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Настройки мода. Файл config/grimspam.json создаётся автоматически при первом запуске.
 *
 * Причина разделения на две группы в том, что Grim кикает не за число флагов,
 * а за число отменённых пакетов. Векторы, которые только флагают, можно слать часто,
 * а векторы, отменяющие пакет, ограничены бюджетом.
 */
public final class SpamConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Частота бесплатных векторов в секунду.
     * Они заставляют Grim зафиксировать нарушение, но не отменяют пакет,
     * поэтому счётчик отмен не растёт. Это основной источник алертов и нагрузки.
     */
    public int freePacketsPerSecond = 2000;

    /**
     * Бюджет отмен в секунду. Один отменяемый пакет стоит два юнита,
     * одна атака Reach стоит три, потому что её отменяют несколько проверок.
     * У Grim лимит сто отмен в секунду, поэтому здесь есть запас.
     */
    public int cancelBudgetPerSecond = 70;

    /** Число атак Reach в секунду. Даёт один алерт на одно нарушение. */
    public int reachAttacksPerSecond = 15;

    /** Число обычных отменяемых векторов в секунду. */
    public int cancelVectorsPerSecond = 8;

    /**
     * Число пакетов движения в секунду. Каждый такой пакет заставляет Grim
     * провести полную симуляцию движения, это самая тяжёлая нагрузка.
     */
    public int invalidPitchPerSecond = 12;

    /** Ближняя и дальняя граница дистанции для атак Reach в блоках. */
    public double reachMinDistance = 6.0;
    public double reachMaxDistance = 12.0;

    /** Сколько сущностей использовать как цели для Reach. */
    public int maxReachTargets = 8;

    /** Список отключённых векторов. Доступные имена указаны в README. */
    public List<String> disabledVectors = new ArrayList<>();

    /** Версия формата конфига, нужна для подстановки новых значений в старые файлы. */
    public int configVersion = 2;

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
                    cfg.normalize();
                    cfg.save();
                    return cfg;
                }
            }
        } catch (Exception e) {
            System.err.println("Не удалось прочитать конфиг, используются значения по умолчанию: " + e);
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
            System.err.println("Не удалось сохранить конфиг: " + e);
        }
    }

    private void normalize() {
        freePacketsPerSecond = clamp(freePacketsPerSecond, 0, 4000);
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
