package me.unariginal.novaraids.data.rewards;

import com.google.gson.JsonObject;
import me.unariginal.novaraids.NovaRaids;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.*;

public record RewardPool(JsonObject poolObject, UUID uuid, String name, boolean allowDuplicates, int minRolls, int maxRolls, Map<Reward, Double> rewards) {
    public void distributeRewards(ServerPlayerEntity player) {
        // Identity-based: preset rewards share one instance by name; inline rewards may share
        // display names across pools. Tracking by name incorrectly zeroed remaining weight.
        Set<Reward> appliedRewards = Collections.newSetFromMap(new IdentityHashMap<>());

        int rolls = new Random().nextInt(minRolls(), maxRolls() + 1);
        if (!allowDuplicates()) {
            int eligible = 0;
            for (Map.Entry<Reward, Double> entry : rewards().entrySet()) {
                Double weight = entry.getValue();
                if (weight != null && weight > 0.0) {
                    eligible++;
                }
            }
            rolls = Math.min(rolls, eligible);
        }

        for (int i = 0; i < rolls; i++) {
            double totalWeight = 0.0;
            for (Map.Entry<Reward, Double> entry : rewards().entrySet()) {
                Reward reward = entry.getKey();
                Double weight = entry.getValue();
                if (weight == null || weight <= 0.0) continue;
                if (allowDuplicates() || !appliedRewards.contains(reward)) {
                    totalWeight += weight;
                }
            }

            if (totalWeight <= 0.0) {
                // Exhausted eligible rewards (or empty/invalid pool). Stop without spam.
                break;
            }

            double randomWeight = new Random().nextDouble(totalWeight);
            totalWeight = 0.0;
            Reward toGive = null;
            for (Map.Entry<Reward, Double> entry : rewards().entrySet()) {
                Reward reward = entry.getKey();
                Double weight = entry.getValue();
                if (weight == null || weight <= 0.0) continue;
                if (allowDuplicates() || !appliedRewards.contains(reward)) {
                    totalWeight += weight;
                    if (randomWeight < totalWeight) {
                        toGive = reward;
                        break;
                    }
                }
            }

            if (toGive != null) {
                toGive.applyReward(player);
                appliedRewards.add(toGive);
            } else {
                NovaRaids.INSTANCE.logError("Failed to distribute reward. No reward was found to give.");
                break;
            }
        }
    }
}
