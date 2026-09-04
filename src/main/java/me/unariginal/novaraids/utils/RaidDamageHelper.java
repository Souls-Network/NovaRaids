package me.unariginal.novaraids.utils;

import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import me.unariginal.novaraids.NovaRaids;
import me.unariginal.novaraids.managers.Raid;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Centralizes raid boss damage calculation and application from fight-phase battles.
 */
public final class RaidDamageHelper {

    private RaidDamageHelper() {}

    public static boolean isBossBattleClone(Pokemon pokemon) {
        if (pokemon == null) {
            return false;
        }
        if (!pokemon.getPersistentData().contains("boss_clone")
                || !pokemon.getPersistentData().contains("battle_clone")) {
            return false;
        }
        return pokemon.getPersistentData().getBoolean("boss_clone")
                && pokemon.getPersistentData().getBoolean("battle_clone");
    }

    /**
     * HP lost by the raid boss clone during a fight-phase battle.
     * Uses {@link BattlePokemon} HP first (authoritative during battle), then falls back
     * to the effected pokemon and faint detection when teardown resets HP.
     */
    public static int calculateBossCloneDamage(BattlePokemon battlePokemon) {
        if (battlePokemon == null) {
            return 0;
        }
        Pokemon pokemon = battlePokemon.getEffectedPokemon();
        if (!isBossBattleClone(pokemon)) {
            return 0;
        }

        int maxHp = battlePokemon.getMaxHealth();
        int battleHpLost = Math.max(0, maxHp - battlePokemon.getHealth());
        if (battleHpLost > 0) {
            return battleHpLost;
        }

        int pokemonHpLost = Math.max(0, pokemon.getMaxHealth() - pokemon.getCurrentHealth());
        if (pokemonHpLost > 0) {
            return pokemonHpLost;
        }

        if (battlePokemon.getHealth() <= 0 || pokemon.getCurrentHealth() <= 0) {
            return maxHp;
        }
        return 0;
    }

    public static void applyRaidBossDamage(Raid raid, ServerPlayerEntity player, int damage) {
        if (player == null || damage <= 0 || raid.stage() != 2) {
            return;
        }
        if (!raid.participatingPlayers().contains(player.getUuid())) {
            return;
        }

        raid.removeFleeingPlayer(player.getUuid());

        if (damage > raid.currentHealth()) {
            damage = raid.currentHealth();
        }
        if (damage <= 0) {
            return;
        }

        raid.applyDamage(damage);
        raid.updatePlayerDamage(player.getUuid(), damage);
        raid.participatingBroadcast(TextUtils.deserialize(TextUtils.parse(
                NovaRaids.INSTANCE.messagesConfig().getMessage("player_damage_report"),
                raid,
                player,
                damage,
                -1
        )));
    }
}
