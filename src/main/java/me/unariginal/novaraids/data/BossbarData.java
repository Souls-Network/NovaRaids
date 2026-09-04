package me.unariginal.novaraids.data;

import me.unariginal.novaraids.managers.Raid;
import me.unariginal.novaraids.utils.TextUtils;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.ComponentLike;

public record BossbarData(String name,
                          BossBar.Color barColor,
                          BossBar.Overlay barStyle,
                          String barText,
                          boolean useActionbar,
                          String actionbarText) {
    public BossBar createBossBar(Raid raid) {
        return BossBar.bossBar(TextUtils.deserializeAdventure(TextUtils.parse(this.barText, raid)), (float)1.0f, (BossBar.Color)this.barColor, (BossBar.Overlay)this.barStyle);
    }
}
