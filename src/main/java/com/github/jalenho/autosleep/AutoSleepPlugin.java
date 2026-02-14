package com.github.jalenho.autosleep;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import com.github.jalenho.autosleep.command.AutoSleepCommand;
import com.github.jalenho.autosleep.module.AutoSleepModule;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Automatically sleep in beds at night to skip the night cycle",
    url = "https://github.com/JalenHo/AutoSleepPlugin",
    authors = {"JalenHo"},
    mcVersions = "*"
)
public class AutoSleepPlugin implements ZenithProxyPlugin {
    public static AutoSleepConfig PLUGIN_CONFIG;
    public static ComponentLogger LOG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        LOG.info("AutoSleep Plugin loading...");
        PLUGIN_CONFIG = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, AutoSleepConfig.class);
        pluginAPI.registerModule(new AutoSleepModule());
        pluginAPI.registerCommand(new AutoSleepCommand());
        LOG.info("AutoSleep Plugin loaded!");
    }
}

