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

    // the day time tick at which the module starts trying to sleep (default: 12542 = dusk)
    public int nightStartTick = 12542;
    // the day time tick at which the module considers it day (default: 23460 = dawn)
    public int nightEndTick = 23460;
}
