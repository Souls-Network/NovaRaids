package me.unariginal.novaraids.utils;

import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.api.drop.DropTable;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import me.unariginal.novaraids.NovaRaids;
import me.unariginal.novaraids.data.Location;
import me.unariginal.novaraids.managers.Raid;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public final class CatchEncounterEntityHelper {

    /** Minimum ring radius so catch clones are not stacked on the boss tile. */
    private static final double CATCH_SPAWN_RADIUS = 8.0D;
    /** Chord length between neighboring clones; radius grows with player count. */
    private static final double CATCH_MIN_SPACING = 8.0D;
    /** Owner stands this many blocks toward the center from their clone. */
    private static final double CATCH_PLAYER_INSET = 3.0D;
    private static final double CATCH_REANCHOR_DISTANCE_SQ = 0.25D;
    /** Scoreboard team used so vanilla glowing outline renders red (synced to clients). */
    private static final String CATCH_GLOW_TEAM = "nr_catch_red";
    /** Cobblemon busyLocks entry for an active/stale battle. */
    private static final String BATTLE_BUSY_LOCK = "battle";

    private CatchEncounterEntityHelper() {}

    public static void restoreVisibility(PokemonEntity entity) {
        if (entity == null || entity.isRemoved()) {
            return;
        }
        try {
            entity.setBeamMode(0);
            entity.setInvisible(false);
            entity.setSilent(false);
            entity.setPhasingTargetId(0);
        } catch (Throwable ignored) {
            // Best-effort only
        }
    }

    /**
     * Clears ONLY a stale Cobblemon "battle" busy lock / battleId.
     * Never wipes the whole busyLocks list — capture, send-out beam, and
     * evolution locks must survive or Pokemon vanish mid-ball / mid-sendout
     * and canBattle stays false for the wrong reasons.
     */
    public static void clearBusyState(PokemonEntity entity) {
        if (entity == null || entity.isRemoved()) {
            return;
        }
        try {
            // Never touch player-owned party Pokemon (caught rewards, etc.).
            if (entity.getPokemon() != null && entity.getPokemon().isPlayerOwned()) {
                return;
            }
            // Mid capture / send-out beam — do not touch locks.
            if (entity.getBeamMode() != 0) {
                return;
            }
            if (entity.isBattling()) {
                return;
            }

            UUID battleId = entity.getBattleId();
            boolean staleBattle = battleId != null && BattleRegistry.getBattle(battleId) == null;
            if (battleId != null && (staleBattle || !entity.isBattling())) {
                entity.setBattleId(null);
            }

            List<Object> locks = entity.getBusyLocks();
            if (locks == null || locks.isEmpty()) {
                return;
            }
            // Surgical: remove only the battle lock when not in a live battle.
            Iterator<Object> it = locks.iterator();
            while (it.hasNext()) {
                Object lock = it.next();
                if (lock != null && BATTLE_BUSY_LOCK.equals(String.valueOf(lock))) {
                    it.remove();
                }
            }
        } catch (Throwable ignored) {
            // Best-effort only
        }
    }

    /**
     * Server-side red outline via glowing flag + scoreboard team color.
     * Uses the entity UUID as the scoreboard holder so clones of the same
     * species do not collide on one team entry.
     */
    public static void applyCatchCloneRedGlow(PokemonEntity entity) {
        if (entity == null || entity.isRemoved()) {
            return;
        }
        if (hasCatchCloneRedGlow(entity)) {
            return;
        }
        try {
            if (entity.getPokemon() != null && entity.getPokemon().isPlayerOwned()) {
                return;
            }
            if (!(entity.getWorld() instanceof ServerWorld world)) {
                return;
            }
            Scoreboard scoreboard = world.getScoreboard();
            Team team = scoreboard.getTeam(CATCH_GLOW_TEAM);
            if (team == null) {
                team = scoreboard.addTeam(CATCH_GLOW_TEAM);
                team.setColor(Formatting.RED);
                team.setFriendlyFireAllowed(true);
            } else {
                team.setColor(Formatting.RED);
            }
            String holder = entity.getUuidAsString();
            Team current = scoreboard.getScoreHolderTeam(holder);
            if (current != team) {
                scoreboard.addScoreHolderToTeam(holder, team);
            }
            entity.setGlowing(true);
        } catch (Throwable ignored) {
            // Best-effort only
        }
    }

    /** Skip scoreboard work when the clone already has the catch glow applied. */
    public static boolean hasCatchCloneRedGlow(PokemonEntity entity) {
        if (entity == null || entity.isRemoved() || !entity.isGlowing()) {
            return false;
        }
        try {
            if (!(entity.getWorld() instanceof ServerWorld world)) {
                return false;
            }
            Team team = world.getScoreboard().getScoreHolderTeam(entity.getUuidAsString());
            return team != null && CATCH_GLOW_TEAM.equals(team.getName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Clears glowing/team membership before discard (scoreboard hygiene). */
    public static void clearCatchCloneRedGlow(PokemonEntity entity) {
        if (entity == null) {
            return;
        }
        try {
            entity.setGlowing(false);
            if (!(entity.getWorld() instanceof ServerWorld world)) {
                return;
            }
            Scoreboard scoreboard = world.getScoreboard();
            String holder = entity.getUuidAsString();
            Team team = scoreboard.getScoreHolderTeam(holder);
            if (team != null && CATCH_GLOW_TEAM.equals(team.getName())) {
                scoreboard.removeScoreHolderFromTeam(holder, team);
            }
            try {
                String legacy = entity.getNameForScoreboard();
                if (legacy != null && !legacy.equals(holder)) {
                    Team legacyTeam = scoreboard.getScoreHolderTeam(legacy);
                    if (legacyTeam != null && CATCH_GLOW_TEAM.equals(legacyTeam.getName())) {
                        scoreboard.removeScoreHolderFromTeam(legacy, legacyTeam);
                    }
                }
            } catch (Throwable ignored) {
                // ignore
            }
        } catch (Throwable ignored) {
            // Best-effort only
        }
    }

    public static void prepareCatchClone(PokemonEntity entity) {
        if (entity == null || entity.isRemoved()) {
            return;
        }
        if (entity.getPokemon() != null && entity.getPokemon().isPlayerOwned()) {
            // Caught reward — never re-anchor / glow / strip battle state.
            CatchEncounterTags.stripLeftoverTagsIfPlayerOwned(entity.getPokemon());
            return;
        }
        restoreVisibility(entity);
        clearBusyState(entity);
        try {
            entity.setNoGravity(true);
            entity.setMovementSpeed(0.0f);
            entity.setDrops(new DropTable());
            entity.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, -1, 9999, false, false));
        } catch (Throwable ignored) {
            // Best-effort only
        }
        applyCatchCloneRedGlow(entity);
    }

    public static double catchSpreadRadius(Raid raid) {
        Location location = raid.raidBossLocation();
        int playerCount = Math.max(raid.participatingPlayers().size(), 1);
        double radius = CATCH_SPAWN_RADIUS;
        if (playerCount > 1) {
            double needed = CATCH_MIN_SPACING / (2.0D * Math.sin(Math.PI / playerCount));
            if (needed > radius) {
                radius = needed;
            }
        }
        int border = location.borderRadius();
        if (border > 2) {
            radius = Math.min(radius, border - 1.0D);
        }
        return Math.max(2.0D, radius);
    }

    public static Vec3d catchSpawnPosition(Raid raid, UUID ownerUuid) {
        Location location = raid.raidBossLocation();
        Vec3d center = location.pos();
        int playerIndex = raid.getPlayerIndex(ownerUuid);
        if (playerIndex < 0) {
            playerIndex = 0;
        }
        int playerCount = Math.max(raid.participatingPlayers().size(), 1);
        double angle = (2.0D * Math.PI / playerCount) * playerIndex;
        double radius = catchSpreadRadius(raid);
        return new Vec3d(
                center.x + radius * Math.cos(angle),
                center.y,
                center.z + radius * Math.sin(angle)
        );
    }

    public static Vec3d catchPlayerStandPosition(Raid raid, UUID ownerUuid) {
        Vec3d clonePos = catchSpawnPosition(raid, ownerUuid);
        Vec3d center = raid.raidBossLocation().pos();
        double dx = clonePos.x - center.x;
        double dz = clonePos.z - center.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01D) {
            return new Vec3d(clonePos.x + CATCH_PLAYER_INSET, clonePos.y, clonePos.z);
        }
        double standRadius = Math.max(2.0D, len - CATCH_PLAYER_INSET);
        double scale = standRadius / len;
        return new Vec3d(center.x + dx * scale, clonePos.y, center.z + dz * scale);
    }

    public static void teleportOwnerToCatchSpot(Raid raid, ServerPlayerEntity player) {
        if (raid == null || player == null) {
            return;
        }
        Location location = raid.raidBossLocation();
        ServerWorld world = location.world();
        Vec3d stand = catchPlayerStandPosition(raid, player.getUuid());
        Vec3d clone = catchSpawnPosition(raid, player.getUuid());
        double y = stand.y;
        int chunkX = (int) Math.floor(stand.x / 16.0D);
        int chunkZ = (int) Math.floor(stand.z / 16.0D);
        world.setChunkForced(chunkX, chunkZ, true);
        while (!world.getBlockState(new BlockPos((int) stand.x, (int) y, (int) stand.z)).isAir()) {
            y++;
        }
        world.setChunkForced(chunkX, chunkZ, false);
        double dx = clone.x - stand.x;
        double dz = clone.z - stand.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.teleportTo(new TeleportTarget(world, new Vec3d(stand.x, y, stand.z), Vec3d.ZERO, yaw, 0.0f, teleported -> {}));
    }

    public static boolean cloneNeedsReanchor(PokemonEntity entity, Raid raid, UUID ownerUuid) {
        if (entity == null || raid == null || ownerUuid == null) {
            return false;
        }
        Vec3d expected = catchSpawnPosition(raid, ownerUuid);
        return entity.getPos().squaredDistanceTo(expected) > CATCH_REANCHOR_DISTANCE_SQ;
    }

    /**
     * During catch phase a thrown ball must hit the thrower's assigned clone only.
     * Other stacked/hidden clones and other players' sent-out Pokémon are skipped
     * so the projectile keeps flying (Cobblemon otherwise rejects them as "not wild"
     * / "in a battle" before NovaRaids can cancel).
     */
    public static boolean shouldPokeballIgnoreEntity(Entity projectileOwner, Entity hit) {
        if (!(projectileOwner instanceof ServerPlayerEntity thrower) || !(hit instanceof PokemonEntity target)) {
            return false;
        }
        if (NovaRaids.INSTANCE == null || !NovaRaids.LOADED) {
            return false;
        }
        Raid catchRaid = null;
        for (Raid raid : NovaRaids.INSTANCE.activeRaids().values()) {
            if (raid.stage() == 4 && raid.hasCatchAssignment(thrower.getUuid())) {
                catchRaid = raid;
                break;
            }
        }
        if (catchRaid == null) {
            return false;
        }
        PokemonEntity owned = catchRaid.findLiveCatchCloneForPlayer(thrower.getUuid());
        if (owned != null && owned.getUuid().equals(target.getUuid())) {
            return false;
        }
        return true;
    }

    public static void anchorCatchClone(Raid raid, PokemonEntity entity, UUID ownerUuid) {
        if (entity == null || entity.isRemoved() || raid == null || ownerUuid == null) {
            return;
        }
        if (entity.getPokemon() != null && entity.getPokemon().isPlayerOwned()) {
            CatchEncounterTags.stripLeftoverTagsIfPlayerOwned(entity.getPokemon());
            return;
        }
        prepareCatchClone(entity);
        try {
            ServerWorld world = raid.raidBossLocation().world();
            Vec3d pos = catchSpawnPosition(raid, ownerUuid);
            entity.teleportTo(new TeleportTarget(world, pos, Vec3d.ZERO, raid.raidBossLocation().bossFacingDirection(), entity.getPitch(), teleported -> {}));
        } catch (Throwable ignored) {
            // Best-effort only
        }
    }
}
