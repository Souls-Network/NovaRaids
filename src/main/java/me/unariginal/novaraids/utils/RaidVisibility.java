package me.unariginal.novaraids.utils;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import me.unariginal.novaraids.NovaRaids;
import me.unariginal.novaraids.managers.Raid;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

/**
 * Server-authoritative per-player visibility for raid entities.
 * Used by {@code Entity#broadcastToPlayer}/{@code canBeSpectated} and the
 * ChunkMap TrackedEntity failsafe mixin.
 *
 * Catch-phase rule (non-negotiable):
 * - Owner MUST see (and track) their assigned catch clone
 * - Everyone else MUST NOT see other players' catch clones
 */
public final class RaidVisibility {

    private RaidVisibility() {}

    public static boolean shouldHideFrom(Entity entity, ServerPlayerEntity viewer) {
        if (entity == null || viewer == null || NovaRaids.INSTANCE == null || !NovaRaids.LOADED) {
            return false;
        }

        if (entity instanceof PokemonEntity pokemonEntity) {
            return shouldHidePokemon(pokemonEntity, viewer);
        }
        if (entity instanceof ServerPlayerEntity otherPlayer) {
            return shouldHidePlayer(otherPlayer, viewer);
        }
        return false;
    }

    /**
     * True when this entity is a catch clone that {@code viewer} is allowed to track.
     * Used to force-show the owner's clone (prevents accidental hide/invert paths).
     */
    public static boolean forceShowCatchTo(Entity entity, ServerPlayerEntity viewer) {
        if (!(entity instanceof PokemonEntity pokemonEntity) || viewer == null) {
            return false;
        }
        Pokemon pokemon = pokemonEntity.getPokemon();
        if (pokemon == null) {
            return false;
        }
        if (!CatchEncounterTags.hasCatchEncounterTags(pokemon)
                && !CatchEncounterTags.isRaidCatchPokemon(pokemon)
                && !isRegisteredCloneFor(pokemonEntity, viewer.getUuid())) {
            return false;
        }
        return isCatchOwner(pokemonEntity, pokemon, viewer.getUuid());
    }

    private static boolean shouldHidePokemon(PokemonEntity pokemonEntity, ServerPlayerEntity viewer) {
        Pokemon pokemon = pokemonEntity.getPokemon();
        if (pokemon == null) {
            return false;
        }

        // Fight-phase battle clones — always hidden unless debug (never catch encounters).
        if (pokemon.getPersistentData().contains("raid_entity")
                && pokemon.getPersistentData().contains("boss_clone")
                && pokemon.getPersistentData().contains("battle_clone")
                && !pokemon.getPersistentData().contains("catch_encounter")
                && !NovaRaids.INSTANCE.debug) {
            return true;
        }

        if (NovaRaids.INSTANCE.config().hideOtherCatchEncounters
                && !NovaRaidsPermissions.SHOWPOKEMON.test(viewer)
                && shouldHideCatchEncounter(pokemonEntity, pokemon, viewer)) {
            return true;
        }

        if (NovaRaids.INSTANCE.config().hideOtherPokemonInRaid
                && !NovaRaidsPermissions.SHOWPOKEMON.test(viewer)
                && shouldHideOtherRaidPokemon(pokemon, viewer)) {
            return true;
        }

        return false;
    }

    /**
     * Catch-phase reward clones: only the assigned owner may track them.
     * Owner is resolved from clones-map first (authoritative), then NBT tags.
     */
    private static boolean shouldHideCatchEncounter(
            PokemonEntity pokemonEntity,
            Pokemon pokemon,
            ServerPlayerEntity viewer
    ) {
        // Keep hiding after capture while tags/clones-map still exist. isRaidCatchPokemon
        // is false for player-owned mons, which previously un-hid the just-caught entity
        // and spawned it for every nearby client (custom-payload disconnect).
        UUID ownerUuid = resolveCatchOwner(pokemonEntity, pokemon);
        boolean catchEntity = CatchEncounterTags.hasCatchEncounterTags(pokemon)
                || CatchEncounterTags.isRaidCatchPokemon(pokemon)
                || ownerUuid != null;
        if (!catchEntity) {
            return false;
        }

        // Strongest signal: this entity is the live clone registered for the viewer.
        if (isRegisteredCloneFor(pokemonEntity, viewer.getUuid())) {
            return false;
        }

        if (ownerUuid == null) {
            // Tagged/orphan catch mon with no resolvable owner — hide from non-admins.
            return true;
        }

        // Owner sees; everyone else is hidden.
        return !ownerUuid.equals(viewer.getUuid());
    }

    private static boolean isCatchOwner(PokemonEntity pokemonEntity, Pokemon pokemon, UUID viewerUuid) {
        if (viewerUuid == null) {
            return false;
        }
        if (isRegisteredCloneFor(pokemonEntity, viewerUuid)) {
            return true;
        }
        UUID ownerUuid = resolveCatchOwner(pokemonEntity, pokemon);
        return ownerUuid != null && ownerUuid.equals(viewerUuid);
    }

    private static boolean isRegisteredCloneFor(PokemonEntity pokemonEntity, UUID playerUuid) {
        if (NovaRaids.INSTANCE == null || playerUuid == null || pokemonEntity == null) {
            return false;
        }
        UUID indexedOwner = NovaRaids.INSTANCE.getCatchCloneOwnerByEntityId(pokemonEntity.getUuid());
        if (indexedOwner != null) {
            return playerUuid.equals(indexedOwner);
        }
        if (NovaRaids.INSTANCE.activeRaids().size() <= 3) {
            return scanRegisteredCloneFor(pokemonEntity, playerUuid) != null;
        }
        return false;
    }

    private static UUID scanRegisteredCloneFor(PokemonEntity pokemonEntity, UUID playerUuid) {
        for (Raid raid : NovaRaids.INSTANCE.activeRaids().values()) {
            PokemonEntity live = raid.findLiveCatchCloneForPlayer(playerUuid);
            if (live != null && (live == pokemonEntity || live.getUuid().equals(pokemonEntity.getUuid()))) {
                return playerUuid;
            }
            UUID mapped = raid.getCloneOwnerUuid(pokemonEntity);
            if (playerUuid.equals(mapped)) {
                return playerUuid;
            }
        }
        return null;
    }

    /**
     * Prefer live clones-map ownership over NBT (map is mutated with the raid;
     * NBT can desync after flee/respawn). Fall back to tags when map misses.
     */
    private static UUID resolveCatchOwner(PokemonEntity pokemonEntity, Pokemon pokemon) {
        if (NovaRaids.INSTANCE != null && pokemonEntity != null) {
            UUID indexed = NovaRaids.INSTANCE.getCatchCloneOwnerByEntityId(pokemonEntity.getUuid());
            if (indexed != null) {
                return indexed;
            }
            if (NovaRaids.INSTANCE.activeRaids().size() <= 3) {
                for (Raid raid : NovaRaids.INSTANCE.activeRaids().values()) {
                    UUID mapped = raid.getCloneOwnerUuid(pokemonEntity);
                    if (mapped != null) {
                        return mapped;
                    }
                }
            }
        }
        return CatchEncounterTags.getOwnerUuid(pokemon);
    }

    private static boolean shouldHideOtherRaidPokemon(Pokemon pokemon, ServerPlayerEntity viewer) {
        if (pokemon.getPersistentData().contains("raid_entity")) {
            return false;
        }
        if (!pokemon.isPlayerOwned()) {
            return false;
        }
        if (!isParticipatingInAnyRaid(viewer.getUuid())) {
            return false;
        }
        ServerPlayerEntity owner = pokemon.getOwnerPlayer();
        if (owner == null || owner.getUuid().equals(viewer.getUuid())) {
            return false;
        }
        return isParticipatingInAnyRaid(owner.getUuid());
    }

    private static boolean shouldHidePlayer(ServerPlayerEntity otherPlayer, ServerPlayerEntity viewer) {
        if (!NovaRaids.INSTANCE.config().hideOtherPlayersInRaid) {
            return false;
        }
        if (NovaRaidsPermissions.SHOWPLAYERS.test(viewer)) {
            return false;
        }
        if (otherPlayer.getUuid().equals(viewer.getUuid())) {
            return false;
        }
        return isParticipatingInAnyRaid(viewer.getUuid())
                && isParticipatingInAnyRaid(otherPlayer.getUuid());
    }

    private static boolean isParticipatingInAnyRaid(UUID playerUuid) {
        for (Raid raid : NovaRaids.INSTANCE.activeRaids().values()) {
            if (raid.participatingPlayers().contains(playerUuid)) {
                return true;
            }
        }
        return false;
    }
}
