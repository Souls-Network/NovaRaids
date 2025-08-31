package me.unariginal.novaraids;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.platform.events.PlatformEvents;
import kotlin.Unit;
import me.unariginal.novaraids.commands.RaidCommands;
import me.unariginal.novaraids.config.*;
import me.unariginal.novaraids.data.QueueItem;
import me.unariginal.novaraids.managers.EventManager;
import me.unariginal.novaraids.managers.Raid;
import me.unariginal.novaraids.managers.TickManager;
import me.unariginal.novaraids.utils.WebhookHandler;
import net.kyori.adventure.platform.modcommon.MinecraftServerAudiences;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

@Mod(NovaRaids.MOD_ID)
public class NovaRaids {
    public static final String MOD_ID = "novaraids";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static NovaRaids INSTANCE;
    public static boolean LOADED = true;

    private Config config;
    private LocationsConfig locationsConfig;
    private BossbarsConfig bossbarsConfig;
    private MessagesConfig messagesConfig;
    private SchedulesConfig schedulesConfig;
    private RewardPresetsConfig rewardPresetsConfig;
    private RewardPoolsConfig rewardPoolsConfig;
    private BossesConfig bossesConfig;
    private GuisConfig guisConfig;

    public boolean debug = false;
    private MinecraftServer server;
    private MinecraftServerAudiences audience;

    private final Map<Integer, Raid> activeRaids = new HashMap<>();
    private final Queue<QueueItem> queuedRaids = new LinkedList<>();

    public NovaRaids(IEventBus bus) {
        INSTANCE = this;

        NeoForge.EVENT_BUS.<RegisterCommandsEvent>addListener(event -> RaidCommands.init(event.getDispatcher()));

        // Set up event handlers and configuration at server load
        PlatformEvents.SERVER_STARTED.subscribe(Priority.NORMAL, server -> {
            this.server = server.getServer();
            this.audience = MinecraftServerAudiences.of(this.server);

            reloadConfig();
            if (LOADED) {
                EventManager.battleEvents();
                EventManager.rightClickEvents();
                EventManager.playerEvents();
                EventManager.cobblemonEvents();
                EventManager.captureEvent();
            } else {
                LOGGER.error("Config did not load properly!");
            }

            return Unit.INSTANCE;
        });

        // Server tick loop
        PlatformEvents.SERVER_TICK_POST.subscribe(Priority.NORMAL, server -> {
            if (LOADED) {
                try {
                    TickManager.updateWebhooks();
                    TickManager.fixBossPositions();
                    TickManager.handleDefeatedBosses();
                    TickManager.executeTasks();
                    TickManager.updateBossbars();
                    TickManager.fixPlayerPositions();
                    TickManager.fixPlayerPokemon();
                    TickManager.scheduledRaids();
                } catch (ConcurrentModificationException e) {
                    logInfo("Suppressing concurrent modification exception!");
                }
                for (Raid raid : activeRaids.values()) {
                    raid.removePlayers();
                }
            }
            return Unit.INSTANCE;
        });

        // Clean up at server stop
        PlatformEvents.SERVER_STOPPING.subscribe(Priority.NORMAL, server -> {
            if (LOADED) {
                for (QueueItem queue : queuedRaids) {
                    queue.cancelItem();
                }
                queuedRaids.clear();

                for (Raid raid : activeRaids.values()) {
                    raid.stop();
                }
                // TODO: Save current raid, write queue to file
            }

            return Unit.INSTANCE;
        });
    }

    public Config config() {
        return config;
    }
    public LocationsConfig locationsConfig() {
        return locationsConfig;
    }
    public BossbarsConfig bossbarsConfig() {
        return bossbarsConfig;
    }
    public MessagesConfig messagesConfig() {
        return messagesConfig;
    }
    public SchedulesConfig schedulesConfig() {
        return schedulesConfig;
    }
    public RewardPresetsConfig rewardPresetsConfig() {
        return rewardPresetsConfig;
    }
    public RewardPoolsConfig rewardPoolsConfig() {
        return rewardPoolsConfig;
    }
    public BossesConfig bossesConfig() {
        return bossesConfig;
    }
    public GuisConfig guisConfig() {
        return guisConfig;
    }

    public void reloadConfig() {
        config = new Config();
        locationsConfig = new LocationsConfig();
        bossbarsConfig = new BossbarsConfig();
        messagesConfig = new MessagesConfig();
        schedulesConfig = new SchedulesConfig();
        rewardPresetsConfig = new RewardPresetsConfig();
        rewardPoolsConfig = new RewardPoolsConfig();
        bossesConfig = new BossesConfig();
        guisConfig = new GuisConfig();
        if (WebhookHandler.webhookToggle) {
            WebhookHandler.connectWebhook();
        }
    }

    public MinecraftServer server() {
        return server;
    }

    public MinecraftServerAudiences audience() {
        return audience;
    }

    public Logger logger() {
        return LOGGER;
    }

    public void logInfo(String message) {
        if (debug) {
            logger().info("[NovaRaids] {}", message);
        }
    }

    public void logError(String message) {
        logger().error("[NovaRaids] {}", message);
    }

    public Map<Integer, Raid> activeRaids() {
        return activeRaids;
    }

    public Queue<QueueItem> queuedRaids() {
        return queuedRaids;
    }

    public void addQueueItem(QueueItem item) {
        if (!queuedRaids.contains(item)) {
            queuedRaids.add(item);
        } else {
            logInfo("Queue item already exists!");
        }
    }

    public void initNextRaid() {
        if (config.useQueueSystem) {
            if (!queuedRaids.isEmpty()) {
                queuedRaids.remove().startRaid();
            }
        }
    }

    public int getRaidId(Raid raid) {
        for (int key : activeRaids.keySet()) {
            if (activeRaids.get(key).uuid().equals(raid.uuid())) {
                return key;
            }
        }
        return -1;
    }

    public void addRaid(Raid raid) {
        if (getRaidId(raid) == -1) {
            int next_id = fixRaidIds();
            activeRaids.put(next_id, raid);
        }
    }

    public void removeRaid(Raid raid) {
        int id = getRaidId(raid);
        if (id != -1) {
            activeRaids.remove(id);
            fixRaidIds();
        }
    }

    public int fixRaidIds() {
        Map<Integer, Raid> newRaids = new HashMap<>();
        int count = 1;
        for (int key : activeRaids.keySet()) {
            Raid raid = activeRaids.get(key);
            newRaids.put(count, raid);
            count++;
        }
        activeRaids.clear();
        activeRaids.putAll(newRaids);
        return count;
    }
}