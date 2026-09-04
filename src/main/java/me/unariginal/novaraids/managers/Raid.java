package me.unariginal.novaraids.managers;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.drop.DropTable;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.properties.UncatchableProperty;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import kotlin.Unit;
import me.unariginal.novaraids.NovaRaids;
import me.unariginal.novaraids.config.MessagesConfig;
import me.unariginal.novaraids.data.BossbarData;
import me.unariginal.novaraids.data.CatchEncounterAssignment;
import me.unariginal.novaraids.data.Category;
import me.unariginal.novaraids.data.Location;
import me.unariginal.novaraids.data.Task;
import me.unariginal.novaraids.data.bosssettings.Boss;
import me.unariginal.novaraids.data.bosssettings.CatchPlacement;
import me.unariginal.novaraids.data.rewards.DistributionSection;
import me.unariginal.novaraids.data.rewards.Place;
import me.unariginal.novaraids.data.rewards.RewardPool;
import me.unariginal.novaraids.managers.BattleManager;
import me.unariginal.novaraids.utils.BanHandler;
import me.unariginal.novaraids.utils.BattleStopSafety;
import me.unariginal.novaraids.utils.CatchEncounterTags;
import me.unariginal.novaraids.utils.CatchEncounterEntityHelper;
import me.unariginal.novaraids.utils.NovaRaidsPermissions;
import me.unariginal.novaraids.utils.RandomUtils;
import me.unariginal.novaraids.utils.TextUtils;
import me.unariginal.novaraids.utils.WebhookHandler;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.UserCache;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.commons.lang3.StringUtils;

public class Raid {
    private final NovaRaids nr = NovaRaids.INSTANCE;
    private final MessagesConfig messages = this.nr.messagesConfig();
    private final UUID uuid;
    private final Boss bossInfo;
    private final Pokemon raidBossPokemon;
    private final Pokemon raidBossPokemonUncatchable;
    private final PokemonEntity raidBossEntity;
    private final Location raidBossLocation;
    private final Category raidBossCategory;
    private int currentHealth;
    private int maxHealth;
    private final UUID startedBy;
    private final ItemStack startingItem;
    private final int minPlayers;
    private final int maxPlayers;
    private final List<UUID> participatingPlayers = new ArrayList<UUID>();
    private final List<UUID> markForDeletion = new ArrayList<UUID>();
    private boolean clearToDelete = true;
    private final Map<UUID, Integer> damageByPlayer = new HashMap<UUID, Integer>();
    private final List<UUID> latestDamage = new ArrayList<UUID>();
    private final List<UUID> fleeingPlayers = new ArrayList<UUID>();
    private final Map<Long, List<Task>> tasks = new HashMap<Long, List<Task>>();
    private final Map<UUID, BossBar> playerBossbars = new HashMap<UUID, BossBar>();
    /** Identity map — PokemonEntity equals/hashCode must not break ownership lookups mid-tick. */
    private final Map<PokemonEntity, UUID> clones = new java.util.IdentityHashMap<PokemonEntity, UUID>();
    private final Map<UUID, CatchEncounterAssignment> catchAssignments = new HashMap<UUID, CatchEncounterAssignment>();
    private final Map<UUID, Long> catchRespawnBlockedUntil = new HashMap<UUID, Long>();
    private final List<EmptyPokeBallEntity> pokeballsCapturing = new ArrayList<EmptyPokeBallEntity>();
    private long raidStartTime = 0L;
    private long raidEndTime = 0L;
    private long phaseLength;
    private long phaseStartTime;
    private long fightStartTime;
    private long fightEndTime;
    private BossbarData bossbarData;
    private long webhook = 0L;
    private int stage;

    public Raid(Boss bossInfo, Location raidBossLocation, UUID startedBy, ItemStack startingItem) {
        this.bossInfo = bossInfo;
        this.raidBossLocation = raidBossLocation;
        this.startedBy = startedBy;
        this.startingItem = startingItem;
        if (startingItem != null) {
            startingItem.setCount(1);
        }
        this.raidBossPokemon = bossInfo.pokemonDetails().createPokemon(false);
        this.raidBossPokemonUncatchable = bossInfo.pokemonDetails().createPokemon(false);
        this.raidBossPokemonUncatchable.getCustomProperties().add(UncatchableProperty.INSTANCE.uncatchable());
        this.raidBossEntity = this.generateBossEntity();
        this.raidBossEntity.setBodyYaw(raidBossLocation.bossFacingDirection());
        this.uuid = this.raidBossEntity.getUuid();
        this.currentHealth = this.maxHealth = bossInfo.baseHealth();
        this.raidBossCategory = this.nr.bossesConfig().getCategory(bossInfo.categoryId());
        this.minPlayers = this.raidBossCategory.minPlayers();
        this.maxPlayers = this.raidBossCategory.maxPlayers();
        this.stage = 0;
        this.raidStartTime = this.nr.server().getOverworld().getTime();
        this.setupPhase();
    }

    public void stop() {
        this.stage = -1;
        this.clearRaidWebhook(WebhookHandler.FinalizeReason.CANCELLED);
        if (this.raidBossEntity != null && this.raidBossEntity.isAlive() && !this.raidBossEntity.isRemoved()) {
            // discard: avoid LivingDeathEvent → third-party Entity.save on raid Pokemon
            this.raidBossEntity.discard();
        }
        // Clear catch tags before ending battles so Nuzlocke does not restore orphans.
        this.clearCatchEncounterTagsEverywhere();
        this.endBattles();
        if (!this.raidBossLocation.onComplete().isEmpty()) {
            System.out.println("Testing onComplete");
            ServerCommandSource commandSOurce = ServerLifecycleHooks.getCurrentServer().getCommandSource();
            CommandManager commandExecutor = ServerLifecycleHooks.getCurrentServer().getCommandManager();
            commandExecutor.executeWithPrefix(commandSOurce, this.raidBossLocation.onComplete());
        }
        this.cleanupCapturingPokeballs();
        ArrayList<PokemonEntity> toRemove = new ArrayList<PokemonEntity>(this.clones.keySet());
        for (PokemonEntity pokemon : toRemove) {
            this.removeClone(pokemon, false);
        }
        this.clones.clear();
        this.discardOrphanCatchEncounters();
        for (UUID playerUUID : this.playerBossbars.keySet()) {
            ServerPlayerEntity player = this.nr.server().getPlayerManager().getPlayer(playerUUID);
            if (player == null) continue;
            ((Audience)player).hideBossBar(this.playerBossbars.get(playerUUID));
        }
        this.raidEndTime = this.nr.server().getOverworld().getTime();
        this.nr.initNextRaid();
    }

    /** Clears Discord so stop/end/fail cannot leave an active-looking embed behind. */
    private void clearRaidWebhook(WebhookHandler.FinalizeReason reason) {
        if (!WebhookHandler.webhookToggle || this.webhook == 0L) {
            return;
        }
        long id = this.webhook;
        this.webhook = 0L;
        try {
            WebhookHandler.finalizeRaidWebhook(id, this, reason);
        } catch (Exception e) {
            this.nr.logError("Failed to finalize raid webhook: " + e.getMessage());
            try {
                WebhookHandler.deleteWebhook(id);
            } catch (Exception deleteError) {
                this.nr.logError("Failed to delete raid webhook after finalize error: " + deleteError.getMessage());
            }
        }
    }

    public void setupPhase() {
        ServerPlayerEntity player;
        this.stage = 1;
        this.bossbarData = this.nr.bossbarsConfig().getBossbar(this.bossInfo, "setup");
        this.showBossbar(this.bossbarData);
        this.phaseLength = this.bossInfo.raidDetails().setupPhaseTime();
        this.phaseStartTime = this.nr.server().getOverworld().getTime();
        this.broadcast(TextUtils.deserialize(TextUtils.parse(this.messages.getMessage("start_pre_phase"), this)));
        this.nr.messagesConfig().executeCommand(this);
        if (WebhookHandler.webhookToggle && WebhookHandler.startEmbedEnabled && !WebhookHandler.blacklistedBosses.contains(this.bossInfo.bossId()) && !WebhookHandler.blacklistedCategories.contains(this.raidBossCategory.id())) {
            try {
                this.webhook = WebhookHandler.sendStartRaidWebhook(this);
            }
            catch (InterruptedException | ExecutionException e) {
                this.nr.logError("Failed to send raid_start webhook: " + e.getMessage());
            }
        }
        this.addTask(this.raidBossLocation.world(), this.phaseLength * 20L, this::fightPhase);
        if (this.nr.config().vouchersJoinRaids && this.startedBy != null && this.startingItem != null && this.addPlayer(this.startedBy, true) && (player = this.nr.server().getPlayerManager().getPlayer(this.startedBy)) != null) {
            player.sendMessage(TextUtils.deserialize(TextUtils.parse(this.nr.messagesConfig().getMessage("joined_raid"), this)));
        }
    }

    public void fightPhase() {
        if (this.participatingPlayers.size() >= this.minPlayers && !this.participatingPlayers.isEmpty()) {
            this.stage = 2;
            this.bossbarData = this.nr.bossbarsConfig().getBossbar(this.bossInfo, "fight");
            this.showBossbar(this.bossbarData);
            this.phaseLength = this.bossInfo.raidDetails().fightPhaseTime();
            this.fightStartTime = this.phaseStartTime = this.nr.server().getOverworld().getTime();
            this.participatingBroadcast(TextUtils.deserialize(TextUtils.parse(this.messages.getMessage("start_fight_phase"), this)));
            if (WebhookHandler.webhookToggle && this.webhook != 0L && WebhookHandler.runningEmbedEnabled) {
                try {
                    this.webhook = WebhookHandler.sendRunningWebhook(this.webhook, this);
                }
                catch (InterruptedException | ExecutionException e) {
                    this.nr.logError("Failed to send raid_running webhook: " + e.getMessage());
                }
            }
            this.addTask(this.raidBossLocation.world(), this.phaseLength * 20L, this::raidLost);
        } else {
            ServerPlayerEntity player;
            this.stage = -1;
            this.participatingBroadcast(TextUtils.deserialize(TextUtils.parse(this.messages.getMessage("not_enough_players"), this)));
            this.onError(this.participantsOnline());
            if (this.raidBossCategory.requirePass() && this.startingItem != null && (player = this.nr.server().getPlayerManager().getPlayer(this.startedBy)) != null) {
                player.giveItemStack(this.startingItem);
            }
            if (WebhookHandler.webhookToggle && this.webhook != 0L) {
                if (WebhookHandler.deleteIfNoFightPhase) {
                    long id = this.webhook;
                    this.webhook = 0L;
                    try {
                        WebhookHandler.deleteWebhook(id);
                    }
                    catch (InterruptedException | ExecutionException e) {
                        this.nr.logError("Failed to delete webhook: " + e.getMessage());
                        WebhookHandler.finalizeRaidWebhook(id, this, WebhookHandler.FinalizeReason.CANCELLED);
                    }
                } else {
                    this.clearRaidWebhook(WebhookHandler.FinalizeReason.CANCELLED);
                }
            }
        }
    }

    public void raidLost() {
        this.stage = -1;
        this.tasks.clear();
        this.raidEndTime = this.nr.server().getOverworld().getTime();
        this.participatingBroadcast(TextUtils.deserialize(TextUtils.parse(this.messages.getMessage("out_of_time"), this)));
        this.onDefeat(this.participantsOnline());
        if (WebhookHandler.webhookToggle && this.webhook != 0L) {
            if (WebhookHandler.failedEmbedEnabled) {
                this.clearRaidWebhook(WebhookHandler.FinalizeReason.FAILED);
            } else {
                this.clearRaidWebhook(WebhookHandler.FinalizeReason.CANCELLED);
            }
        }
    }

    public void preCatchPhase() {
        this.stage = 3;
        if (this.bossInfo.raidDetails().doCatchPhase()) {
            this.bossbarData = this.nr.bossbarsConfig().getBossbar(this.bossInfo, "pre_catch");
            this.showBossbar(this.bossbarData);
            this.phaseLength = this.bossInfo.raidDetails().preCatchPhaseTime();
        }
        this.fightEndTime = this.phaseStartTime = this.nr.server().getOverworld().getTime();
        this.tasks.clear();
        this.endBattles();
        if (this.raidBossEntity != null && !this.raidBossEntity.isRemoved()) {
            this.raidBossEntity.discard();
        }
        this.handleRewards();
        try {
            this.nr.config().writeResults(this);
        }
        catch (IOException | NoSuchElementException e) {
            this.nr.logError("Failed to write raid information to history file.");
        }
        if (WebhookHandler.webhookToggle && this.webhook != 0L) {
            if (WebhookHandler.endEmbedEnabled) {
                this.clearRaidWebhook(WebhookHandler.FinalizeReason.END);
            } else {
                this.clearRaidWebhook(WebhookHandler.FinalizeReason.CANCELLED);
            }
        }
        if (this.bossInfo.raidDetails().doCatchPhase()) {
            this.participatingBroadcast(TextUtils.deserialize(TextUtils.parse(this.messages.getMessage("catch_phase_warning"), this)));
            this.addTask(this.raidBossLocation.world(), this.phaseLength * 20L, this::catchPhase);
        } else {
            this.raidWon();
        }
    }

    public void catchPhase() {
        this.stage = 4;
        this.bossbarData = this.nr.bossbarsConfig().getBossbar(this.bossInfo, "catch");
        this.showBossbar(this.bossbarData);
        this.phaseLength = this.bossInfo.raidDetails().catchPhaseTime();
        this.phaseStartTime = this.nr.server().getOverworld().getTime();
        ArrayList<ServerPlayerEntity> alreadyCatching = new ArrayList<ServerPlayerEntity>();
        for (CatchPlacement placement : this.bossInfo.catchSettings().catchPlacements()) {
            ArrayList<ServerPlayerEntity> playersToReward = new ArrayList<ServerPlayerEntity>();
            if (StringUtils.isNumeric(placement.place())) {
                ServerPlayerEntity player;
                int placeIndex = Integer.parseInt(placement.place());
                if (--placeIndex >= 0 && placeIndex < this.getDamageLeaderboard().size() && (player = this.nr.server().getPlayerManager().getPlayer(this.getDamageLeaderboard().get(placeIndex).getKey())) != null && !alreadyCatching.contains(player) && (!placement.requireDamage() || this.damageByPlayer.containsKey(player.getUuid()) && this.damageByPlayer.get(player.getUuid()) > 0)) {
                    playersToReward.add(player);
                }
            } else if (placement.place().contains("%")) {
                String percentStr = placement.place().replace("%", "");
                if (StringUtils.isNumeric(percentStr)) {
                    int percent = Integer.parseInt(percentStr);
                    double positions = (double)this.getDamageLeaderboard().size() * ((double)percent / 100.0);
                    for (int i = 0; i < (int)positions; ++i) {
                        ServerPlayerEntity player = this.nr.server().getPlayerManager().getPlayer(this.getDamageLeaderboard().get(i).getKey());
                        if (player == null || alreadyCatching.contains(player) || placement.requireDamage() && (!this.damageByPlayer.containsKey(player.getUuid()) || this.damageByPlayer.get(player.getUuid()) <= 0)) continue;
                        playersToReward.add(player);
                    }
                }
            } else if (placement.place().equalsIgnoreCase("participating")) {
                for (UUID participatingUUID : this.participatingPlayers) {
                    ServerPlayerEntity player = this.nr.server().getPlayerManager().getPlayer(participatingUUID);
                    if (player == null || alreadyCatching.contains(player)) continue;
                    boolean valid = false;
                    if (placement.requireDamage()) {
                        if (this.damageByPlayer.containsKey(player.getUuid()) && this.damageByPlayer.get(player.getUuid()) > 0) {
                            valid = true;
                        }
                    } else {
                        valid = true;
                    }
                    if (!valid) continue;
                    playersToReward.add(player);
                }
            }
            for (ServerPlayerEntity player : playersToReward) {
                if (player == null) continue;
                alreadyCatching.add(player);
                CatchEncounterEntityHelper.teleportOwnerToCatchSpot(this, player);
                BattleManager.invokeCatchEncounter(this, player, (float)placement.shinyChance(), placement.minPerfectIvs());
            }
        }
        this.participatingBroadcast(TextUtils.deserialize(TextUtils.parse(this.messages.getMessage("start_catch_phase"), this)));
        this.addTask(this.raidBossLocation.world(), this.phaseLength * 20L, this::raidWon);
        this.checkCatchPhaseComplete();
    }

    public void raidWon() {
        if (this.stage == -1) {
            return;
        }
        this.tasks.clear();
        this.purgeDeadCatchClones();
        // Strip tags FIRST so battle.end() recalls/discards instead of restoring via Nuzlocke.
        this.clearCatchEncounterTagsEverywhere();
        // End catch battles before removing entities.
        this.endBattles();
        this.cleanupCapturingPokeballs();
        this.catchAssignments.clear();
        this.catchRespawnBlockedUntil.clear();
        for (PokemonEntity clone : new ArrayList<>(this.clones.keySet())) {
            this.removeClone(clone, false);
        }
        this.clones.clear();
        // Catch entities that left the clones map under lag (mid-shake / respawn desync).
        this.discardOrphanCatchEncounters();
        this.stage = -1;
        this.raidEndTime = this.nr.server().getOverworld().getTime();
        if (this.bossInfo.raidDetails().doCatchPhase()) {
            this.participatingBroadcast(TextUtils.deserialize(TextUtils.parse(this.messages.getMessage("catch_phase_end"), this)));
        }
        this.participatingBroadcast(TextUtils.deserialize(TextUtils.parse(this.messages.getMessage("raid_end"), this)));
        this.onVictory(this.participantsOnline());
    }

    /**
     * Clears catch_encounter tags on tracked clones and mid-capture pokeball targets
     * before battles end, so persistent-catch restore paths do not re-anchor leftovers.
     */
    private void clearCatchEncounterTagsEverywhere() {
        for (PokemonEntity clone : new ArrayList<>(this.clones.keySet())) {
            if (clone != null) {
                CatchEncounterTags.clearCatchEncounterTags(clone.getPokemon());
            }
        }
        for (EmptyPokeBallEntity ball : new ArrayList<>(this.pokeballsCapturing)) {
            if (ball == null) {
                continue;
            }
            PokemonEntity capturing = ball.getCapturingPokemon();
            if (capturing != null) {
                CatchEncounterTags.clearCatchEncounterTags(capturing.getPokemon());
            }
        }
    }

    private void cleanupCapturingPokeballs() {
        ArrayList<EmptyPokeBallEntity> pokeballs = new ArrayList<>(this.pokeballsCapturing);
        for (EmptyPokeBallEntity entity : pokeballs) {
            if (entity != null && entity.isAlive() && !entity.isRemoved()) {
                PokemonEntity capturing = entity.getCapturingPokemon();
                entity.remove(Entity.RemovalReason.DISCARDED);
                if (capturing != null && !capturing.isRemoved()) {
                    capturing.remove(Entity.RemovalReason.DISCARDED);
                }
            }
            this.removePokeballsCapturing(entity);
        }
    }

    /**
     * Removes catch-phase Pokémon near the arena that belong to this raid but are
     * no longer tracked in {@link #clones} (common after lag discard/respawn).
     * Never discards player-owned Pokemon or clones still registered in the map.
     */
    private void discardOrphanCatchEncounters() {
        ServerWorld world = this.raidBossLocation.world();
        if (world == null) {
            return;
        }
        double radius = Math.max(40.0D, this.raidBossLocation.borderRadius() * 2.0D);
        Vec3d center = this.raidBossLocation.pos();
        Box area = new Box(
                center.x - radius, center.y - 16.0D, center.z - radius,
                center.x + radius, center.y + 16.0D, center.z + radius
        );
        UUID raidId = this.uuid;
        for (PokemonEntity entity : world.getEntitiesByClass(PokemonEntity.class, area, e -> true)) {
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            Pokemon pokemon = entity.getPokemon();
            if (pokemon == null) {
                continue;
            }
            // Never discard a player's caught Pokemon (shine Zapdos send-out bug).
            if (pokemon.isPlayerOwned()) {
                if (CatchEncounterTags.hasCatchEncounterTags(pokemon)
                        || pokemon.getPersistentData().contains("raid_entity")) {
                    CatchEncounterTags.clearCatchEncounterTags(pokemon);
                }
                continue;
            }
            if (!CatchEncounterTags.isRaidCatchPokemon(pokemon)) {
                continue;
            }
            // Still tracked as a live catch clone — leave it alone.
            if (this.getCloneOwnerUuid(entity) != null) {
                continue;
            }
            UUID taggedRaid = CatchEncounterTags.getRaidUuid(pokemon);
            if (taggedRaid != null && !taggedRaid.equals(raidId)) {
                continue;
            }
            // Mid-capture: do not yank the mon out of the ball.
            if (entity.getBeamMode() != 0 || entity.isBattling()) {
                continue;
            }
            CatchEncounterTags.clearCatchEncounterTags(pokemon);
            entity.remove(Entity.RemovalReason.DISCARDED);
        }
    }

    public void handleRewards() {
        ServerPlayerEntity player;
        this.participatingBroadcast(TextUtils.deserialize(TextUtils.parse(this.messages.getMessage("leaderboard_message_header"), this)));
        int placeIndex = 0;
        for (Map.Entry<String, Integer> entry : this.getDamageLeaderboard()) {
            player = this.nr.server().getPlayerManager().getPlayer(entry.getKey());
            if (player == null) continue;
            this.participatingBroadcast(TextUtils.deserialize(TextUtils.parse(this.messages.getMessage("leaderboard_message_item"), this, player, (int)entry.getValue(), ++placeIndex)));
            if (placeIndex != 10) continue;
            break;
        }
        placeIndex = 0;
        for (Map.Entry<String, Integer> entry : this.getDamageLeaderboard()) {
            player = this.nr.server().getPlayerManager().getPlayer(entry.getKey());
            if (player == null) continue;
            player.sendMessage(TextUtils.deserialize(TextUtils.parse(this.messages.getMessage("leaderboard_individual"), this, player, (int)entry.getValue(), ++placeIndex)));
        }
        ArrayList<DistributionSection> categoryRewards = new ArrayList<DistributionSection>(this.raidBossCategory.rewards());
        ArrayList<DistributionSection> bossRewards = new ArrayList<DistributionSection>(this.bossInfo.raidDetails().rewards());
        ArrayList<DistributionSection> rewards = new ArrayList<DistributionSection>(bossRewards);
        if (!this.bossInfo.raidDetails().overrideCategoryDistribution()) {
            ArrayList<Place> overriddenPlacements = new ArrayList<Place>();
            for (DistributionSection bossReward : bossRewards) {
                List<Place> places = bossReward.places();
                for (Place place : places) {
                    if (!place.overrideCategoryReward()) continue;
                    overriddenPlacements.add(place);
                }
            }
            for (DistributionSection categoryReward : categoryRewards) {
                boolean overridden = false;
                List<Place> places = categoryReward.places();
                block5: for (Place place : places) {
                    for (Place place2 : overriddenPlacements) {
                        if (!place2.place().equalsIgnoreCase(place.place())) continue;
                        overridden = true;
                        break block5;
                    }
                }
                if (overridden) continue;
                rewards.add(categoryReward);
            }
        }
        HashMap<ServerPlayerEntity, String> noMoreRewards = new HashMap<ServerPlayerEntity, String>();
        for (DistributionSection reward : rewards) {
            List<Place> places = reward.places();
            for (Place place : places) {
                ArrayList<ServerPlayerEntity> playersToReward = new ArrayList<ServerPlayerEntity>();
                if (StringUtils.isNumeric(place.place())) {
                    ServerPlayerEntity serverPlayer;
                    int placeAsInt = Integer.parseInt(place.place());
                    if (--placeAsInt >= 0 && placeAsInt < this.getDamageLeaderboard().size() && (serverPlayer = this.nr.server().getPlayerManager().getPlayer(this.getDamageLeaderboard().get(placeAsInt).getKey())) != null && this.damageByPlayer.containsKey(serverPlayer.getUuid()) && (!place.requireDamage() || this.damageByPlayer.get(serverPlayer.getUuid()) > 0)) {
                        playersToReward.add(serverPlayer);
                    }
                } else if (place.place().contains("%")) {
                    String percentStr = place.place().replace("%", "");
                    if (StringUtils.isNumeric(percentStr)) {
                        int n = Integer.parseInt(percentStr);
                        double positions = (double)this.getDamageLeaderboard().size() * ((double)n / 100.0);
                        for (int i = 0; i < (int)Math.ceil(positions); ++i) {
                            ServerPlayerEntity player3 = this.nr.server().getPlayerManager().getPlayer(this.getDamageLeaderboard().get(i).getKey());
                            if (player3 == null || !this.damageByPlayer.containsKey(player3.getUuid()) || place.requireDamage() && this.damageByPlayer.get(player3.getUuid()) <= 0) continue;
                            playersToReward.add(player3);
                        }
                    }
                } else if (place.place().equalsIgnoreCase("participating")) {
                    for (UUID uUID : this.participatingPlayers) {
                        ServerPlayerEntity player4 = this.nr.server().getPlayerManager().getPlayer(uUID);
                        if (player4 == null) continue;
                        boolean valid = false;
                        if (place.requireDamage()) {
                            if (this.damageByPlayer.containsKey(player4.getUuid()) && this.damageByPlayer.get(player4.getUuid()) > 0) {
                                valid = true;
                            }
                        } else {
                            valid = true;
                        }
                        if (!valid) continue;
                        playersToReward.add(player4);
                    }
                }
                for (ServerPlayerEntity serverPlayer : playersToReward) {
                    if (serverPlayer == null) continue;
                    boolean duplicatePlacementExists = false;
                    int placeCount = 0;
                    for (DistributionSection rewardSection : rewards) {
                        List<Place> rewardPlaces = rewardSection.places();
                        for (Place rewardPlace : rewardPlaces) {
                            if (!rewardPlace.place().equalsIgnoreCase(place.place())) continue;
                            ++placeCount;
                            break;
                        }
                        if (placeCount < 2) continue;
                        duplicatePlacementExists = true;
                        break;
                    }
                    if (noMoreRewards.containsKey(serverPlayer) && (!duplicatePlacementExists || !place.place().equalsIgnoreCase(noMoreRewards.get(serverPlayer)))) continue;
                    int rolls = new Random().nextInt(reward.minRolls(), reward.maxRolls() + 1);
                    ArrayList<UUID> distributedPools = new ArrayList<UUID>();
                    // Never retry with i-- when rolls > unique pools (hangs forever with allow_duplicates:false).
                    HashMap<RewardPool, Double> availablePools = new HashMap<>(reward.pools());
                    if (!reward.allowDuplicates()) {
                        rolls = Math.min(rolls, availablePools.size());
                    }
                    for (int i = 0; i < rolls; ++i) {
                        if (!reward.allowDuplicates()) {
                            availablePools.entrySet().removeIf(e -> distributedPools.contains(e.getKey().uuid()));
                        }
                        if (availablePools.isEmpty()) {
                            break;
                        }
                        Map.Entry<?, Double> poolEntry = RandomUtils.getRandomEntry(availablePools);
                        if (poolEntry != null) {
                            RewardPool pool = (RewardPool)poolEntry.getKey();
                            pool.distributeRewards(serverPlayer);
                            distributedPools.add(pool.uuid());
                        } else {
                            this.nr.logError("Pool was null!");
                            break;
                        }
                    }
                }
                for (ServerPlayerEntity serverPlayer : playersToReward) {
                    if (place.allowOtherRewards() || noMoreRewards.containsKey(serverPlayer)) continue;
                    noMoreRewards.put(serverPlayer, place.place());
                }
            }
        }
    }

    public void addTask(ServerWorld world, Long delay, Runnable action) {
        long currentTick = NovaRaids.INSTANCE.server().getOverworld().getTime();
        long executeTick = currentTick + delay;
        Task task = new Task(world, executeTick, action);
        if (this.tasks.containsKey(executeTick)) {
            ArrayList<Task> taskList = new ArrayList<Task>(this.tasks.get(executeTick));
            taskList.add(task);
            this.tasks.put(executeTick, taskList);
        } else {
            this.tasks.put(executeTick, List.of(task));
        }
    }

    public void removeTask(long executeTick) {
        this.tasks.remove(executeTick);
    }

    public Map<Long, List<Task>> getTasks() {
        return this.tasks;
    }

    public void fixBossPosition() {
        if (this.stage != -1 && this.stage != 0 && this.raidBossEntity != null) {
            Vec3d current = this.raidBossEntity.getPos();
            Vec3d expected = this.raidBossLocation.pos();
            // Use distance, not reference != — getPos() always returns a new Vec3d.
            if (current.squaredDistanceTo(expected) > 0.01D) {
                this.raidBossEntity.teleportTo(new TeleportTarget(this.raidBossLocation.world(), expected, Vec3d.ZERO, this.raidBossLocation.bossFacingDirection(), 0.0f, a -> {}));
            }
        }
        if (this.stage == 4) {
            this.maintainCatchClones();
        }
    }

    /**
     * Keeps live catch encounters visible and at the arena after flee or lag.
     */
    public void maintainCatchClones() {
        this.purgeDeadCatchClones();
        for (Map.Entry<PokemonEntity, UUID> entry : this.clones.entrySet()) {
            PokemonEntity clone = entry.getKey();
            UUID ownerUuid = entry.getValue();
            if (clone == null || clone.isRemoved() || !clone.isAlive()) {
                continue;
            }
            if (!CatchEncounterTags.isCatchEncounter(clone.getPokemon())) {
                continue;
            }
            // Keep red glow. Only clear a stale battle lock (never wipe capture/send-out locks).
            if (!clone.isBattling() && clone.getBeamMode() == 0) {
                CatchEncounterEntityHelper.clearBusyState(clone);
            }
            if (!CatchEncounterEntityHelper.hasCatchCloneRedGlow(clone)) {
                CatchEncounterEntityHelper.applyCatchCloneRedGlow(clone);
            }
            if (this.catchAssignments.containsKey(ownerUuid)) {
                ServerPlayerEntity owner = this.nr.server().getPlayerManager().getPlayer(ownerUuid);
                if (owner != null && BattleRegistry.getBattleByParticipatingPlayer(owner) != null) {
                    continue;
                }
                // Do not re-anchor while a pokeball is capturing this clone.
                if (clone.getBeamMode() != 0) {
                    continue;
                }
                // Teleport only when the clone drifted. Re-anchoring every tick resyncs
                // full PokemonEntity custom payloads and can disconnect nearby clients.
                if (CatchEncounterEntityHelper.cloneNeedsReanchor(clone, this, ownerUuid)) {
                    CatchEncounterEntityHelper.anchorCatchClone(this, clone, ownerUuid);
                }
            }
        }
        this.checkCatchPhaseComplete();
    }

    public void purgeDeadCatchClones() {
        List<PokemonEntity> dead = new ArrayList<>();
        for (PokemonEntity clone : this.clones.keySet()) {
            if (clone == null || clone.isRemoved() || !clone.isAlive()) {
                dead.add(clone);
            }
        }
        for (PokemonEntity removed : dead) {
            this.clones.remove(removed);
        }
    }

    public void removeCatchClonesForOwner(UUID ownerUuid) {
        if (ownerUuid == null) {
            return;
        }
        List<PokemonEntity> toRemove = new ArrayList<>();
        for (Map.Entry<PokemonEntity, UUID> entry : this.clones.entrySet()) {
            if (ownerUuid.equals(entry.getValue())) {
                toRemove.add(entry.getKey());
            }
        }
        for (PokemonEntity clone : toRemove) {
            this.removeClone(clone, false);
        }
    }

    /**
     * Ends a player's catch attempt after a successful capture: clears assignment,
     * removes world clones, and advances the catch phase when everyone is done.
     */
    public void completeCatchForOwner(UUID ownerUuid, Pokemon capturedPokemon) {
        if (ownerUuid == null) {
            return;
        }
        this.clearCatchAssignment(ownerUuid);
        PokemonEntity capturedEntity = capturedPokemon != null ? capturedPokemon.getEntity() : null;
        List<PokemonEntity> ownerClones = new ArrayList<>();
        for (Map.Entry<PokemonEntity, UUID> entry : this.clones.entrySet()) {
            if (ownerUuid.equals(entry.getValue())) {
                ownerClones.add(entry.getKey());
            }
        }
        for (PokemonEntity clone : ownerClones) {
            this.clones.remove(clone);
            this.nr.unregisterCatchCloneEntity(clone.getUuid());
            if (clone != capturedEntity) {
                this.removeClone(clone, false);
            } else {
                CatchEncounterEntityHelper.clearCatchCloneRedGlow(clone);
            }
        }
        if (capturedEntity != null && capturedEntity.isAlive() && !capturedEntity.isRemoved()) {
            this.clones.remove(capturedEntity);
            this.nr.unregisterCatchCloneEntity(capturedEntity.getUuid());
            CatchEncounterEntityHelper.clearCatchCloneRedGlow(capturedEntity);
            // Discard while catch tags still apply so other clients never receive a
            // spawn packet for the now-owned entity (custom-payload disconnect).
            if (capturedEntity.getBeamMode() == 0) {
                try {
                    capturedEntity.discard();
                } catch (Exception e) {
                    this.nr.logError("completeCatch discard failed: " + e.getMessage());
                }
            }
        }
        if (capturedPokemon != null) {
            CatchEncounterTags.clearCatchEncounterTags(capturedPokemon);
        }
        this.checkCatchPhaseComplete();
    }

    private PokemonEntity generateBossEntity() {
        ServerWorld world = this.raidBossLocation.world();
        Vec3d pos = this.raidBossLocation.pos();
        return this.raidBossPokemonUncatchable.sendOut(world, pos, null, entity -> {
            entity.setPersistent();
            entity.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 999999, 9999, true, false));
            entity.setMovementSpeed(0.0f);
            entity.setNoGravity(true);
            entity.setAiDisabled(true);
            if (this.bossInfo.applyGlowing()) {
                entity.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 999999, 9999, true, false));
            }
            entity.setInvulnerable(true);
            entity.setBodyYaw(this.raidBossLocation.bossFacingDirection());
            entity.setDrops(new DropTable());
            Box hitbox = entity.getBoundingBox();
            hitbox.stretch(new Vec3d(this.raidBossPokemonUncatchable.getScaleModifier(), this.raidBossPokemonUncatchable.getScaleModifier(), this.raidBossPokemonUncatchable.getScaleModifier()));
            entity.setBoundingBox(hitbox);
            return Unit.INSTANCE;
        });
    }

    private void endBattles() {
        for (UUID playerUUID : this.participatingPlayers) {
            PokemonBattle battle;
            ServerPlayerEntity player = this.nr.server().getPlayerManager().getPlayer(playerUUID);
            if (player == null || (battle = BattleRegistry.getBattleByParticipatingPlayer(player)) == null) continue;
            BattleStopSafety.safeStop(battle);
        }
    }

    public UUID uuid() {
        return this.uuid;
    }

    public int stage() {
        return this.stage;
    }

    public String getPhase() {
        return switch (this.stage) {
            case -1 -> "Stopping";
            case 0 -> "Constructor";
            case 1 -> "Setup";
            case 2 -> "Fight";
            case 3 -> "Pre-Catch";
            case 4 -> "Catch";
            default -> "Error";
        };
    }

    public int maxPlayers() {
        return this.maxPlayers;
    }

    public int minPlayers() {
        return this.minPlayers;
    }

    public long raidStartTime() {
        return this.raidStartTime;
    }

    public long raidEndTime() {
        return this.raidEndTime;
    }

    public long raidCompletionTime() {
        if (this.raidEndTime() > 0L) {
            return this.raidEndTime() - this.raidStartTime();
        }
        return 0L;
    }

    public long raidTimer() {
        return this.nr.server().getOverworld().getTime() - this.raidStartTime;
    }

    public long bossDefeatTime() {
        return this.fightEndTime - this.fightStartTime;
    }

    public BossbarData bossbarData() {
        return this.bossbarData;
    }

    public long phaseStartTime() {
        return this.phaseStartTime;
    }

    public long phaseLength() {
        return this.phaseLength;
    }

    public long phaseEndTime() {
        return this.phaseStartTime + this.phaseLength * 20L;
    }

    public Boss bossInfo() {
        return this.bossInfo;
    }

    public Pokemon raidBossPokemon() {
        return this.raidBossPokemon;
    }

    public Pokemon raidBossPokemonUncatchable() {
        return this.raidBossPokemonUncatchable;
    }

    public Category raidBossCategory() {
        return this.raidBossCategory;
    }

    public Location raidBossLocation() {
        return this.raidBossLocation;
    }

    public int currentHealth() {
        return this.currentHealth;
    }

    public void applyDamage(int damage) {
        this.currentHealth -= damage;
    }

    public int maxHealth() {
        return this.maxHealth;
    }

    public void broadcast(Text text) {
        this.nr.server().getPlayerManager().getPlayerList().forEach(p -> p.sendMessage(text));
    }

    public void participatingBroadcast(Text text) {
        for (UUID playerUuid : this.participatingPlayers) {
            ServerPlayerEntity player = this.nr.server().getPlayerManager().getPlayer(playerUuid);
            if (player == null) continue;
            player.sendMessage(text);
        }
    }

    public void addClone(PokemonEntity pokemon, ServerPlayerEntity player) {
        for (PokemonEntity clone : this.clones.keySet()) {
            if (!clone.getUuid().equals(pokemon.getUuid())) continue;
            this.clones.put(clone, player.getUuid());
            this.nr.registerCatchCloneEntity(clone.getUuid(), player.getUuid());
            return;
        }
        this.clones.put(pokemon, player.getUuid());
        this.nr.registerCatchCloneEntity(pokemon.getUuid(), player.getUuid());
    }

    /**
     * Resolves catch/battle clone ownership from the live clones map, matching by
     * reference or entity UUID (survives IdentityHashMap misses after re-wrap).
     */
    public UUID getCloneOwnerUuid(PokemonEntity pokemonEntity) {
        if (pokemonEntity == null) {
            return null;
        }
        UUID direct = this.clones.get(pokemonEntity);
        if (direct != null) {
            return direct;
        }
        UUID entityId = pokemonEntity.getUuid();
        for (Map.Entry<PokemonEntity, UUID> entry : this.clones.entrySet()) {
            PokemonEntity clone = entry.getKey();
            if (clone != null && clone.getUuid().equals(entityId)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public void registerCatchAssignment(ServerPlayerEntity player, float shinyChance, int minPerfectIvs) {
        this.catchAssignments.put(player.getUuid(), new CatchEncounterAssignment(player.getUuid(), shinyChance, minPerfectIvs));
    }

    public boolean hasCatchAssignment(UUID playerUuid) {
        return this.catchAssignments.containsKey(playerUuid);
    }

    public CatchEncounterAssignment getCatchAssignment(UUID playerUuid) {
        return this.catchAssignments.get(playerUuid);
    }

    public void clearCatchAssignment(UUID playerUuid) {
        this.catchAssignments.remove(playerUuid);
        this.catchRespawnBlockedUntil.remove(playerUuid);
    }

    public PokemonEntity findLiveCatchCloneForPlayer(UUID playerUuid) {
        for (Map.Entry<PokemonEntity, UUID> entry : this.clones.entrySet()) {
            PokemonEntity clone = entry.getKey();
            if (!entry.getValue().equals(playerUuid)) {
                continue;
            }
            if (clone != null && clone.isAlive() && !clone.isRemoved()) {
                return clone;
            }
        }
        return null;
    }

    /**
     * Keeps a catch encounter in the world after flee/disconnect so the owner can
     * re-engage. Battle teardown is handled by Cobblemon flee/stop.
     */
    public void releaseCatchClone(PokemonEntity clone) {
        if (clone == null) {
            return;
        }
        UUID ownerUuid = CatchEncounterTags.getOwnerUuid(clone);
        if (ownerUuid == null) {
            this.removeClone(clone, false);
            return;
        }

        CatchEncounterEntityHelper.anchorCatchClone(this, clone, ownerUuid);
        this.clones.put(clone, ownerUuid);
        this.nr.registerCatchCloneEntity(clone.getUuid(), ownerUuid);
    }

    public void releaseCatchCloneForPlayer(UUID playerUuid) {
        PokemonEntity clone = this.findLiveCatchCloneForPlayer(playerUuid);
        if (clone != null) {
            this.releaseCatchClone(clone);
        }
    }

    /**
     * Spawns a fresh catch encounter when the player reconnects or their clone
     * was discarded during lag, as long as the catch phase is still active.
     * Does not auto-start a battle — the owner re-engages by battling the clone.
     */
    public void respawnCatchEncounterIfNeeded(ServerPlayerEntity player) {
        if (this.stage != 4 || player == null) {
            return;
        }
        CatchEncounterAssignment assignment = this.catchAssignments.get(player.getUuid());
        if (assignment == null) {
            return;
        }
        if (this.findLiveCatchCloneForPlayer(player.getUuid()) != null) {
            return;
        }
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            return;
        }

        long now = System.currentTimeMillis();
        Long blockedUntil = this.catchRespawnBlockedUntil.get(player.getUuid());
        if (blockedUntil != null && now < blockedUntil) {
            return;
        }

        PokemonEntity clone = BattleManager.spawnCatchEncounterClone(
                this,
                player,
                assignment.shinyChance(),
                assignment.minPerfectIvs()
        );
        this.catchRespawnBlockedUntil.put(
                player.getUuid(),
                now + BattleStopSafety.catchRespawnCooldownMs()
        );

        if (clone != null) {
            this.nr.logInfo("Respawned catch encounter for " + player.getName().getString());
        }
    }

    public void finalizeCatchClone(PokemonEntity clone) {
        if (clone == null) {
            return;
        }
        UUID ownerUuid = CatchEncounterTags.getOwnerUuid(clone);
        if (ownerUuid != null) {
            this.abandonCatchForOwner(ownerUuid);
            return;
        }
        this.removeClone(clone, false);
    }

    /**
     * Ends a player's catch attempt without a capture (defeat/KO). Clears assignment,
     * removes world clones, and advances the catch phase when everyone is done.
     */
    public void abandonCatchForOwner(UUID ownerUuid) {
        if (ownerUuid == null || !this.catchAssignments.containsKey(ownerUuid)) {
            return;
        }
        this.clearCatchAssignment(ownerUuid);
        this.removeCatchClonesForOwner(ownerUuid);
        this.checkCatchPhaseComplete();
    }

    public void removeClone(PokemonEntity clone, boolean fromFlee) {
        if (clone != null && !fromFlee && CatchEncounterTags.isCatchEncounter(clone.getPokemon())) {
            CatchEncounterTags.clearCatchEncounterTags(clone.getPokemon());
        }
        if (clone != null && !clone.isRemoved()) {
            PokemonBattle battle;
            int chunkX = (int)Math.floor(clone.getPos().getX() / 16.0);
            int chunkZ = (int)Math.floor(clone.getPos().getZ() / 16.0);
            ServerWorld world = this.nr.server().getOverworld();
            for (ServerWorld worldLoop : this.nr.server().getWorlds()) {
                if (!worldLoop.getDimension().equals(clone.getWorld().getDimension())) continue;
                world = worldLoop;
            }
            world.setChunkForced(chunkX, chunkZ, true);
            if (!fromFlee && clone.isBattling() && clone.getBattleId() != null && (battle = BattleRegistry.getBattle(clone.getBattleId())) != null) {
                BattleStopSafety.safeStop(battle);
            }
            // Prefer discard over kill(): catch clones often have held item cleared to air
            // (BattleManager keepHeldItem=false / PreventDrops) and may have levelOverride 0.
            // kill() fires LivingDeathEvent → Souls MobKilledListener → Entity.save() →
            // Pokemon.saveToNBT IllegalStateException (level [1;99], item not air) and can
            // take down the dedicated server. discard() removes without death hooks.
            CatchEncounterEntityHelper.clearCatchCloneRedGlow(clone);
            try {
                clone.discard();
            } catch (Exception e) {
                this.nr.logError("removeClone discard failed, forcing removal: " + e.getMessage());
                try {
                    clone.remove(Entity.RemovalReason.DISCARDED);
                } catch (Exception e2) {
                    this.nr.logError("removeClone force remove failed: " + e2.getMessage());
                }
            }
            world.setChunkForced(chunkX, chunkZ, false);
        }
        this.clones.remove(clone);
        if (clone != null) {
            this.nr.unregisterCatchCloneEntity(clone.getUuid());
        }
        this.checkCatchPhaseComplete();
    }

    public void checkCatchPhaseComplete() {
        if (this.stage != 4) {
            return;
        }
        this.purgeDeadCatchClones();
        if (this.catchAssignments.isEmpty()) {
            this.raidWon();
        }
    }

    public Map<PokemonEntity, UUID> getClones() {
        return this.clones;
    }

    public List<UUID> participatingPlayers() {
        return this.participatingPlayers;
    }

    public int getPlayerIndex(UUID playerUUID) {
        for (int index = 0; index < this.participatingPlayers.size(); ++index) {
            if (!this.participatingPlayers.get(index).equals(playerUUID)) continue;
            return index;
        }
        return -1;
    }

    public void removePlayer(UUID playerUUID) {
        int index = this.getPlayerIndex(playerUUID);
        if (index == -1) {
            return;
        }

        // During catch phase keep the player eligible and preserve their encounter.
        if (this.stage == 4 && this.hasCatchAssignment(playerUUID)) {
            this.releaseCatchCloneForPlayer(playerUUID);
            ServerPlayerEntity player = this.nr.server().getPlayerManager().getPlayer(playerUUID);
            if (player != null) {
                PokemonBattle battle = BattleRegistry.getBattleByParticipatingPlayer(player);
                if (battle != null) {
                    BattleStopSafety.safeStop(battle);
                }
                BossBar bar = this.bossbars().get(playerUUID);
                if (bar != null) {
                    ((Audience)player).hideBossBar(bar);
                }
            }
            return;
        }

        this.markForDeletion.add(playerUUID);
        ServerPlayerEntity player = this.nr.server().getPlayerManager().getPlayer(playerUUID);
        if (player != null) {
            PokemonBattle battle = BattleRegistry.getBattleByParticipatingPlayer(player);
            if (battle != null) {
                BattleStopSafety.safeStop(battle);
            }
            BossBar bar = this.bossbars().get(playerUUID);
            if (bar != null) {
                ((Audience)player).hideBossBar(bar);
            }
        }
        this.playerBossbars.remove(playerUUID);
        ArrayList<PokemonEntity> toRemove = new ArrayList<PokemonEntity>();
        for (PokemonEntity clone : this.clones.keySet()) {
            if (!this.clones.get(clone).equals(playerUUID)) continue;
            toRemove.add(clone);
        }
        for (PokemonEntity clone : toRemove) {
            this.removeClone(clone, false);
        }
    }

    public void removePlayers() {
        if (this.clearToDelete) {
            this.participatingPlayers().removeAll(this.markForDeletion);
            this.markForDeletion.clear();
        }
    }

    public long getCurrentWebhookID() {
        return this.webhook;
    }

    public boolean addPlayer(UUID playerUUID, boolean usedPass) {
        int index = this.getPlayerIndex(playerUUID);
        ServerPlayerEntity player = this.nr.server().getPlayerManager().getPlayer(playerUUID);
        if (player != null) {
            // One raid at a time — always enforced (override may skip pass/stage/contraband/levels only).
            if (index != -1 || this.nr.isPlayerParticipating(playerUUID)) {
                player.sendMessage(TextUtils.deserialize(TextUtils.parse(this.messages.getMessage("warning_already_joined_raid"), this)));
                return false;
            }
            if (!NovaRaidsPermissions.OVERRIDE.test(player)) {
                if (this.raidBossCategory().requirePass() && !usedPass) {
                    index = -2;
                    player.sendMessage(TextUtils.deserialize(TextUtils.parse(this.messages.getMessage("warning_no_pass"), this)));
                }
                if (this.stage != 1) {
                    index = -2;
                    player.sendMessage(TextUtils.deserialize(TextUtils.parse(this.messages.getMessage("warning_not_joinable"), this)));
                }
                if (BanHandler.hasContraband(player, this.bossInfo)) {
                    index = -2;
                }
                int numPokemon = 0;
                for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
                    if (pokemon == null) continue;
                    ++numPokemon;
                    if (pokemon.getLevel() < this.bossInfo.raidDetails().minimumLevel()) {
                        index = -2;
                        player.sendMessage(TextUtils.deserialize(TextUtils.parse(this.messages.getMessage("warning_minimum_level"), this)));
                        break;
                    }
                    if (pokemon.getLevel() <= this.bossInfo.raidDetails().maximumLevel()) continue;
                    index = -2;
                    player.sendMessage(TextUtils.deserialize(TextUtils.parse(this.messages.getMessage("warning_maximum_level"), this)));
                }
                if (numPokemon == 0) {
                    index = -2;
                    player.sendMessage(TextUtils.deserialize(TextUtils.parse(this.messages.getMessage("warning_no_pokemon"), this)));
                }
            } else {
                this.nr.logInfo("Player has permission override!");
            }
            if (index == -1) {
                this.participatingPlayers().add(playerUUID);
                if (this.participatingPlayers().size() > 1) {
                    this.maxHealth += this.bossInfo.healthIncreasePerPlayer();
                    this.currentHealth += this.bossInfo.healthIncreasePerPlayer();
                }
                this.showBossbar(this.bossbarData);
                this.teleportPlayerToJoinSpot(player);
            }
            return index == -1;
        }
        return false;
    }

    /** Blocks from boss center where join teleports land (matches catch-phase player inset). */
    private static final double JOIN_DISTANCE_FROM_BOSS = 3.0D;

    /** Teleport a successful join to a spot ~3 blocks from the boss, facing the boss. */
    private void teleportPlayerToJoinSpot(ServerPlayerEntity player) {
        Location loc = this.raidBossLocation;
        ServerWorld world = loc.world();
        Vec3d center = loc.pos();
        int playerIndex = this.getPlayerIndex(player.getUuid());
        if (playerIndex < 0) {
            playerIndex = Math.max(0, this.participatingPlayers().size() - 1);
        }
        int playerCount = Math.max(this.participatingPlayers().size(), 1);
        double x;
        double z;
        if (playerCount == 1) {
            double rad = Math.toRadians(loc.bossFacingDirection());
            x = center.x + (-Math.sin(rad)) * JOIN_DISTANCE_FROM_BOSS;
            z = center.z + Math.cos(rad) * JOIN_DISTANCE_FROM_BOSS;
        } else {
            double angle = (2.0D * Math.PI / playerCount) * playerIndex;
            x = center.x + JOIN_DISTANCE_FROM_BOSS * Math.cos(angle);
            z = center.z + JOIN_DISTANCE_FROM_BOSS * Math.sin(angle);
        }
        double y = center.y;
        int chunkX = (int) Math.floor(x / 16.0);
        int chunkZ = (int) Math.floor(z / 16.0);
        world.setChunkForced(chunkX, chunkZ, true);
        while (!world.getBlockState(new BlockPos((int) x, (int) y, (int) z)).isAir()) {
            y++;
        }
        world.setChunkForced(chunkX, chunkZ, false);
        double dx = center.x - x;
        double dz = center.z - z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.teleportTo(new TeleportTarget(world, new Vec3d(x, y, z), Vec3d.ZERO, yaw, 0.0f, a -> {}));
    }

    public void updatePlayerDamage(UUID playerUUID, int damage) {
        if (this.damageByPlayer.containsKey(playerUUID)) {
            damage += this.damageByPlayer.get(playerUUID).intValue();
        }
        this.damageByPlayer.put(playerUUID, damage);
        this.latestDamage.remove(playerUUID);
        this.latestDamage.add(playerUUID);
    }

    public List<Map.Entry<String, Integer>> getDamageLeaderboard() {
        ArrayList<Map.Entry<UUID, Integer>> leaderboardList = new ArrayList<Map.Entry<UUID, Integer>>(this.damageByPlayer.entrySet());
        Map<Integer, Long> damageFrequencies = leaderboardList.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)).values().stream().collect(Collectors.groupingBy(value -> value, Collectors.counting()));
        List<Integer> duplicates = damageFrequencies.entrySet().stream().filter(entry -> entry.getValue() > 1L).map(Map.Entry::getKey).toList();
        leaderboardList.sort((o1, o2) -> o2.getValue().compareTo(o1.getValue()));
        for (int n = leaderboardList.size(); n > 0 && n != 1; --n) {
            block1: for (int i = 0; i < n - 1; ++i) {
                Map.Entry<UUID, Integer> e1 = leaderboardList.get(i);
                Map.Entry<UUID, Integer> entry2 = leaderboardList.get(i + 1);
                boolean duplicate = false;
                for (int value2 : duplicates) {
                    if (e1.getValue() == value2) {
                        duplicate = true;
                        break;
                    }
                    if (entry2.getValue() != value2) continue;
                    duplicate = true;
                    break;
                }
                if (!duplicate || e1.getValue().compareTo(entry2.getValue()) != 0) continue;
                for (UUID uDmg : this.latestDamage) {
                    if (e1.getKey().equals(uDmg)) continue block1;
                    if (!entry2.getKey().equals(uDmg)) continue;
                    Map.Entry temp = leaderboardList.get(i);
                    leaderboardList.set(i, leaderboardList.get(i + 1));
                    leaderboardList.set(i + 1, temp);
                    continue block1;
                }
            }
        }
        ArrayList<Map.Entry<String, Integer>> sortedLeaderboard = new ArrayList<Map.Entry<String, Integer>>();
        UserCache cache = this.nr.server().getUserCache();
        if (cache != null) {
            for (Map.Entry<UUID, Integer> entry3 : leaderboardList) {
                Optional<GameProfile> profile = cache.getByUuid(entry3.getKey());
                if (profile.isPresent()) {
                    String name = ((GameProfile) profile.get()).getName();
                    sortedLeaderboard.add(Map.entry(name, (Integer) entry3.getValue()));
                }
            }
        }
        return sortedLeaderboard;
    }

    public void addPokeballsCapturing(EmptyPokeBallEntity entity) {
        this.pokeballsCapturing.add(entity);
    }

    public void removePokeballsCapturing(EmptyPokeBallEntity entity) {
        this.pokeballsCapturing.remove(entity);
    }

    public boolean isPlayerFleeing(UUID playerUUID) {
        return this.fleeingPlayers.contains(playerUUID);
    }

    public void addFleeingPlayer(UUID playerUUID) {
        this.fleeingPlayers.add(playerUUID);
    }

    public void removeFleeingPlayer(UUID playerUUID) {
        this.fleeingPlayers.remove(playerUUID);
    }

    public Map<UUID, BossBar> bossbars() {
        return this.playerBossbars;
    }

    private void showBossbar(BossbarData bossbar) {
        this.hideBossbar();
        if (bossbar != null) {
            for (UUID playerUUID : this.participatingPlayers) {
                this.clearToDelete = false;
                ServerPlayerEntity player = this.nr.server().getPlayerManager().getPlayer(playerUUID);
                if (player == null) continue;
                BossBar bar = bossbar.createBossBar(this);
                ((Audience)player).showBossBar(bar);
                this.playerBossbars.put(playerUUID, bar);
            }
            this.clearToDelete = true;
        }
    }

    private void hideBossbar() {
        for (UUID playerUUID : this.playerBossbars.keySet()) {
            net.minecraft.server.network.ServerPlayerEntity player = this.nr.server().getPlayerManager().getPlayer(playerUUID);
            if (player == null) continue;
            ((Audience)player).hideBossBar(this.playerBossbars.get(playerUUID));
        }
        this.playerBossbars.clear();
    }

    public void showOverlay(BossbarData bossbar) {
        if (bossbar != null && bossbar.useActionbar()) {
            for (UUID playerUUID : this.participatingPlayers) {
                this.clearToDelete = false;
                ServerPlayerEntity player = this.nr.server().getPlayerManager().getPlayer(playerUUID);
                if (player == null) continue;
                ((Audience)player).sendActionBar(TextUtils.deserializeAdventure(TextUtils.parse(bossbar.actionbarText(), this)));
            }
            this.clearToDelete = true;
        }
    }

    protected void runnCommands(List<String> commands, List<ServerPlayerEntity> players) {
        ServerCommandSource commandSOurce = ServerLifecycleHooks.getCurrentServer().getCommandSource();
        CommandManager commandExecutor = ServerLifecycleHooks.getCurrentServer().getCommandManager();
        if (!this.raidBossLocation.onComplete().isEmpty()) {
            System.out.println("Testing onComplete");
            commandExecutor.executeWithPrefix(commandSOurce, this.raidBossLocation.onComplete());
        }
        players.forEach(player -> {
            String name = player.getNameForScoreboard();
            System.out.println("Hoi Annoyed: " + name);
            for (String command : commands) {
                System.out.println("Hoi Running comamnds: " + command);
                command = command.replace("%player%", name);
                command = command.replace("%boss%", this.bossInfo.bossId());
                commandExecutor.executeWithPrefix(commandSOurce, command);
            }
        });
    }

    protected void onDefeat(List<ServerPlayerEntity> players) {
        this.runnCommands(this.bossInfo.onDefeat(), players);
    }

    protected void onError(List<ServerPlayerEntity> players) {
        this.runnCommands(this.bossInfo.onError(), players);
    }

    protected void onVictory(List<ServerPlayerEntity> players) {
        this.runnCommands(this.bossInfo.onVictory(), players);
    }

    private List<ServerPlayerEntity> participantsOnline() {
        ArrayList<ServerPlayerEntity> out = new ArrayList<ServerPlayerEntity>();
        for (UUID id : this.participatingPlayers) {
            ServerPlayerEntity p = this.nr.server().getPlayerManager().getPlayer(id);
            if (p == null) continue;
            out.add(p);
        }
        return out;
    }
}
