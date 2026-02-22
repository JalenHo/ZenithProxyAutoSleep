# AutoSleepPlugin

A [ZenithProxy](https://github.com/rfresh2/ZenithProxy) plugin that automatically finds the saved bed and sleeps at night to skip the server night cycle.

## Features

- **Auto Night Detection** - Detects nighttime using accurate packet-based timing by intercepting `ClientboundSetTimePacket`, perfectly matching the true server time and preventing mid-day sleep drift issues
- **Thunderstorm Sleep** - Also triggers sleeping during thunderstorms
- **Pathfinding to Bed** - Uses Baritone to navigate to the configured bed position
- **Smart Bed Interaction** - Multi-phase approach: path adjacent → right-click with retry logic
- **Post-Sleep Actions**:
  - `return` - Return to the position before sleeping (default)
  - `waypoint` - Navigate to a ZenithProxy waypoint after sleeping
  - `stay` - Stay at the bed location
- **Module Priority (Pause/Resume)** - Configure commands to pause other modules (e.g. villager trader) before sleeping and resume them after
- **Leave Bed Blocking** - Prevents the bot from accidentally leaving the bed before night is skipped

## Installation

1. Place the plugin jar in the `plugins` folder inside the same folder as the ZenithProxy launcher
2. Restart ZenithProxy to load the plugin

Plugins are only supported on the `java` ZenithProxy release channel (i.e. not `linux`).

## Commands

| Command | Description |
|---------|-------------|
| `autoSleep on/off` | Enable or disable the module |
| `autoSleep bed` | Save current position as bed location |
| `autoSleep bed <x> <y> <z>` | Set bed position manually |
| `autoSleep afterSleep return` | Return to previous position after sleeping |
| `autoSleep afterSleep waypoint <id>` | Go to a ZenithProxy waypoint after sleeping |
| `autoSleep afterSleep stay` | Stay at bed after sleeping |
| `autoSleep nightStart <tick>` | Set when night starts (default: 12542) |
| `autoSleep nightEnd <tick>` | Set when night ends (default: 23460) |
| `autoSleep pause add <cmd>` | Add a command to run before sleeping |
| `autoSleep pause remove <cmd>` | Remove a pause command |
| `autoSleep pause list` | List all pause commands |
| `autoSleep resume add <cmd>` | Add a command to run after sleeping |
| `autoSleep resume remove <cmd>` | Remove a resume command |
| `autoSleep resume list` | List all resume commands |
| `autoSleep status` | Show current auto-sleep state |

## Building

Execute the Gradle `build` task:

```
./gradlew build
```

The built plugin jar will be in the `build/libs` directory.

## Testing

Execute the `run` task:

```
./gradlew run
```

This will run ZenithProxy with your plugin loaded in the `run` directory.
