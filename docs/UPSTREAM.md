# 本项目与 Chunker（上游）的关系

本工具建立在 [Chunker](https://github.com/HiveGamesOSS/Chunker) 之上（MIT 许可）。
Chunker 负责 Minecraft 存档的格式解析、区块读写与各版本映射，本工具在它的基础上加了
「降级到 1.12.2」所需的全部东西：近似替换、Y 轴平移、转换报告、网页界面、增量写入与预览。

## 基线

| 项 | 值 |
|---|---|
| 上游仓库 | https://github.com/HiveGamesOSS/Chunker |
| 基线提交 | `31c91a92bd2dda746f3e41189b603fcfd1727f04` |
| 基线说明 | Merge pull request #2582 from HiveGamesOSS/develop |
| 基线日期 | 2026-09-02 13:13:46 +0100 |
| 许可 | MIT（见 `app/chunker/LICENSE`） |

上游的 git 历史没有带进本仓库（一个仓库里不能套一个仓库），而 `app/chunker/` 在本仓库里
只是一个普通目录。想对比上游或把上游的新修复合并进来时，拉一份上游到仓库外面去比：

```powershell
# 拉一份上游到临时目录（浅克隆就够，只是想对比代码）
git clone --depth 1 https://github.com/HiveGamesOSS/Chunker.git D:\temp\chunker-upstream

# 看某个文件跟上游差多少
diff D:\temp\chunker-upstream\cli\src\main\java\com\hivemc\chunker\conversion\WorldConverter.java `
     app\chunker\cli\src\main\java\com\hivemc\chunker\conversion\WorldConverter.java
```

如果以后需要频繁跟进上游，更省事的做法是把这份仓库改成真正的 fork（让上游历史作为
基底），代价是两边的目录结构要重新安排一次。目前没必要。

## 我们改了什么

改动分两类。**新增的代码全部放在独立的包里**，与上游代码分开，这样将来合并上游更新时冲突面最小。

### 一、新增文件


```
app/chunker/cli/src/main/java/com/hivemc/chunker/downgrade/
    Approximations.java          内置近似替换规则（把 1.12.2 没有的方块换成最接近的）
    BlockSurvey.java             转换前测量：最低方块 Y、方块统计
    ConversionReport.java        转换报告（JSON + 文本）
    EntityFilterHandler.java     删掉非悬挂实体，保留画与展示框
    ContainerClearHandler.java   保留容器方块、清空内部物品
    ShiftColumnHandler.java      Y 轴平移（按 section 对齐，连带搬运方块实体）
    ShiftStats.java              平移统计
    SurveyLevelWriter.java       只测量不写出的写入器
    SurveyResult.java            测量结果
    LosslessBlockHandler.java    无损处理接口（特殊方块的接入点）
    LosslessBlocks.java          无损处理的调度与待处理报告
    GhostBlockHandler.java       幽灵方块通道：把 1.12.2 装不下的方块连状态一起写成幽灵编号

app/chunker/cli/src/main/resources/ghostblocks/
    channel-patches.txt          20037 个（方块，状态）的幽灵编号对照表（由 GhostBlocks 项目生成，不要手改）

app/chunker/cli/src/main/java/com/hivemc/chunker/web/
    Main.java                    入口（默认开界面，带 cli 参数时走命令行）
    LocalApp.java                本地 HTTP 服务与全部接口
    ConversionJob.java           一次转换的编排
    BlueMapRunner.java           驱动两个版本的 BlueMap
    BlockIcons.java              从玩家本地客户端 jar 取方块贴图，画成立方体图标

app/chunker/cli/src/main/resources/downgrade/
    approximations.json          275 条近似替换规则（带注释与分组）

app/chunker/cli/src/main/resources/web/
    index.html                   整个网页界面（单文件，无外部依赖）
```

### 二、修改的上游文件

| 文件 | 改了什么 |
|---|---|
| `cli/build.gradle.kts` | 入口类改为 `com.hivemc.chunker.web.Main` |
| `cli/.../cli/CLI.java` | 加 `--shiftToFit`、`--noApproximations`；接上报告与近似替换 |
| `cli/.../conversion/WorldConverter.java` | 接入测量、平移、实体过滤、容器清空；未映射方块与替换的统计 |
| `cli/.../encoding/base/Converter.java` | 加两个统计用的默认方法 |
| `cli/.../java/base/resolver/JavaResolversBuilder.java` | 在写出处记录「替换了什么」与「丢了什么」；`resolveLegacyBlockIdentifier` 新增（与正式编码共用映射，但不记损失） |
| `cli/.../java/base/writer/JavaChunkWriter.java` | **按字段名排序后再写 NBT**（否则每次输出顺序随机，见下）；无损扩展钩子接入（传逐块原生编码） |
| `cli/.../java/base/writer/JavaColumnWriter.java` | 同上；接入增量写入 |
| `cli/.../java/base/writer/JavaWorldWriter.java` | 增量写入：内容没变就不重写、变了才打时间戳 |
| `cli/.../handlers/pipeline/Pipeline.java` | 新增追加式的 `addColumnHandler()`（原方法会覆盖预变换处理器） |
| `settings.gradle.kts` | 只构建 `cli`（不用 Electron 界面） |

### 三、为什么动了 NBT 字段顺序

这看起来像是多余的改动，但它是「预览能跟着改映射实时更新」的前提。

原来的写法里，两个异步任务往同一个列表里并发追加，谁先完成谁先落笔——所以同一张地图
连着转两次，输出字节也不一样。而增量写入靠「和磁盘上已有的字节比对」来判断某个区块
有没有变，字节每次都不同就意味着每次都认为全变了，于是一个区块也省不下来。

改成按字段名排序写入后，同样的输入必得同样的字节，增量才能成立。
NBT 规范里 Compound 的字段本就无序，读取方（原版客户端、BlueMap）都不受影响。

## 上游没有带进仓库的部分

| 目录 | 体积 | 为什么不用 |
|---|---|---|
| `app/chunker/cli/data/` | 607 MB | 各版本的方块/物品数据表（`bedrock/` 367 MB + `java/` 240 MB）。转换运行时不加载它。 |
| `app/chunker/app/` | 580 MB | 上游的 Electron + React 界面。本工具用的是自建的网页界面。 |
| `app/chunker/cli/src/test/resources/integration/worlds/` | 16 MB | **保留了**。上游自带的各版本测试地图，很有用。 |

排除前两项是验证过的：移开之后 `gradlew :cli:shadowJar` 构建成功，且产物字节数完全相同
（31926310 字节）。**但那只能证明「编译与打包」不受影响。**

### 测试会受影响（曾经坏过）

`cli/src/test` 里有 5 个测试会读 `data/java` / `data/bedrock` 来校验映射表：

```
JavaBlockIdentifierValidationTests      data/java
JavaItemIdentifierValidationTests       data/java
BedrockBlockIdentifierValidationTests   data/bedrock
BedrockItemIdentifierValidationTests    data/bedrock
BedrockLegacyBlockIdentifierValidationTests  data/bedrock
```

它们原本写的是 `Objects.requireNonNull(...listFiles())`，目录不存在时 `listFiles()` 返回 `null`，
于是直接 NPE——**全新克隆后跑 `:cli:test` 会挂 5 个测试**。这里注意：有数据的是 3 Bedrock + 2 Java，
只把 `java/`（240 MB）补回仓库并不能解决，必须连 `bedrock/`（367 MB）一起入，才共 607 MB。

现在这 5 处已改为 `Assumptions.assumeTrue(...)`：数据在就正常校验，数据不在就跳过并说明原因。
这样仓库体积保住，CI 也能全绿。想本地跑完整校验，把数据放回去即可（见下）。

### 想把数据取回来

```bash
cd app/chunker
git sparse-checkout set cli/data        # 需要上游 git 历史，见上方「基线」一节
```
