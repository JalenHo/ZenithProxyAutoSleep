package com.github.jalenho.autosleep.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import com.zenith.util.math.MathHelper;
import com.github.jalenho.autosleep.module.AutoSleepModule;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static com.github.jalenho.autosleep.AutoSleepPlugin.PLUGIN_CONFIG;

public class AutoSleepCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("autoSleep")
            .category(CommandCategory.MODULE)
            .description("""
                Automatically sleeps in a bed at night to skip the night cycle.
                Set the bed position first, then enable the module.
                """)
            .usageLines(
                "on/off",
                "bed                   - save current position as bed",
                "bed <x> <y> <z>       - set bed position manually",
                "nightStart <tick>     - set when night starts (default: 12542)",
                "nightEnd <tick>       - set when night ends (default: 23460)",
                "status                - show current auto-sleep state"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("autoSleep")
            // on/off toggle
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.enabled = getToggle(c, "toggle");
                MODULE.get(AutoSleepModule.class).syncEnabledFromConfig();
                c.getSource().getEmbed()
                    .title("Auto Sleep " + toggleStrCaps(PLUGIN_CONFIG.enabled));
            }))
            // bed - save current position
            .then(literal("bed")
                .executes(c -> {
                    int x = MathHelper.floorI(CACHE.getPlayerCache().getX());
                    int y = MathHelper.floorI(CACHE.getPlayerCache().getY());
                    int z = MathHelper.floorI(CACHE.getPlayerCache().getZ());
                    PLUGIN_CONFIG.bedX = x;
                    PLUGIN_CONFIG.bedY = y;
                    PLUGIN_CONFIG.bedZ = z;
                    PLUGIN_CONFIG.bedSet = true;
                    c.getSource().getEmbed()
                        .title("Bed Position Set")
                        .addField("Position", String.format("[%d, %d, %d]", x, y, z));
                })
                // bed <x> <y> <z> - set manually
                .then(argument("x", integer()).then(argument("y", integer()).then(argument("z", integer()).executes(c -> {
                    int x = getInteger(c, "x");
                    int y = getInteger(c, "y");
                    int z = getInteger(c, "z");
                    PLUGIN_CONFIG.bedX = x;
                    PLUGIN_CONFIG.bedY = y;
                    PLUGIN_CONFIG.bedZ = z;
                    PLUGIN_CONFIG.bedSet = true;
                    c.getSource().getEmbed()
                        .title("Bed Position Set")
                        .addField("Position", String.format("[%d, %d, %d]", x, y, z));
                })))))
            // nightStart <tick>
            .then(literal("nightStart")
                .then(argument("tick", integer(0, 24000)).executes(c -> {
                    PLUGIN_CONFIG.nightStartTick = getInteger(c, "tick");
                    c.getSource().getEmbed()
                        .title("Night Start Tick Set")
                        .addField("Tick", String.valueOf(PLUGIN_CONFIG.nightStartTick));
                })))
            // nightEnd <tick>
            .then(literal("nightEnd")
                .then(argument("tick", integer(0, 24000)).executes(c -> {
                    PLUGIN_CONFIG.nightEndTick = getInteger(c, "tick");
                    c.getSource().getEmbed()
                        .title("Night End Tick Set")
                        .addField("Tick", String.valueOf(PLUGIN_CONFIG.nightEndTick));
                })))
            // status
            .then(literal("status").executes(c -> {
                AutoSleepModule module = MODULE.get(AutoSleepModule.class);
                c.getSource().getEmbed()
                    .title("Auto Sleep Status")
                    .addField("State", module.getState().name())
                    .primaryColor();
            }));
    }

    @Override
    public void defaultEmbed(Embed embed) {
        AutoSleepModule module = MODULE.get(AutoSleepModule.class);
        String bedPos = PLUGIN_CONFIG.bedSet
            ? String.format("[%d, %d, %d]", PLUGIN_CONFIG.bedX, PLUGIN_CONFIG.bedY, PLUGIN_CONFIG.bedZ)
            : "Not set";
        embed
            .primaryColor()
            .addField("Enabled", toggleStr(PLUGIN_CONFIG.enabled))
            .addField("Bed Position", bedPos)
            .addField("Night Start Tick", String.valueOf(PLUGIN_CONFIG.nightStartTick))
            .addField("Night End Tick", String.valueOf(PLUGIN_CONFIG.nightEndTick))
            .addField("Current State", module != null ? module.getState().name() : "N/A");
    }
}
