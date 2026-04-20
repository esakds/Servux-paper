# Servux-paper

这是一个 Paper / Lophine 服务端插件，用来给 Litematica 提供轻量级的 Servux metadata 握手和 Easy Place v3 轻松放置兼容。

当前实现范围：

- 注册 `servux:litematics` 插件消息通道。
- 回应 Litematica 的 Servux metadata 请求。
- 玩家进服后主动多次发送 Servux metadata，让 Litematica AUTO 模式更稳定地识别 Servux。
- 解码 Litematica Easy Place v3 放置协议。
- 在 Bukkit 正常放置方块后修正方块状态。
- 兼容 v3 直接点击目标可替换方块位的放置方式。
- 提供 `/servuxpaper status` 和 `/servuxpaper resend` 用于确认握手与 v3 放置包状态。
- 使用并发计数器、异步维护线程和线程安全方块属性缓存，降低大量玩家同时放置时的锁竞争与重复解析开销。
- 支持 PlugMan / PlugManX 热卸载和重载时的显式清理。

当前不实现：

- 不粘贴原理图。
- 不接收 `LitematicaPaste`。
- 不接收 `Litematic-TransmitStart/Data/End` 上传。
- 不还原容器物品、方块实体 NBT 或实体。
- 不支持 MiniHUD 结构边界框、种子、天气、HUD 数据同步。

## 目标环境

- 服务端：Lophine / Paper API `1.21.11`
- Java：`21`
- 运行依赖：`ProtocolLib 5.4.0`
- 客户端：Minecraft `1.21.11` + Fabric + MaLiLib + Litematica

## 前置插件

- `ProtocolLib 5.4.0` 或兼容版本

## 构建

在本目录运行：

```powershell
.\gradlew.bat clean build
```

构建完成后，插件 jar 会生成在：

```text
build/libs/paper-servux-compat-0.2.2.jar
```

## 安装

把下面文件复制到服务端 `plugins` 文件夹：

```text
PaperServuxCompat.jar
ProtocolLib.jar
```

如果你也安装了之前的：

```text
PaperEasyPlaceCompat.jar
```

请使用 `0.1.2` 或更高版本。旧插件检测到 `PaperServuxCompat` 后会自动让路，避免 v2 和 v3 同时处理同一个放置包。

## 推荐配置

```yaml
debug: false
metadata-enabled: true
join-metadata-delay-ticks: 40
join-metadata-resend-count: 4
join-metadata-resend-interval-ticks: 40
easy-place-v3-enabled: true
packet-listener-priority: LOWEST
packet-max-age-millis: 1200
strict-placement-match: true
apply-delay-ticks: 1
require-same-material-before-apply: true
apply-block-state: true
async-maintenance-enabled: true
async-maintenance-interval-millis: 1000
```

## 客户端设置

Litematica 推荐：

```text
entityDataSync = true
easyPlaceMode = true
easyPlaceProtocolVersion = Auto 或 Version 3
```

`entityDataSync` 很重要：Litematica 客户端只有在实体数据同步开启时才会处理 Servux metadata。若它关闭，服务端即使发送了 `servux:litematics` 握手，客户端也会继续认为没有 Servux 服务端。

如果使用 `Auto`，客户端收到 `servux:litematics` metadata 后会自动切到 v3。

## 状态检查

OP 可执行：

```text
/servuxpaper status
```

重点看：

```text
metadata: sent / requests
Easy Place v3: encoded_v3 / applied
Async maintenance: pending / expired / blockDataCache / trackedTasks
```

如果 `encoded_v3` 在轻松放置时增加，说明客户端正在发送 v3 放置包。如果 metadata 一直不被客户端识别，可以执行：

```text
/servuxpaper resend
```

## PlugMan / PlugManX

插件使用 Bukkit 插件加载形式，避免 PlugManX 无法管理 Paper plugin 的限制。可以通过 PlugMan / PlugManX 执行 unload、load 或 reload。卸载时会主动清理：

- ProtocolLib 包监听器。
- `servux:litematics` 插件消息通道。
- Bukkit 事件监听器。
- 玩家调度器里的延迟任务。
- 异步维护线程和运行时缓存。

只重载本插件时可以使用 PlugMan。若同时更新 Paper、Lophine、ProtocolLib 或大量核心插件，仍建议完整重启服务器。

## Lophine / Folia

插件声明 `folia-supported: true`，可以在 Lophine / Folia 类服务端加载。

Easy Place v3 的单方块修正使用玩家调度器，适合只处理玩家放置后的单方块状态修正。

异步维护线程只清理插件自己维护的并发 Map，不读取或修改 Bukkit 世界、方块、玩家状态。方块写入仍通过安全的玩家调度器执行。

## 反作弊兼容

Litematica Easy Place v3 会在客户端发送包里编码方块状态，这类包的点击坐标看起来不像普通原版点击。插件默认使用：

```yaml
packet-listener-priority: LOWEST
```

这样会尽量在大多数反作弊插件检查包之前，把 v3 编码坐标还原成普通点击坐标。如果某个反作弊插件仍然拦截，可以尝试：

```yaml
packet-listener-priority: HIGHEST
apply-delay-ticks: 2
```

如果反作弊直接取消 `BlockPlaceEvent`，插件不会强行写方块状态；这种情况需要在反作弊里给 Litematica/Servux 放置行为加白名单，或关闭相关的非法交互/异常点击检测。

## 调试

默认关闭调试：

```yaml
debug: false
```

需要排查时优先使用：

```text
/servuxpaper status
/servuxpaper resend
```

如果仍要看每个包的细节，再临时打开 `debug: true`。测试完成后建议重新关闭 `debug`，避免大量玩家施工时刷屏。
