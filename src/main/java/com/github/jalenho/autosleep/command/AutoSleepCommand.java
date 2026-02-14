package com.github.jalenho.autosleep.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import com.zenith.feature.waypoints.Waypoint;
import com.zenith.util.math.MathHelper;
import com.github.jalenho.autosleep.module.AutoSleepModule;

import java.util.stream.Collectors;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.CustomStringArgumentType.wordWithChars;
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
                "afterSleep return     - return to previous position after sleeping (default)",
                "afterSleep waypoint <id> - go to a ZenithProxy waypoint after sleeping",
                "afterSleep stay       - stay at bed after sleeping",
                "nightStart <tick>     - set when night starts (default: 12542)",
                "nightEnd <tick>       - set when night ends (default: 23460)",
                "pause add <cmd>       - add a command to run before sleeping",
                "pause remove <cmd>    - remove a pause command",
                "pause list            - list all pause commands",
                "resume add <cmd>      - add a command to run after sleeping",
                "resume remove <cmd>   - remove a resume command",
                "resume list           - list all resume commands",
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
            // afterSleep - set post-sleep action
            .then(literal("afterSleep")
                .then(literal("return").executes(c -> {
                    PLUGIN_CONFIG.postSleepAction = "return";
                    c.getSource().getEmbed()
                        .title("After Sleep Action")
                        .addField("Action", "Return to previous position");
                }))
                .then(literal("waypoint")
                    .then(argument("id", wordWithChars()).executes(c -> {
                        String id = com.zenith.command.brigadier.CustomStringArgumentType.getString(c, "id");
                        // Verify the waypoint exists
                        var wpOpt = CONFIG.client.extra.waypoints.waypoints.stream()
                            .filter(w -> w.id().equalsIgnoreCase(id))
                            .findFirst();
                        if (wpOpt.isEmpty()) {
                            c.getSource().getEmbed()
                                .title("Waypoint Not Found")
                                .addField("ID", id)
                                .description("Use `waypoints list` to see available waypoints, or `waypoints add <id> <x> <y> <z>` to create one.")
                                .errorColor();
                            return;
                        }
                        Waypoint wp = wpOpt.get();
                        PLUGIN_CONFIG.postSleepAction = "waypoint";
                        PLUGIN_CONFIG.waypointId = wp.id();
                        c.getSource().getEmbed()
                            .title("After Sleep Action")
                            .addField("Action", "Go to waypoint")
                            .addField("Waypoint", String.format("%s [%d, %d, %d] (%s)", wp.id(), wp.x(), wp.y(), wp.z(), wp.dimension()));
                    })))
                .then(literal("stay").executes(c -> {
                    PLUGIN_CONFIG.postSleepAction = "stay";
                    c.getSource().getEmbed()
                        .title("After Sleep Action")
                        .addField("Action", "Stay at bed");
                })))
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
            // pause commands
            .then(literal("pause")
                .then(literal("add")
                    .then(argument("command", greedyString()).executes(c -> {
                        String cmd = getString(c, "command");
                        PLUGIN_CONFIG.pauseCommands.add(cmd);
                        c.getSource().getEmbed()
                            .title("Pause Command Added")
                            .addField("Command", "`" + cmd + "`")
                            .addField("All Pause Commands", formatCommandList(PLUGIN_CONFIG.pauseCommands));
                    })))
                .then(literal("remove")
                    .then(argument("command", greedyString()).executes(c -> {
                        String cmd = getString(c, "command");
                        boolean removed = PLUGIN_CONFIG.pauseCommands.remove(cmd);
                        c.getSource().getEmbed()
                            .title(removed ? "Pause Command Removed" : "Pause Command Not Found")
                            .addField("Command", "`" + cmd + "`")
                            .addField("All Pause Commands", formatCommandList(PLUGIN_CONFIG.pauseCommands));
                    })))
                .then(literal("list").executes(c -> {
                    c.getSource().getEmbed()
                        .title("Pause Commands")
                        .addField("Commands", formatCommandList(PLUGIN_CONFIG.pauseCommands))
                        .primaryColor();
                })))
            // resume commands
            .then(literal("resume")
                .then(literal("add")
                    .then(argument("command", greedyString()).executes(c -> {
                        String cmd = getString(c, "command");
                        PLUGIN_CONFIG.resumeCommands.add(cmd);
                        c.getSource().getEmbed()
                            .title("Resume Command Added")
                            .addField("Command", "`" + cmd + "`")
                            .addField("All Resume Commands", formatCommandList(PLUGIN_CONFIG.resumeCommands));
                    })))
                .then(literal("remove")
                    .then(argument("command", greedyString()).executes(c -> {
                        String cmd = getString(c, "command");
                        boolean removed = PLUGIN_CONFIG.resumeCommands.remove(cmd);
                        c.getSource().getEmbed()
                            .title(removed ? "Resume Command Removed" : "Resume Command Not Found")
                            .addField("Command", "`" + cmd + "`")
                            .addField("All Resume Commands", formatCommandList(PLUGIN_CONFIG.resumeCommands));
                    })))
                .then(literal("list").executes(c -> {
                    c.getSource().getEmbed()
                        .title("Resume Commands")
                        .addField("Commands", formatCommandList(PLUGIN_CONFIG.resumeCommands))
                        .primaryColor();
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
        String afterSleep = formatAfterSleep();
        embed
            .primaryColor()
            .addField("Enabled", toggleStr(PLUGIN_CONFIG.enabled))
            .addField("Bed Position", bedPos)
            .addField("After Sleep", afterSleep)
            .addField("Night Start Tick", String.valueOf(PLUGIN_CONFIG.nightStartTick))
            .addField("Night End Tick", String.valueOf(PLUGIN_CONFIG.nightEndTick))
            .addField("Pause Commands", formatCommandList(PLUGIN_CONFIG.pauseCommands))
            .addField("Resume Commands", formatCommandList(PLUGIN_CONFIG.resumeCommands))
            .addField("Current State", module != null ? module.getState().name() : "N/A");
    }

    private String formatAfterSleep() {
        String action = PLUGIN_CONFIG.postSleepAction;
        if (action == null) action = "return";
        return switch (action.toLowerCase()) {
            case "waypoint" -> {
                String wpId = PLUGIN_CONFIG.waypointId;
                if (wpId == null || wpId.isBlank()) {
                    yield "Go to waypoint (not configured)";
                }
                // Try to resolve the waypoint for display
                var wpOpt = CONFIG.client.extra.waypoints.waypoints.stream()
                    .filter(w -> w.id().equalsIgnoreCase(wpId))
                    .findFirst();
                if (wpOpt.isPresent()) {
                    Waypoint wp = wpOpt.get();
                    yield String.format("Go to waypoint `%s` [%d, %d, %d]", wp.id(), wp.x(), wp.y(), wp.z());
                } else {
                    yield "Go to waypoint `" + wpId + "` (not found!)";
                }
            }
            case "stay" -> "Stay at bed";
            default -> "Return to previous position";
        };
    }

    private static String formatCommandList(java.util.List<String> commands) {
        if (commands == null || commands.isEmpty()) return "None";
        return commands.stream()
            .map(cmd -> "`" + cmd + "`")
            .collect(Collectors.joining(", "));
    }
}
