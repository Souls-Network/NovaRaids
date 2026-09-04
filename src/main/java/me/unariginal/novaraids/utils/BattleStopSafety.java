package me.unariginal.novaraids.utils;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;

/**
 * Avoids dedicated-server crashes when Showdown is already torn down but
 * {@link PokemonBattle#stop()} still sends {@code >forcetie}.
 */
public final class BattleStopSafety {

    private static final long CATCH_RESPAWN_COOLDOWN_MS = 5_000L;

    private BattleStopSafety() {}

    public static long catchRespawnCooldownMs() {
        return CATCH_RESPAWN_COOLDOWN_MS;
    }

    public static void safeStop(PokemonBattle battle) {
        if (battle == null) {
            return;
        }

        try {
            if (!battle.getEnded()) {
                battle.end();
            }
        } catch (Throwable ignored) {
            // end() is best-effort; forcetie may still be required
        }

        try {
            battle.writeShowdownAction(">forcetie");
        } catch (Throwable ignored) {
            // Showdown connection already dead — battle.end() already ran server-side
        }
    }
}
