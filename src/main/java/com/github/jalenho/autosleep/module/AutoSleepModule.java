package com.github.jalenho.autosleep.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.cache.data.chunk.WorldTimeData;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.pathfinder.goals.GoalBlock;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
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
        PATHING_BACK
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
        }
    }

    private void handleIdle() {
        boolean night = isNightTime();
        boolean thunder = isThunderStorm();
        if (night || thunder) {
            info("Sleep trigger: night={}, thunder={}, dayTimeTick={}", night, thunder, getCurrentTimeOfDay());

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
            return;
        }

        // Check if it's no longer night (someone else slept, or time changed)
        if (!isNightTime() && !isThunderStorm()) {
            info("It is no longer night, cancelling sleep attempt.");
            blockLeaveBed = false;
            if (BARITONE.isActive()) {
                BARITONE.stop();
            }
            startReturning();
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
            return;
        }

        // Check if it's no longer night
        if (!isNightTime() && !isThunderStorm()) {
            info("It is no longer night, cancelling sleep attempt.");
            blockLeaveBed = false;
            if (BARITONE.isActive()) {
                BARITONE.stop();
            }
            startReturning();
            return;
        }

        // If baritone is done with the right-click attempt but we're not sleeping, retry
        if (!BARITONE.isActive()) {
            clickRetries++;
            if (clickRetries > MAX_CLICK_RETRIES) {
                info("Failed to sleep after {} retries, giving up.", MAX_CLICK_RETRIES);
                blockLeaveBed = false;
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
        // Check if we woke up (server set isSleeping to false = morning!)
        if (!CACHE.getPlayerCache().getThePlayer().isSleeping()) {
            info("Woke up! Night has been skipped.");
            blockLeaveBed = false;
            startReturning();
            return;
        }

        // If it's no longer night but we're still sleeping, the server should wake us soon
        // Just keep blocking leave_bed and wait
    }

    /**
     * Return to the previous position after sleeping.
     */
    private void startReturning() {
        if (prevPosSaved) {
            state = SleepState.PATHING_BACK;
            int goalX = MathHelper.floorI(prevX);
            int goalY = MathHelper.floorI(prevY);
            int goalZ = MathHelper.floorI(prevZ);
            info("Returning to previous position [{}, {}, {}]", goalX, goalY, goalZ);
            BARITONE.pathTo(new GoalBlock(goalX, goalY, goalZ)).addExecutedListener(f -> {
                info("Returned to previous position!");
                state = SleepState.IDLE;
            });
        } else {
            info("No previous position saved, staying at current location.");
            state = SleepState.IDLE;
        }
    }

    private void handlePathingBack() {
        // Check if baritone finished or isn't active
        if (!BARITONE.isActive()) {
            info("Finished returning to previous position.");
            state = SleepState.IDLE;
        }
    }

    /**
     * Get the current time of day (0-23999) with proper offset calculation,
     * matching how ZenithProxy's WorldTimeData.toPacket() works.
     * Returns -1 if unavailable or daylight cycle is disabled.
     */
    private long getCurrentTimeOfDay() {
        WorldTimeData worldTimeData = CACHE.getChunkCache().getWorldTimeData();
        if (worldTimeData == null) return -1;

        // Check if daylight cycle is ticking (1.21+ uses tickDayTime flag)
        if (!worldTimeData.isTickDayTime()) return -1;

        long dayTime = worldTimeData.getDayTime();
        // If dayTime is negative, the daylight cycle is disabled (legacy check)
        if (dayTime < 0) return -1;

        // Add offset for ticks elapsed since the last server time update
        // (same calculation as WorldTimeData.toPacket())
        long offset = (System.currentTimeMillis() - worldTimeData.getLastUpdate()) / 50;
        return (dayTime + offset) % 24000;
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
