package savage.btaf.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

@Config(name = "savs_btf")
public class SavsConfig implements ConfigData {
    @ConfigEntry.Gui.Tooltip
    public float oak3Rarity = 0.01f;

    @ConfigEntry.Gui.Tooltip
    public String oak3Foliage = "minecraft:azalea_leaves";

    @ConfigEntry.Gui.Tooltip
    public String oak3Log = "minecraft:dark_oak_log";
}
