package me.unariginal.novaraids.commands.suggestions;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.concurrent.CompletableFuture;
import me.unariginal.novaraids.NovaRaids;
import me.unariginal.novaraids.data.Location;
import net.minecraft.server.command.ServerCommandSource;

public class LocationSuggestions
implements SuggestionProvider<ServerCommandSource> {
    public CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) throws CommandSyntaxException {
        if (NovaRaids.LOADED) {
            for (Location boss : NovaRaids.INSTANCE.locationsConfig().locations) {
                builder.suggest(boss.id());
            }
        }
        return builder.buildFuture();
    }
}
