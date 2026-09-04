package me.unariginal.novaraids.data;

import java.util.UUID;

/**
 * Stored when a player is granted a catch-phase encounter so it can be
 * respawned after disconnect or entity discard during server lag.
 */
public record CatchEncounterAssignment(
        UUID playerUuid,
        float shinyChance,
        int minPerfectIvs
) {}
