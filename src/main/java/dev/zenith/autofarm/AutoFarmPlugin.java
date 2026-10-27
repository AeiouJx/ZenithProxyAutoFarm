package dev.zenith.autofarm;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import dev.zenith.autofarm.command.AutoFarmCommand;
import dev.zenith.autofarm.module.AutoFarmModule;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Automatically harvests, replants, and tends nearby crops",
    url = "https://github.com/AeiouJx/ZenithProxyAutoFarm",
    authors = {"AeiouJx"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class AutoFarmPlugin implements ZenithProxyPlugin {
    public static AutoFarmConfig PLUGIN_CONFIG;
    public static ComponentLogger LOG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        PLUGIN_CONFIG = pluginAPI.registerConfig("autofarm", AutoFarmConfig.class);
        pluginAPI.registerModule(new AutoFarmModule());
        pluginAPI.registerCommand(new AutoFarmCommand());
        LOG.info("AutoFarm Plugin loaded");
    }
}
