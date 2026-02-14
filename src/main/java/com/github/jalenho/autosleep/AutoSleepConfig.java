package com.github.jalenho.autosleep;

/**
 * AutoSleep plugin configuration.
 *
 * Configurations are saved and loaded to JSON files.
 * Save and load is handled automatically.
 * All fields should be public and mutable.
 */
public class AutoSleepConfig {
    public boolean enabled = false;
    // bed coordinates
    public int bedX = 0;
    public int bedY = 64;
    public int bedZ = 0;
    // whether the bed position has been set
    public boolean bedSet = false;

    // What to do after sleeping: "return" (go back to previous pos), "waypoint" (go to waypoint), "stay" (stay at bed)
    public String postSleepAction = "return";

    // ZenithProxy waypoint ID to go to after sleeping (used when postSleepAction = "waypoint")
    // Set a waypoint first via the ZenithProxy "waypoints add <id> <x> <y> <z>" command
    public String waypointId = "";

    // the day time tick at which the module starts trying to sleep (default: 12542 = dusk)
    public int nightStartTick = 12542;
    // the day time tick at which the module considers it day (default: 23460 = dawn)
    public int nightEndTick = 23460;

    // Commands to execute before sleeping (to pause other modules)
    // e.g. ["villagerTrader off"]
    public java.util.List<String> pauseCommands = new java.util.ArrayList<>();

    // Commands to execute after sleeping finishes (to resume other modules)
    // e.g. ["villagerTrader on"]
    public java.util.List<String> resumeCommands = new java.util.ArrayList<>();
}
