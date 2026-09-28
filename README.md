# CEArcheology

基于 [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) 的考古系统插件。

用刷子清理自定义 suspicious 方块，按进度切换外观，进度满后结算掉落。方块状态、方块实体、资源包与网络层均由 CraftEngine 提供。

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 服务端 | Paper 或兼容分支，`api-version: 1.21` |
| CraftEngine | 26.8+ |
| 运行时 Java | 21+ |
| 构建 JDK | 21+ |

`pom.xml` 的 `craft-engine.version` 为 26.8.2，取 26.8+ 区间的最低版本，产物可用于 26.8.x ~ 26.9.x。当前源码在 26.8.2 与 26.9 下均可编译，未使用 `@Deprecated` 成员。

26.9.2 仅存在于上游 `main` 分支，未发布到 Maven 仓库。

## 构建

要求 JDK 21+。pom 使用 `<release>21</release>`，并由 `maven-enforcer-plugin` 在 `validate` 阶段校验，版本不足时直接失败。

```
mvn clean package
```

产物为 `target/CEArcheology-1.0.0-SNAPSHOT.jar`。依赖从 pom 中声明的仓库拉取。

## 安装

1. jar 放入 `plugins/`，并确认 CraftEngine 已安装。
2. 首次启动需完整重启，不能使用 `/reload` 或 plugman。插件在 `onLoad` 注册 `cearcheology:brushable_block` 行为工厂，CraftEngine 的行为注册表不支持反注册，热重载会残留旧类加载器的工厂。检测到该情况时插件在 `onEnable` 自行禁用并输出说明。
3. 自带 CraftEngine 资源释放到 `plugins/CraftEngine/resources/cearcheology/`，只补缺失文件，不覆盖已有文件。升级后如需新的默认配置，先删除对应文件再重启。

数据目录 `plugins/CEArcheology/`：

```
config.yml                   显示偏移、刷子节奏、进度恢复、命令白名单、语言
languages/{zh_CN,en_US}.yml  插件侧消息
loot_tables/                 掉落表
tools/                       刷子定义
```

## 配置

### 掉落表

表按 `namespace:path` 命名，由方块的 `loot-table:` 引用。每个表含若干 pools，每个池按 rolls 次数、以 weight 为相对权重抽取。

| 字段 | 说明 |
| --- | --- |
| `type` | `item` / `entity` / `command` |
| `item` | 奖励物品；`entity` 与 `command` 下仅作展示图标 |
| `display-item` | 刷扫过程中展示的图标，省略时用 `item` |
| `count` | 数量，支持数字、`"min~max"`、`{min:, max:}`；`amount` 为别名 |
| `weight` | 池内相对权重 |
| `entity` | `entity` 类型的实体 id |
| `command` | `command` 类型的控制台命令 |

`display-item` 使展示图标与奖励物品不同：

```yaml
- type: item
  item: minecraft:dirt
  display-item: minecraft:diamond
  count: 1~2
  weight: 4
```

数量区间在每次 roll 时确定一次，并随预设掉落持久化，展示数量与发放数量一致。

### 刷子

三档刷子，CraftEngine 物品定义在 `cearcheology/configuration/brushes.yml`，参数在 `tools/brush_*.yml`。

| 刷子 | speed | 耐久 | bonus-progress | brush-level |
| --- | --- | --- | --- | --- |
| `cearcheology:brush_copper` | 1.5 | 250 | 0 | 1 |
| `cearcheology:brush_diamond` | 2.0 | 750 | 0 | 2 |
| `cearcheology:brush_netherite` | 3.0 | 1500 | 1 | 3 |

三档使用原版刷子贴图。需要单独外观时，把贴图放入 `resourcepack/assets/cearcheology/textures/item/` 并修改该档的 `model`。

内置方块的 `required-brush-level` 均为 1，所有档位都能刷所有方块，档位只影响速度与耐久。需要分档限定时调高方块的 `required-brush-level`。

### 逐状态硬度

六个方块按进度递减硬度，石头为 1.0 / 0.9 / 0.8 / 0.7 / 0.6，其余按各自基础硬度等比递减。全部状态设为同一数值即恢复统一硬度：

```yaml
variants:
  brush_progress=0:
    appearance: progress_0
    settings:
      hardness: 1.0
```

### 世界生成

配置位于 `cearcheology/subpacks/worldgen/`，由 `pack.yml` 的 `worldgen` 开关控制，默认关闭，仅影响新生成区块。

- 高度分布使用 `minecraft:trapezoid` 与 `plateau: 0`。原版没有 gaussian 高度 provider，CraftEngine 的 `placement` 也只接受原版 placement modifier。
- 无法按结构限定生成。原版没有对应的 placement modifier，改为通过替换的目标方块表达：`suspicious_sculk` 靶定 `minecraft:sculk`，即限定在幽匿块出现的区域。

## 命令与权限

主命令 `/cearcheology`，别名 `/cea`、`/cearch`。

| 子命令 | 权限 | 说明 |
| --- | --- | --- |
| `help` | `cearcheology.command.help` | 显示帮助 |
| `give` | `cearcheology.command.give` | 获取方块 / 工具 |
| `place` | `cearcheology.command.place` | 放置考古方块 |
| `reload` | `cearcheology.command.reload` | 重载插件配置 |

- `cearcheology.play.brush`（默认所有人）：允许刷扫
- `cearcheology.bypass.protection`（默认 OP）：跳过区域保护检查

## 区域保护

均为软依赖，缺失时跳过。单个钩子初始化失败只影响该钩子。

- 编译期依赖：WorldGuard、Lands
- 反射集成（`hook/protection/`）：GriefPrevention、Towny、PlotSquared、Residence

## 目录结构

```
src/main/java/cn/mymc/cearcheology/
  behavior/  方块行为、方块实体控制器、奖励解析
  listener/  包监听、方块放置监听
  manager/   掉落表、工具、进度存储、刷子交互跟踪
  hook/      CraftEngine、第三方物品源、区域保护
  command/   命令与子命令
  config/ locale/ util/

src/main/resources/
  cearcheology/  释放进 CraftEngine/resources/ 的内容
  languages/     插件侧消息
  loot_tables/ tools/ config.yml
```

## 已知限制

- 不支持热重载（`/reload`、plugman）。
- `/ce reload` 会覆盖本插件的包监听包装，插件监听 `CraftEngineReloadEvent` 后重新挂钩。
- `BrushPacketListener` 依赖 CraftEngine 内部的 `c2sPlayPacketListeners`，通过反射获取。版本不兼容时 `register()` 捕获异常并退化到手抬判定，不影响启动。
- 刷扫展示物是纯发包的 `item_display`，不生成服务端实体。实体 id 取自 CraftEngine 的实体计数器，可见性由 CraftEngine 的方块实体追踪控制。

## 实现依赖

除 `craft-engine-bukkit` 与 `craft-engine-core` 外，编译期还依赖 `craft-engine-bukkit-proxy`。展示物需要构造 `ClientboundAddEntity` / `ClientboundRemoveEntities` / `ClientboundSetEntityData` 三个数据包，这些类不在 `craft-engine-bukkit` 内。CraftEngine 将其标记为 `DependencyVisibility.PUBLIC`，运行时由 CraftEngine 注入自身类加载器。
