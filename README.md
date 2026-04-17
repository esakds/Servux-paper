# Servux-paper

Servux-paper is a lightweight Paper / Lophine plugin that adds partial Servux compatibility for Litematica.

## Features

- Registers the `servux:litematics` plugin message channel.
- Sends Servux litematics metadata so Litematica can detect Servux support.
- Supports Litematica Easy Place protocol v3 on Paper servers.
- Decodes v3 placement data from normal `USE_ITEM_ON` packets.
- Corrects placed block states after Bukkit placement, including direction and supported block-data properties.
- Uses conservative placement matching for servers with protection, logging, anti-cheat, rollback, or script plugins.

## Not Included

- Full Servux implementation.
- Server-side schematic paste through Servux.
- Full entity or block entity NBT sync.
- MiniHUD structure, seed, weather, or HUD data sync.

## Requirements

- Paper / Lophine `1.21.11`
- Java `21`
- Fabric client with MaLiLib and Litematica

## Dependencies

Server-side required plugin:

- ProtocolLib `5.4.0`

Client-side required mods:

- MaLiLib
- Litematica

## Install

Put these jars in the server `plugins` folder:

```text
Servux-paper.jar
ProtocolLib.jar
```

In Litematica, use:

```text
easyPlaceMode = true
easyPlaceProtocolVersion = Auto or Version 3
```

## Build

```powershell
.\gradlew.bat clean build
```

The jar is generated in:

```text
build/libs/Servux-paper-0.1.0.jar
```

## License

MIT
