package me.unariginal.novaraids.utils;

import club.minnced.discord.webhook.WebhookClient;
import club.minnced.discord.webhook.send.AllowedMentions;
import club.minnced.discord.webhook.send.WebhookEmbed;
import club.minnced.discord.webhook.send.WebhookEmbedBuilder;
import club.minnced.discord.webhook.send.WebhookMessageBuilder;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.mojang.authlib.GameProfile;
import me.unariginal.novaraids.NovaRaids;

import java.awt.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.*;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.unariginal.novaraids.data.FieldData;
import me.unariginal.novaraids.managers.Raid;
import net.minecraft.util.UserCache;

public class WebhookHandler {
    private static final NovaRaids nr = NovaRaids.INSTANCE;
    private static final UserCache cache =  nr.server().getUserCache();
    public static boolean webhookToggle = false;
    public static List<String> blacklistedCategories = new ArrayList<>();
    public static List<String> blacklistedBosses = new ArrayList<>();
    public static String webhookUrl = "https://discord.com/api/webhooks/";
    public static String webhookUsername =  "Raid Alert!";
    public static String webhookAvatarUrl = "https://cdn.modrinth.com/data/MdwFAVRL/e54083a07bcd9436d1f8d2879b0d821a54588b9e.png";
    public static String rolePing = "<@&role_id_here>";
    public static int webhookUpdateRateSeconds = 15;
    public static boolean deleteIfNoFightPhase = true;
    public static boolean startEmbedEnabled = false;
    public static String startEmbedTitle = "%boss.id% Raid Has Started";
    public static List<FieldData> startEmbedFields = new ArrayList<>();
    public static boolean runningEmbedEnabled = false;
    public static String runningEmbedTitle = "%boss.id% Raid In Progress!";
    public static List<FieldData> runningEmbedFields = new ArrayList<>();
    public static FieldData runningEmbedLeaderboardField = null;
    public static boolean endEmbedEnabled = false;
    public static String endEmbedTitle = "%boss.id% Raid Has Ended";
    public static List<FieldData> endEmbedFields = new ArrayList<>();
    public static FieldData endEmbedLeaderboardField = null;
    public static boolean failedEmbedEnabled = false;
    public static String failedEmbedTitle = "Failed To Defeat %boss.id%!";
    public static List<FieldData> failedEmbedFields = new ArrayList<>();
    public static FieldData failedEmbedLeaderboardField = null;

    /**
     * Optional Cobblemon id → sprite basename overrides (no extension).
     * Keys match species showdownId, resource path, display name, or form showdownId (case-insensitive).
     * Example: {@code "your_custom_species": "existing-gen5ani-basename"}
     * Only use when a real GIF already exists in the sprite pack under that name.
     */
    public static final Map<String, String> spriteAliases = new LinkedHashMap<>();

    private static final String SPRITE_ROOT =
            "https://raw.githubusercontent.com/SanjiTheLord/cobblesouls-showdown-sprites/master/sprites/";
    private static final String SUBSTITUTE_URL = SPRITE_ROOT + "ani/substitute.gif";
    private static final Pattern ROLE_MENTION = Pattern.compile("<@&(\\d+)>");
    private static final Map<String, Boolean> urlAccessCache = new ConcurrentHashMap<>();
    private static final Map<String, String> thumbnailCache = new ConcurrentHashMap<>();

    public static WebhookClient webhook = null;

    /** How to retire a raid's Discord message so it no longer looks active. */
    public enum FinalizeReason {
        END,
        FAILED,
        CANCELLED
    }

    private static int hexToRGB(String hex) {
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        int hexVal = Integer.parseInt(hex, 16);
        int r = (hexVal >> 16) & 0xFF;
        int g = (hexVal >> 8) & 0xFF;
        int b = (hexVal) & 0xFF;
        return new Color(r, g, b).getRGB();
    }

    private static int genTypeColor(Pokemon pokemon) {
        return switch (pokemon.getPrimaryType().getName()) {
            case "bug" -> hexToRGB("91A119");
            case "dark" -> hexToRGB("624D4E");
            case "dragon" -> hexToRGB("5060E1");
            case "electric" -> hexToRGB("FAC000");
            case "fairy" -> hexToRGB("EF70EF");
            case "fighting" -> hexToRGB("FF8000");
            case "fire" -> hexToRGB("E62829");
            case "flying" -> hexToRGB("81B9EF");
            case "ghost" -> hexToRGB("704170");
            case "grass" -> hexToRGB("3FA129");
            case "ground" -> hexToRGB("915121");
            case "ice" -> hexToRGB("3DCEF3");
            case "poison" -> hexToRGB("9141CB");
            case "psychic" -> hexToRGB("EF4179");
            case "rock" -> hexToRGB("AFA981");
            case "steel" -> hexToRGB("60A1B8");
            case "water" -> hexToRGB("2980EF");
            default -> hexToRGB("9FA19F");
        };
    }

    private static String getThumbnailUrl(Pokemon pokemon) {
        String cacheKey = pokemon.showdownId() + "|" + (pokemon.getShiny() ? "shiny" : "normal");
        String cached = thumbnailCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String aniFolder = pokemon.getShiny() ? "gen5ani-shiny" : "gen5ani";
        String pngFolder = pokemon.getShiny() ? "gen5-shiny" : "gen5";

        for (String id : buildSpriteIdCandidates(pokemon)) {
            String aniUrl = SPRITE_ROOT + aniFolder + "/" + id + ".gif";
            if (isUrlAccessible(aniUrl)) {
                thumbnailCache.put(cacheKey, aniUrl);
                return aniUrl;
            }
        }

        for (String id : buildSpriteIdCandidates(pokemon)) {
            String pngUrl = SPRITE_ROOT + pngFolder + "/" + id + ".png";
            if (isUrlAccessible(pngUrl)) {
                thumbnailCache.put(cacheKey, pngUrl);
                return pngUrl;
            }
        }

        thumbnailCache.put(cacheKey, SUBSTITUTE_URL);
        return SUBSTITUTE_URL;
    }

    /**
     * Builds sprite basenames to try against the cobblesouls-showdown-sprites pack
     * without renaming files. Prefer Cobblemon/Showdown IDs, then hyphenated form
     * variants, then display-name fallbacks, then config aliases.
     */
    private static List<String> buildSpriteIdCandidates(Pokemon pokemon) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();

        String speciesShowdown = sanitizeSpriteId(pokemon.getSpecies().showdownId());
        String formShowdown = sanitizeSpriteId(pokemon.getForm().showdownId());
        String formOnly = sanitizeSpriteId(pokemon.getForm().formOnlyShowdownId());
        String speciesName = sanitizeSpriteId(pokemon.getSpecies().getName());
        String resourcePath = sanitizeSpriteId(pokemon.getSpecies().getResourceIdentifier().getPath());
        String pokemonShowdown = sanitizeSpriteId(pokemon.showdownId());
        boolean nonStandardForm = formOnly != null && !formOnly.isEmpty() && !formOnly.equalsIgnoreCase("normal");

        addAliasMatches(ids, pokemonShowdown, speciesShowdown, formShowdown, formOnly, speciesName, resourcePath);

        // Prefer Pokemon.showdownId() (skips appending "normal" on standard forms).
        addIfPresent(ids, pokemonShowdown);
        if (nonStandardForm) {
            addIfPresent(ids, formShowdown);
            addIfPresent(ids, speciesShowdown + "-" + formOnly);
            addIfPresent(ids, speciesName + "-" + formOnly);
            addIfPresent(ids, resourcePath + "-" + formOnly);
            addIfPresent(ids, speciesShowdown + formOnly);
        }
        addIfPresent(ids, speciesShowdown);
        addIfPresent(ids, speciesName);
        addIfPresent(ids, resourcePath);

        return new ArrayList<>(ids);
    }

    private static void addAliasMatches(Set<String> ids, String... keys) {
        for (String key : keys) {
            if (key == null || key.isEmpty()) {
                continue;
            }
            String alias = spriteAliases.get(key.toLowerCase(Locale.ROOT));
            if (alias != null && !alias.isBlank()) {
                addIfPresent(ids, sanitizeSpriteId(alias));
            }
        }
    }

    private static void addIfPresent(Set<String> ids, String id) {
        if (id != null && !id.isBlank()) {
            ids.add(id.toLowerCase(Locale.ROOT));
        }
    }

    private static String sanitizeSpriteId(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    private static boolean isUrlAccessible(String url) {
        Boolean cached = urlAccessCache.get(url);
        if (cached != null) {
            return cached;
        }
        boolean ok = false;
        try {
            URI uri = new URI(url);
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setInstanceFollowRedirects(true);
            connection.connect();
            int responseCode = connection.getResponseCode();
            ok = responseCode == HttpURLConnection.HTTP_OK;
            // Some CDNs reject HEAD — retry once with GET range-less if 403/405
            if (!ok && (responseCode == HttpURLConnection.HTTP_BAD_METHOD
                    || responseCode == HttpURLConnection.HTTP_FORBIDDEN)) {
                connection.disconnect();
                connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                connection.connect();
                ok = connection.getResponseCode() == HttpURLConnection.HTTP_OK;
            }
        } catch (Exception ignored) {
            ok = false;
        }
        urlAccessCache.put(url, ok);
        return ok;
    }

    public static void connectWebhook() {
        webhook = WebhookClient.withUrl(webhookUrl);
    }

    public static boolean isConnected() {
        return webhook != null;
    }

    public static void deleteWebhook(long id) throws ExecutionException, InterruptedException {
        if (webhook == null || id == 0L) {
            return;
        }
        webhook.delete(id).get();
    }

    /**
     * Retires a raid Discord message so it cannot keep saying "Has Started" / "In Progress".
     * Prefers editing to end/failed (or a cancelled author), then falls back to a new send,
     * then deletes the original message if edits still fail.
     *
     * @return true if Discord was updated or the message was deleted (or there was nothing to do)
     */
    public static boolean finalizeRaidWebhook(long messageId, Raid raid, FinalizeReason reason) {
        if (!webhookToggle || webhook == null || messageId == 0L || raid == null) {
            return true;
        }

        WebhookMessageBuilder preferred = null;
        switch (reason) {
            case END -> {
                if (endEmbedEnabled) {
                    preferred = buildEndRaidWebhook(raid);
                }
            }
            case FAILED -> {
                if (failedEmbedEnabled) {
                    preferred = buildFailedWebhook(raid);
                }
            }
            case CANCELLED -> preferred = buildCancelledWebhook(raid);
        }

        if (preferred != null) {
            if (tryEdit(messageId, preferred)) {
                return true;
            }
            // Edit failed (deleted message, rate limit, etc.) — post a fresh terminal embed.
            if (trySend(preferred)) {
                tryDeleteQuiet(messageId);
                return true;
            }
        }

        // No terminal embed configured, or both edit+send failed: remove the active-looking message.
        return tryDeleteQuiet(messageId);
    }

    private static boolean tryEdit(long messageId, WebhookMessageBuilder message) {
        try {
            webhook.edit(messageId, message.build()).get();
            return true;
        } catch (Exception e) {
            nr.logError("Failed to edit raid webhook " + messageId + ": " + e.getMessage());
            return false;
        }
    }

    private static boolean trySend(WebhookMessageBuilder message) {
        try {
            webhook.send(message.build()).get();
            return true;
        } catch (Exception e) {
            nr.logError("Failed to send fallback raid webhook: " + e.getMessage());
            return false;
        }
    }

    private static boolean tryDeleteQuiet(long messageId) {
        try {
            deleteWebhook(messageId);
            return true;
        } catch (Exception e) {
            nr.logError("Failed to delete raid webhook " + messageId + ": " + e.getMessage());
            return false;
        }
    }

    /** Minimal embed used when a raid is stopped/cleaned up without a normal end/fail. */
    public static WebhookMessageBuilder buildCancelledWebhook(Raid raid) {
        Pokemon pokemon = raid.raidBossPokemon();
        int randColor = genTypeColor(pokemon);
        String thumbnailUrl = getThumbnailUrl(pokemon);
        String title = TextUtils.parse("%boss.name% Raid Cancelled", raid);

        WebhookEmbed embed = new WebhookEmbedBuilder()
                .setColor(randColor)
                .setAuthor(new WebhookEmbed.EmbedAuthor(title, "", thumbnailUrl))
                .setThumbnailUrl(thumbnailUrl)
                .addField(new WebhookEmbed.EmbedField(false, "Status", "This raid is no longer active on the server."))
                .build();

        return new WebhookMessageBuilder()
                .setUsername(webhookUsername)
                .setAvatarUrl(webhookAvatarUrl)
                .setContent("")
                .setAllowedMentions(AllowedMentions.none())
                .addEmbeds(embed);
    }

    public static long sendStartRaidWebhook(Raid raid) throws ExecutionException, InterruptedException {
        // Ping configured role only on the initial summon message (not on later edits).
        return webhook.send(buildStartRaidWebhook(raid, true).build()).get().getId();
    }

    public static void editStartRaidWebhook(long id, Raid raid) throws ExecutionException, InterruptedException {
        webhook.edit(id, buildStartRaidWebhook(raid, false).build()).get();
    }

    /** Non-blocking variant for server tick — avoids freezing the thread on Discord I/O. */
    public static void editStartRaidWebhookAsync(long id, Raid raid) {
        if (webhook == null || id == 0L) {
            return;
        }
        try {
            webhook.edit(id, buildStartRaidWebhook(raid, false).build()).whenComplete((ignored, ex) -> {
                if (ex != null) {
                    nr.logError("Failed to edit raid_start webhook: " + ex.getMessage());
                }
            });
        } catch (Exception e) {
            nr.logError("Failed to edit raid_start webhook: " + e.getMessage());
        }
    }

    public static WebhookMessageBuilder buildStartRaidWebhook(Raid raid) {
        return buildStartRaidWebhook(raid, false);
    }

    public static WebhookMessageBuilder buildStartRaidWebhook(Raid raid, boolean pingRole) {
        Pokemon pokemon = raid.raidBossPokemon();
        int randColor = genTypeColor(pokemon);
        String thumbnailUrl = getThumbnailUrl(pokemon);

        WebhookEmbedBuilder embedBuilder = new WebhookEmbedBuilder()
                .setColor(randColor)
                .setAuthor(
                        new WebhookEmbed.EmbedAuthor(
                                TextUtils.parse(startEmbedTitle, raid),
                                "",
                                thumbnailUrl
                        )
                );
        for (FieldData field : startEmbedFields) {
            embedBuilder.addField(new WebhookEmbed.EmbedField(field.inline(), TextUtils.parse(field.name(), raid), TextUtils.parse(field.value(), raid)));
        }
        embedBuilder.setThumbnailUrl(thumbnailUrl);
        WebhookEmbed embed = embedBuilder.build();

        WebhookMessageBuilder message = new WebhookMessageBuilder()
                .setUsername(webhookUsername)
                .setAvatarUrl(webhookAvatarUrl)
                .addEmbeds(embed);

        if (pingRole) {
            applyRolePing(message);
        } else {
            // Edits must not re-notify @Raids every webhook_update_rate_seconds.
            message.setContent("").setAllowedMentions(AllowedMentions.none());
        }
        return message;
    }

    /**
     * Puts {@link #rolePing} in message content and enables Discord allowed_mentions
     * so the role actually gets notified (webhook default often strips role pings).
     */
    private static void applyRolePing(WebhookMessageBuilder message) {
        if (rolePing == null || rolePing.isBlank()
                || rolePing.contains("role_id_here")
                || rolePing.contains("PUT_RAIDS_ROLE_ID_HERE")) {
            message.setContent("").setAllowedMentions(AllowedMentions.none());
            return;
        }
        String content = rolePing.trim();
        message.setContent(content);

        Matcher matcher = ROLE_MENTION.matcher(content);
        List<String> roleIds = new ArrayList<>();
        while (matcher.find()) {
            roleIds.add(matcher.group(1));
        }

        AllowedMentions mentions = new AllowedMentions()
                .withParseEveryone(false)
                .withParseUsers(false);
        if (!roleIds.isEmpty()) {
            mentions.withRoles(roleIds);
        } else {
            // Free-form text like "@Raids" won't ping; still allow role parse if IDs were used oddly.
            mentions.withParseRoles(true);
        }
        message.setAllowedMentions(mentions);
    }

    public static void sendEndRaidWebhook(long id, Raid raid) throws ExecutionException, InterruptedException {
        if (id == 0L) {
            if (endEmbedEnabled && webhook != null) {
                webhook.send(buildEndRaidWebhook(raid).build()).get();
            }
            return;
        }
        finalizeRaidWebhook(id, raid, FinalizeReason.END);
    }

    public static void editEndRaidWebhook(long id, Raid raid) throws ExecutionException, InterruptedException {
        webhook.edit(id, buildEndRaidWebhook(raid).build()).get();
    }

    public static WebhookMessageBuilder buildEndRaidWebhook(Raid raid) {
        Pokemon pokemon = raid.raidBossPokemon();
        int randColor = genTypeColor(pokemon);
        String thumbnailUrl = getThumbnailUrl(pokemon);

        WebhookEmbedBuilder embedBuilder = new WebhookEmbedBuilder()
                .setColor(randColor)
                .setAuthor(
                        new WebhookEmbed.EmbedAuthor(
                                TextUtils.parse(endEmbedTitle, raid),
                                "",
                                thumbnailUrl
                        )
                );

        for (FieldData field : endEmbedFields) {
            embedBuilder.addField(new WebhookEmbed.EmbedField(field.inline(), TextUtils.parse(field.name(), raid), TextUtils.parse(field.value(), raid)));
            if (field.insertLeaderboardAfter()) {
                List<Map.Entry<String, Integer>> entries = raid.getDamageLeaderboard();

                for (int i = 0; i < Math.min(entries.size(), 10); i++) {
                    Map.Entry<String, Integer> entry = entries.get(i);
                    if (cache != null) {
                        Optional<GameProfile> userOpt = cache.findByName(entry.getKey());
                        if (userOpt.isEmpty()) {
                            continue;
                        }
                        GameProfile user = userOpt.get();
                        String name = TextUtils.parse(endEmbedLeaderboardField.name(), raid, user, entry.getValue(), i + 1);
                        String value = TextUtils.parse(endEmbedLeaderboardField.value(), raid, user, entry.getValue(), i + 1);
                        embedBuilder.addField(new WebhookEmbed.EmbedField(endEmbedLeaderboardField.inline(), name, value));
                    }
                }
            }
        }

        embedBuilder.setThumbnailUrl(thumbnailUrl);
        WebhookEmbed embed = embedBuilder.build();

        return new WebhookMessageBuilder()
                .setUsername(webhookUsername)
                .setAvatarUrl(webhookAvatarUrl)
                .setContent("")
                .setAllowedMentions(AllowedMentions.none())
                .addEmbeds(embed);
    }

    public static long sendRunningWebhook(long id, Raid raid) throws ExecutionException, InterruptedException {
        if (id == 0) {
            return webhook.send(buildRunningWebhook(raid).build()).get().getId();
        }
        editRunningWebhook(id, raid);
        return id;
    }

    public static void editRunningWebhook(long id, Raid raid) throws ExecutionException, InterruptedException {
        webhook.edit(id, buildRunningWebhook(raid).build()).get();
    }

    /** Non-blocking variant for server tick — avoids freezing the thread on Discord I/O. */
    public static void editRunningWebhookAsync(long id, Raid raid) {
        if (webhook == null || id == 0L) {
            return;
        }
        try {
            webhook.edit(id, buildRunningWebhook(raid).build()).whenComplete((ignored, ex) -> {
                if (ex != null) {
                    nr.logError("Failed to edit raid_running webhook: " + ex.getMessage());
                }
            });
        } catch (Exception e) {
            nr.logError("Failed to edit raid_running webhook: " + e.getMessage());
        }
    }

    public static WebhookMessageBuilder buildRunningWebhook(Raid raid) {
        Pokemon pokemon = raid.raidBossPokemon();
        int randColor = genTypeColor(pokemon);
        String thumbnailUrl = getThumbnailUrl(pokemon);

        WebhookEmbedBuilder embedBuilder = new WebhookEmbedBuilder()
                .setColor(randColor)
                .setAuthor(
                        new WebhookEmbed.EmbedAuthor(
                                TextUtils.parse(runningEmbedTitle, raid),
                                "",
                                thumbnailUrl
                        )
                );

        for (FieldData field : runningEmbedFields) {
            embedBuilder.addField(new WebhookEmbed.EmbedField(field.inline(), TextUtils.parse(field.name(), raid), TextUtils.parse(field.value(), raid)));
            if (field.insertLeaderboardAfter()) {
                List<Map.Entry<String, Integer>> entries = raid.getDamageLeaderboard();

                for (int i = 0; i < Math.min(entries.size(), 10); i++) {
                    Map.Entry<String, Integer> entry = entries.get(i);
                    if (cache != null) {
                        Optional<GameProfile> userOpt = cache.findByName(entry.getKey());
                        if (userOpt.isEmpty()) {
                            continue;
                        }
                        GameProfile user = userOpt.get();
                        String name = TextUtils.parse(runningEmbedLeaderboardField.name(), raid, user, entry.getValue(), i + 1);
                        String value = TextUtils.parse(runningEmbedLeaderboardField.value(), raid, user, entry.getValue(), i + 1);
                        embedBuilder.addField(new WebhookEmbed.EmbedField(runningEmbedLeaderboardField.inline(), name, value));
                    }
                }
            }
        }

        embedBuilder.setThumbnailUrl(thumbnailUrl);
        WebhookEmbed embed = embedBuilder.build();

        return new WebhookMessageBuilder()
                .setUsername(webhookUsername)
                .setAvatarUrl(webhookAvatarUrl)
                .setContent("")
                .setAllowedMentions(AllowedMentions.none())
                .addEmbeds(embed);
    }

    public static void sendFailedWebhook(long id, Raid raid) throws ExecutionException, InterruptedException {
        if (id == 0L) {
            if (failedEmbedEnabled && webhook != null) {
                webhook.send(buildFailedWebhook(raid).build()).get();
            }
            return;
        }
        finalizeRaidWebhook(id, raid, FinalizeReason.FAILED);
    }

    public static void editFailedWebhook(long id, Raid raid) throws ExecutionException, InterruptedException {
        webhook.edit(id, buildFailedWebhook(raid).build()).get();
    }

    public static WebhookMessageBuilder buildFailedWebhook(Raid raid) {
        Pokemon pokemon = raid.raidBossPokemon();
        int randColor = genTypeColor(pokemon);
        String thumbnailUrl = getThumbnailUrl(pokemon);

        WebhookEmbedBuilder embedBuilder = new WebhookEmbedBuilder()
                .setColor(randColor)
                .setAuthor(
                        new WebhookEmbed.EmbedAuthor(
                                TextUtils.parse(failedEmbedTitle, raid),
                                "",
                                thumbnailUrl
                        )
                );

        for (FieldData field : failedEmbedFields) {
            embedBuilder.addField(new WebhookEmbed.EmbedField(field.inline(), TextUtils.parse(field.name(), raid), TextUtils.parse(field.value(), raid)));
            if (field.insertLeaderboardAfter()) {
                List<Map.Entry<String, Integer>> entries = raid.getDamageLeaderboard();

                for (int i = 0; i < Math.min(entries.size(), 10); i++) {
                    Map.Entry<String, Integer> entry = entries.get(i);
                    if (cache != null) {
                        Optional<GameProfile> userOpt = cache.findByName(entry.getKey());
                        if (userOpt.isEmpty()) {
                            continue;
                        }
                        GameProfile user = userOpt.get();
                        String name = TextUtils.parse(failedEmbedLeaderboardField.name(), raid, user, entry.getValue(), i + 1);
                        String value = TextUtils.parse(failedEmbedLeaderboardField.value(), raid, user, entry.getValue(), i + 1);
                        embedBuilder.addField(new WebhookEmbed.EmbedField(failedEmbedLeaderboardField.inline(), name, value));
                    }
                }
            }
        }

        embedBuilder.setThumbnailUrl(thumbnailUrl);
        WebhookEmbed embed = embedBuilder.build();

        return new WebhookMessageBuilder()
                .setUsername(webhookUsername)
                .setAvatarUrl(webhookAvatarUrl)
                .setContent("")
                .setAllowedMentions(AllowedMentions.none())
                .addEmbeds(embed);
    }
}