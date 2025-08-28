package me.unariginal.novaraids.utils;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.permission.Permission;
import com.cobblemon.mod.common.api.permission.PermissionLevel;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class NovaRaidsPermissions {
    public static final List<Permission> permissions = new ArrayList<>();
    public static final PermissionNode RELOAD = PermissionNode.of("reload", 4);
    public static final PermissionNode START = PermissionNode.of("start", 4);
    public static final PermissionNode STOP = PermissionNode.of("stop", 4);
    public static final PermissionNode GIVE = PermissionNode.of("give", 4);
    public static final PermissionNode LIST = PermissionNode.of("list", 4);
    public static final PermissionNode JOIN = PermissionNode.of("join", 4);
    public static final PermissionNode OVERRIDE = PermissionNode.of("override", 4);
    public static final PermissionNode LEAVE = PermissionNode.of("leave", 4);
    public static final PermissionNode QUEUE = PermissionNode.of("queue", 4);
    public static final PermissionNode CHECKEDBANNED = PermissionNode.of("queue", 4);
    public static final PermissionNode HISTORY = PermissionNode.of("history", 4);
    public static final PermissionNode SKIPPHASE = PermissionNode.of("skipphase", 4);
    public static final PermissionNode TESTREWARDS = PermissionNode.of("testrewards", 4);
    public static final PermissionNode WORLD = PermissionNode.of("world", 4);
    public static final PermissionNode DAMAGE = PermissionNode.of("damage", 4);
    public static final PermissionNode SCHEDULE = PermissionNode.of("schedule", 4);
    public static final PermissionNode CANCELQUEUE = PermissionNode.of("cancelqueue", 4);
    public static final PermissionNode SHOWPOKEMON = PermissionNode.of("showpokemon", 4);
    public static final PermissionNode SHOWPLAYERS = PermissionNode.of("showplayers", 4);

    public record PermissionNode(Identifier identifier, String literal, PermissionLevel level) implements Permission, Predicate<ServerCommandSource> {
        public static PermissionNode of(String perm, int level) {
            var node = new PermissionNode(Identifier.of("novaraids", perm), "novaraids." + perm, PermissionLevel.values()[MathHelper.clamp(level, 0, 4)]);
            NovaRaidsPermissions.permissions.add(node);
            return node;
        }

        @Override
        public @NotNull Identifier getIdentifier() {
            return identifier;
        }

        @Override
        public @NotNull String getLiteral() {
            return literal;
        }

        @Override
        public @NotNull PermissionLevel getLevel() {
            return level;
        }

        @Override
        public boolean test(ServerCommandSource serverCommandSource) {
            return Cobblemon.INSTANCE.getPermissionValidator().hasPermission(serverCommandSource, this);
        }

        public boolean test(ServerPlayerEntity player) {
            return Cobblemon.INSTANCE.getPermissionValidator().hasPermission(player, this);
        }
    }
}
