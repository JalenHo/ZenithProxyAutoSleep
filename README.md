# AutoSleepPlugin

A [ZenithProxy](https://github.com/rfresh2/ZenithProxy) plugin that automatically finds the saved bed and sleeps at night to skip the server night cycle.

## Features

- **Auto Night Detection** - Detects nighttime using accurate tick-based timing (with offset calculation matching ZenithProxy's internal logic)
- **Thunderstorm Sleep** - Also triggers sleeping during thunderstorms
- **Pathfinding to Bed** - Uses Baritone to navigate to the configured bed position
- **Smart Bed Interaction** - Multi-phase approach: path adjacent → right-click with retry logic
- **Return to Previous Position** - Automatically returns to the position before sleeping after waking up
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
| `autoSleep nightStart <tick>` | Set when night starts (default: 12542) |
| `autoSleep nightEnd <tick>` | Set when night ends (default: 23460) |
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
