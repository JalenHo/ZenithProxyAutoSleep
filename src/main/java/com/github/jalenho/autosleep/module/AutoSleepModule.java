package com.github.jalenho.autosleep.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.cache.data.chunk.WorldTimeData;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.pathfinder.goals.GoalBlock;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
import com.zenith.feature.player.World;
import com.zenith.feature.waypoints.Waypoint;
import com.zenith.mc.block.BlockPos;
import com.zenith.module.api.Module;
import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.PacketHandler;
import com.zenith.network.codec.PacketHandlerCodec;
import com.zenith.network.codec.PacketHandlerStateCodec;
import com.zenith.util.math.MathHelper;
import com.zenith.util.timer.Timer;
import com.zenith.util.timer.Timers;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerState;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerCommandPacket;

import java.util.List;
import java.util.Optional;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;
import static com.github.jalenho.autosleep.AutoSleepPlugin.PLUGIN_CONFIG;

public class AutoSleepModule extends Module {
    private final Timer checkTimer = Timers.tickTimer();

    // State machine
    public enum SleepState {
        IDLE,
        PATHING_TO_BED,
        CLICKING_BED,
        SLEEPING,
        PATHING_BACK,
        PATHING_TO_WAYPOINT
    }

    private volatile SleepState state = SleepState.IDLE;

    // Saved previous position
    private double prevX, prevY, prevZ;
    private boolean prevPosSaved = false;

    // Track whether we should block LEAVE_BED packets
    private volatile boolean blockLeaveBed = false;

    // Retry counter for clicking the bed
    private int clickRetries = 0;
    private static final int MAX_CLICK_RETRIES = 10;

    // Whether we executed pause commands (so we know to resume later)
    private boolean pausedOtherModules = false;

    // Track when sleeping started (for safety timeout)
    private long sleepStartTime = 0;
    private static final long MAX_SLEEP_DURATION_MS = 15 * 60 * 1000; // 15 minutes

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::handleBotTick),
            of(ClientBotTick.Starting.class, this::handleBotTickStarting),
            of(ClientBotTick.Stopped.class, this::handleBotTickStopped)
        );
    }

    @Override
    public PacketHandlerCodec registerClientPacketHandlerCodec() {
        return PacketHandlerCodec.clientBuilder()
            .setId("auto-sleep")
            .setPriority(1000)
            .state(ProtocolState.GAME, PacketHandlerStateCodec.<ClientSession>clientBuilder()
                .outbound(ServerboundPlayerCommandPacket.class, new LeaveBedBlocker())
                .build())
            .build();
    }

    @Override
    public void onDisable() {
        resetState();
        if (BARITONE.isActive()) {
            BARITONE.stop();
        }
    }

    private void handleBotTickStarting(ClientBotTick.Starting event) {
        resetState();
    }

    private void handleBotTickStopped(ClientBotTick.Stopped event) {
        resetState();
    }

    private void resetState() {
        state = SleepState.IDLE;
        blockLeaveBed = false;
        prevPosSaved = false;
        clickRetries = 0;
        pausedOtherModules = false;
        sleepStartTime = 0;
    }

    private void handleBotTick(ClientBotTick event) {
        // Only check every 20 ticks (~1 second)
        if (!checkTimer.tick(20)) return;

        // Must have bed set
        if (!PLUGIN_CONFIG.bedSet) return;

        // Must be alive
        if (!CACHE.getPlayerCache().isAlive()) return;

        switch (state) {
            case IDLE -> handleIdle();
            case PATHING_TO_BED -> handlePathingToBed();
            case CLICKING_BED -> handleClickingBed();
            case SLEEPING -> handleSleeping();
            case PATHING_BACK -> handlePathingBack();
            case PATHING_TO_WAYPOINT -> handlePathingToWaypoint();
        }
    }

    private void handleIdle() {
        boolean night = isNightTime();
        boolean thunder = isThunderStorm();
        if (night || thunder) {
            info("Sleep trigger: night={}, thunder={}, dayTimeTick={}", night, thunder, getCurrentTimeOfDay());

            // Execute pause commands to stop other modules (e.g. villagerTrader off)
            executePauseCommands();

            // Force stop any active Baritone pathing from other modules
            if (BARITONE.isActive()) {
                info("Stopping active Baritone process to take control...");
                BARITONE.stop();
            }

            // Save current position before heading to bed
            prevX = CACHE.getPlayerCache().getX();
            prevY = CACHE.getPlayerCache().getY();
            prevZ = CACHE.getPlayerCache().getZ();
            prevPosSaved = true;

            // Phase 1: Navigate to directly adjacent to the bed
            state = SleepState.PATHING_TO_BED;
            int bedX = PLUGIN_CONFIG.bedX;
            int bedY = PLUGIN_CONFIG.bedY;
            int bedZ = PLUGIN_CONFIG.bedZ;

            info("Pathing to bed at [{}, {}, {}]", bedX, bedY, bedZ);
            blockLeaveBed = true;
            clickRetries = 0;
            BARITONE.pathTo(new GoalGetToBlock(new BlockPos(bedX, bedY, bedZ)));
        }
    }

    private void handlePathingToBed() {
        // Check if the player has started sleeping (maybe we're already in bed from a previous attempt)
        if (CACHE.getPlayerCache().getThePlayer().isSleeping()) {
            info("Player is now sleeping in bed!");
            state = SleepState.SLEEPING;
            sleepStartTime = System.currentTimeMillis();
            return;
        }

        // Check if it's no longer night (someone else slept, or time changed)
        if (!isNightTime() && !isThunderStorm()) {
            info("It is no longer night, cancelling sleep attempt.");
            blockLeaveBed = false;
            if (BARITONE.isActive()) {
                BARITONE.stop();
            }
            startPostSleepAction();
            return;
        }

        // Check if baritone finished pathing (we should now be adjacent to the bed)
        if (!BARITONE.isActive()) {
            int bedX = PLUGIN_CONFIG.bedX;
            int bedY = PLUGIN_CONFIG.bedY;
            int bedZ = PLUGIN_CONFIG.bedZ;

            double distSq = Math.pow(CACHE.getPlayerCache().getX() - (bedX + 0.5), 2)
                + Math.pow(CACHE.getPlayerCache().getY() - bedY, 2)
                + Math.pow(CACHE.getPlayerCache().getZ() - (bedZ + 0.5), 2);

            if (distSq <= 9) { // Within 3 blocks - close enough, transition to clicking
                info("Arrived next to bed, attempting to right-click...");
                state = SleepState.CLICKING_BED;
                clickRetries = 0;
                BARITONE.rightClickBlock(bedX, bedY, bedZ);
            } else {
                // Still far, retry pathing
                debug("Pathing incomplete (dist={}, retrying navigation...", String.format("%.1f", Math.sqrt(distSq)));
                BARITONE.pathTo(new GoalGetToBlock(new BlockPos(bedX, bedY, bedZ)));
            }
        }
    }

    private void handleClickingBed() {
        // Check if sleeping succeeded
        if (CACHE.getPlayerCache().getThePlayer().isSleeping()) {
            info("Player is now sleeping in bed!");
            state = SleepState.SLEEPING;
            sleepStartTime = System.currentTimeMillis();
            return;
        }

        // Check if it's no longer night
        if (!isNightTime() && !isThunderStorm()) {
            info("It is no longer night, cancelling sleep attempt.");
            blockLeaveBed = false;
            if (BARITONE.isActive()) {
                BARITONE.stop();
            }
            startPostSleepAction();
            return;
        }

        // If baritone is done with the right-click attempt but we're not sleeping, retry
        if (!BARITONE.isActive()) {
            clickRetries++;
            if (clickRetries > MAX_CLICK_RETRIES) {
                info("Failed to sleep after {} retries, giving up.", MAX_CLICK_RETRIES);
                blockLeaveBed = false;
                executeResumeCommands();
                state = SleepState.IDLE;
                return;
            }

            int bedX = PLUGIN_CONFIG.bedX;
            int bedY = PLUGIN_CONFIG.bedY;
            int bedZ = PLUGIN_CONFIG.bedZ;

            double distSq = Math.pow(CACHE.getPlayerCache().getX() - (bedX + 0.5), 2)
                + Math.pow(CACHE.getPlayerCache().getY() - bedY, 2)
                + Math.pow(CACHE.getPlayerCache().getZ() - (bedZ + 0.5), 2);

            if (distSq > 9) {
                // Drifted too far, go back to pathing phase
                debug("Drifted too far from bed, re-pathing...");
                state = SleepState.PATHING_TO_BED;
                BARITONE.pathTo(new GoalGetToBlock(new BlockPos(bedX, bedY, bedZ)));
            } else {
                debug("Retrying right-click on bed (attempt {}/{})...", clickRetries, MAX_CLICK_RETRIES);
                BARITONE.rightClickBlock(bedX, bedY, bedZ);
            }
        }
    }

    private void handleSleeping() {
        // Check if we woke up (server set isSleeping to false)
        if (!CACHE.getPlayerCache().getThePlayer().isSleeping()) {
            info("Woke up from bed. Starting post-sleep action.");
            blockLeaveBed = false;
            startPostSleepAction();
            return;
        }

        // FIX: If night ended naturally (not enough players to skip it), we must
        // actively allow the bot to leave bed. In Minecraft 1.21.x, the server does
        // NOT automatically kick you out of bed when morning arrives — it only wakes
        // you when the night is actually skipped by enough players sleeping.
        // Without this check, the bot would stay in bed forever.
        long timeOfDay = getCurrentTimeOfDay();
        if (timeOfDay >= 0) {
            // We have fresh, valid time data — check if it's still night
            int nightStart = PLUGIN_CONFIG.nightStartTick;
            int nightEnd = PLUGIN_CONFIG.nightEndTick;
            boolean stillNight = timeOfDay >= nightStart && timeOfDay <= nightEnd;
            if (!stillNight && !isThunderStorm()) {
                info("Night ended naturally without being skipped (tick={}). Allowing bot to leave bed.", timeOfDay);
                blockLeaveBed = false;
                // The Bot's tick will automatically send LEAVE_BED on the next game tick.
                // On our next check, isSleeping() will be false and we'll transition out.
                return;
            }
        }

        // Safety timeout: if sleeping for over 15 minutes, something is wrong — force leave
        if (sleepStartTime > 0 && System.currentTimeMillis() - sleepStartTime > MAX_SLEEP_DURATION_MS) {
            info("Sleep safety timeout reached ({}min). Force-leaving bed.", MAX_SLEEP_DURATION_MS / 60000);
            blockLeaveBed = false;
            return;
        }
    }

    /**
     * Decide what to do after sleeping (or after early cancellation).
     * Actions: "waypoint" (go to zenith waypoint), "return" (go to prev pos), "stay" (do nothing)
     */
    private void startPostSleepAction() {
        String action = PLUGIN_CONFIG.postSleepAction;
        if (action == null) action = "return";

        switch (action.toLowerCase()) {
            case "waypoint" -> {
                Optional<Waypoint> wpOpt = findWaypoint();
                if (wpOpt.isPresent()) {
                    Waypoint wp = wpOpt.get();
                    // Check dimension match
                    if (wp.dimensionData() != World.getCurrentDimension()) {
                        info("Waypoint '{}' is in dimension {} but we are in {}. Falling back to return.",
                            wp.id(), wp.dimension(), World.getCurrentDimension().name());
                        fallbackReturn();
                    } else {
                        startPathingToWaypoint(wp);
                    }
                } else {
                    info("Waypoint '{}' not found! Falling back to return.", PLUGIN_CONFIG.waypointId);
                    fallbackReturn();
                }
            }
            case "return" -> {
                if (prevPosSaved) {
                    startPathingBack();
                } else {
                    info("No previous position saved, staying at current location.");
                    executeResumeCommands();
                    state = SleepState.IDLE;
                }
            }
            default -> { // "stay" or anything else
                info("Staying at current location.");
                executeResumeCommands();
                state = SleepState.IDLE;
            }
        }
    }

    /**
     * Fallback: try to return to previous position, otherwise stay.
     */
    private void fallbackReturn() {
        if (prevPosSaved) {
            startPathingBack();
        } else {
            executeResumeCommands();
            state = SleepState.IDLE;
        }
    }

    /**
     * Look up the configured waypoint from ZenithProxy's waypoint list.
     */
    private Optional<Waypoint> findWaypoint() {
        String wpId = PLUGIN_CONFIG.waypointId;
        if (wpId == null || wpId.isBlank()) return Optional.empty();
        return CONFIG.client.extra.waypoints.waypoints.stream()
            .filter(w -> w.id().equalsIgnoreCase(wpId))
            .findFirst();
    }

    private void startPathingBack() {
        state = SleepState.PATHING_BACK;
        int goalX = MathHelper.floorI(prevX);
        int goalY = MathHelper.floorI(prevY);
        int goalZ = MathHelper.floorI(prevZ);
        info("Returning to previous position [{}, {}, {}]", goalX, goalY, goalZ);
        BARITONE.pathTo(new GoalBlock(goalX, goalY, goalZ)).addExecutedListener(f -> {
            info("Returned to previous position!");
            state = SleepState.IDLE;
        });
    }

    private void handlePathingBack() {
        // Check if baritone finished or isn't active
        if (!BARITONE.isActive()) {
            info("Finished returning to previous position.");
            // Resume other modules now that we're back
            executeResumeCommands();
            state = SleepState.IDLE;
        }
    }

    private void startPathingToWaypoint(Waypoint wp) {
        state = SleepState.PATHING_TO_WAYPOINT;
        info("Pathing to waypoint '{}' at [{}, {}, {}]", wp.id(), wp.x(), wp.y(), wp.z());
        BARITONE.pathTo(new GoalBlock(wp.x(), wp.y(), wp.z())).addExecutedListener(f -> {
            info("Arrived at waypoint '{}'!", wp.id());
            state = SleepState.IDLE;
        });
    }

    private void handlePathingToWaypoint() {
        // Check if baritone finished or isn't active
        if (!BARITONE.isActive()) {
            info("Finished pathing to waypoint.");
            // Resume other modules now that we've arrived
            executeResumeCommands();
            state = SleepState.IDLE;
        }
    }

    /**
     * Get the current time of day (0-23999) using server world time directly.
     * Returns -1 if unavailable, stale, or daylight cycle is disabled.
     *
     * Uses the server's dayTime value without manual offset calculation to avoid
     * drift issues. The server sends time updates every ~1 second, so the value
     * is accurate to within ~20 ticks — negligible for night detection which
     * spans ~11000 ticks.
     */
    private long getCurrentTimeOfDay() {
        WorldTimeData worldTimeData = CACHE.getChunkCache().getWorldTimeData();
        if (worldTimeData == null) return -1;

        // Check if daylight cycle is ticking (1.21+ uses tickDayTime flag)
        if (!worldTimeData.isTickDayTime()) return -1;

        long dayTime = worldTimeData.getDayTime();
        // If dayTime is negative, the daylight cycle is disabled (legacy check)
        if (dayTime < 0) return -1;

        // Verify the time data is reasonably fresh (server sends updates every ~1s)
        // If stale for over 60 seconds, something is wrong — don't trust the value
        long staleness = System.currentTimeMillis() - worldTimeData.getLastUpdate();
        if (staleness > 60000) {
            debug("World time data is stale ({}s old), cannot determine time of day", staleness / 1000);
            return -1;
        }

        return dayTime % 24000;
    }

    private boolean isNightTime() {
        long timeOfDay = getCurrentTimeOfDay();
        if (timeOfDay < 0) return false; // daylight cycle disabled or data unavailable

        int nightStart = PLUGIN_CONFIG.nightStartTick;
        int nightEnd = PLUGIN_CONFIG.nightEndTick;

        // Night in Minecraft: beds can be used from tick 12542 to 23460
        // nightStart=12542, nightEnd=23460
        return timeOfDay >= nightStart && timeOfDay <= nightEnd;
    }

    /**
     * Checks if there is a thunderstorm (beds can be used during thunderstorms at any time).
     * Requires both raining and high thunder strength to avoid false positives from light rain.
     */
    private boolean isThunderStorm() {
        var chunkCache = CACHE.getChunkCache();
        if (!chunkCache.isRaining()) return false;
        float thunderStrength = chunkCache.getThunderStrength();
        // Thunder strength must be very high (close to 1.0) for a proper thunderstorm
        if (thunderStrength <= 0.9f) return false;
        debug("Thunderstorm detected: raining=true, thunderStrength={}", String.format("%.2f", thunderStrength));
        return true;
    }

    public SleepState getState() {
        return state;
    }

    /**
     * Execute configured pause commands (e.g. "villagerTrader off") to stop other modules
     * before AutoSleep takes control of Baritone.
     */
    private void executePauseCommands() {
        if (pausedOtherModules) return; // already paused
        var commands = PLUGIN_CONFIG.pauseCommands;
        if (commands != null && !commands.isEmpty()) {
            for (String cmd : commands) {
                if (cmd != null && !cmd.isBlank()) {
                    info("Executing pause command: {}", cmd);
                    executeCommand(cmd, true);
                }
            }
        }
        pausedOtherModules = true;
    }

    /**
     * Execute configured resume commands (e.g. "villagerTrader on") to restart other modules
     * after AutoSleep finishes its sleep cycle.
     */
    private void executeResumeCommands() {
        if (!pausedOtherModules) return; // nothing to resume
        var commands = PLUGIN_CONFIG.resumeCommands;
        if (commands != null && !commands.isEmpty()) {
            for (String cmd : commands) {
                if (cmd != null && !cmd.isBlank()) {
                    info("Executing resume command: {}", cmd);
                    executeCommand(cmd, true);
                }
            }
        }
        pausedOtherModules = false;
    }

    /**
     * Packet handler that blocks LEAVE_BED commands while we want the bot to stay in bed.
     */
    public class LeaveBedBlocker implements PacketHandler<ServerboundPlayerCommandPacket, ClientSession> {
        @Override
        public ServerboundPlayerCommandPacket apply(ServerboundPlayerCommandPacket packet, ClientSession session) {
            if (blockLeaveBed && packet.getState() == PlayerState.LEAVE_BED) {
                debug("Blocked LEAVE_BED packet (auto-sleeping)");
                return null; // drop the packet
            }
            return packet;
        }
    }
}
