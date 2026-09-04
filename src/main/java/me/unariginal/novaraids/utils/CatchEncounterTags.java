package me.unariginal.novaraids.utils;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

/**
 * Persistent per-player ownership for NovaRaids catch-phase encounters.
 * Survives flee, disconnect, and entity respawn so only the assigned player
 * can battle or catch their raid reward Pokémon.
 */
public final class CatchEncounterTags {

    public static final String CATCH_OWNER_UUID = "catch_owner_uuid";
    public static final String CATCH_RAID_UUID = "catch_raid_uuid";
    /** String fallback — some Cobblemon persistent-data paths drop typed UUID tags. */
    private static final String CATCH_OWNER_UUID_STR = "catch_owner_uuid_str";
    private static final String CATCH_RAID_UUID_STR = "catch_raid_uuid_str";

    private CatchEncounterTags() {}

    public static void tagCatchEncounter(Pokemon pokemon, ServerPlayerEntity owner, UUID raidUuid) {
        NbtCompound data = pokemon.getPersistentData();
        data.putBoolean("raid_entity", true);
        data.putBoolean("boss_clone", true);
        data.putBoolean("catch_encounter", true);
        data.putBoolean("novaraids_raid_battle", true);
        // Never mark catch clones as fight-phase battle_clone (that path hides from everyone).
        data.remove("battle_clone");
        data.putUuid(CATCH_OWNER_UUID, owner.getUuid());
        data.putString(CATCH_OWNER_UUID_STR, owner.getUuid().toString());
        data.putUuid(CATCH_RAID_UUID, raidUuid);
        data.putString(CATCH_RAID_UUID_STR, raidUuid.toString());
        pokemon.setPersistentData$common(data);
    }

    public static UUID getOwnerUuid(PokemonEntity entity) {
        if (entity == null) {
            return null;
        }
        return getOwnerUuid(entity.getPokemon());
    }

    public static UUID getOwnerUuid(Pokemon pokemon) {
        if (pokemon == null) {
            return null;
        }
        NbtCompound data = pokemon.getPersistentData();
        if (data.containsUuid(CATCH_OWNER_UUID)) {
            return data.getUuid(CATCH_OWNER_UUID);
        }
        if (data.contains(CATCH_OWNER_UUID)) {
            try {
                return data.getUuid(CATCH_OWNER_UUID);
            } catch (Throwable ignored) {
                // fall through to string
            }
        }
        if (data.contains(CATCH_OWNER_UUID_STR)) {
            try {
                return UUID.fromString(data.getString(CATCH_OWNER_UUID_STR));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    public static UUID getRaidUuid(Pokemon pokemon) {
        if (pokemon == null) {
            return null;
        }
        NbtCompound data = pokemon.getPersistentData();
        if (data.containsUuid(CATCH_RAID_UUID)) {
            return data.getUuid(CATCH_RAID_UUID);
        }
        if (data.contains(CATCH_RAID_UUID)) {
            try {
                return data.getUuid(CATCH_RAID_UUID);
            } catch (Throwable ignored) {
                // fall through
            }
        }
        if (data.contains(CATCH_RAID_UUID_STR)) {
            try {
                return UUID.fromString(data.getString(CATCH_RAID_UUID_STR));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    public static boolean isCatchEncounter(Pokemon pokemon) {
        return pokemon != null
                && pokemon.getPersistentData().contains("catch_encounter")
                && pokemon.getPersistentData().getBoolean("catch_encounter");
    }

    public static boolean isOwnedBy(PokemonEntity entity, UUID playerUuid) {
        UUID owner = getOwnerUuid(entity);
        return owner != null && owner.equals(playerUuid);
    }

    public static boolean isOwnedBy(Pokemon pokemon, UUID playerUuid) {
        UUID owner = getOwnerUuid(pokemon);
        return owner != null && owner.equals(playerUuid);
    }

    /**
     * True when catch-phase metadata is present (including a Pokemon that was
     * just captured and is now player-owned — used by POKEMON_CAPTURED).
     */
    public static boolean hasCatchEncounterTags(Pokemon pokemon) {
        if (pokemon == null) {
            return false;
        }
        if (isCatchEncounter(pokemon)) {
            return true;
        }
        return getOwnerUuid(pokemon) != null && getRaidUuid(pokemon) != null;
    }

    /**
     * True for an active wild catch-phase clone in the world.
     * Player-owned Pokemon are never active catch clones (strip leftover tags).
     */
    public static boolean isRaidCatchPokemon(Pokemon pokemon) {
        if (pokemon == null) {
            return false;
        }
        if (pokemon.isPlayerOwned()) {
            return false;
        }
        return hasCatchEncounterTags(pokemon);
    }

    /** Strip leftover catch tags from a player's caught Pokemon (repair older builds). */
    public static void stripLeftoverTagsIfPlayerOwned(Pokemon pokemon) {
        if (pokemon == null || !pokemon.isPlayerOwned()) {
            return;
        }
        if (hasCatchEncounterTags(pokemon) || pokemon.getPersistentData().contains("raid_entity")) {
            clearCatchEncounterTags(pokemon);
        }
    }

    /** Strip raid catch metadata from a captured Pokémon so it cannot re-trigger handlers. */
    public static void clearCatchEncounterTags(Pokemon pokemon) {
        if (pokemon == null) {
            return;
        }
        NbtCompound data = pokemon.getPersistentData();
        data.remove("catch_encounter");
        data.remove(CATCH_OWNER_UUID);
        data.remove(CATCH_OWNER_UUID_STR);
        data.remove(CATCH_RAID_UUID);
        data.remove(CATCH_RAID_UUID_STR);
        data.remove("boss_clone");
        data.remove("raid_entity");
        data.remove("novaraids_raid_battle");
        pokemon.setPersistentData$common(data);
    }
}
