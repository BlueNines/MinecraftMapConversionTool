# 1.12.2 预览方块状态修复

## 原因与范围

BlueMap 1.5.5 的 `mc1_12/blockIds.json` 为六种木质栅栏门生成 `facing/open/powered`，但没有 `in_wall`，也没有对应的栅栏门状态扩展。原版客户端所有栅栏门模型条件都要求 `in_wall`。因此 `BlockStateResource.getModels()` 未命中任何条件，静默回退首个模型，使朝向和开合看起来丢失。导出地图的 ID/Data 本身没有因此改变。

`LegacyPreviewResources` 为结果预览生成一个约数 KB 的资源包。明确的 `in_wall=true` 条件优先；其他情况按 `facing/open` 选择普通栅栏门模型。保留原版贴图和模型引用，不修改 BlueMap jar 或地图。

首次模型补丁仅解决朝向和开合。随后增加独立的邻接扩展补丁，修复旧版木栅栏仅识别其他栅栏与完整方块、漏掉栅栏门的问题。按照 1.12.2 的规则，栅栏连接门的横杆两端，不能穿过门平面连接；开合和通电不改变连接轴。门两端有圆石墙时也正确计算 `in_wall`。

邻接补丁源码位于 `cli/src/legacyPreview/java`，单独编译成约 4 KB 的 jar 并嵌入主程序。启动旧 BlueMap 时将补丁放在其类路径前端，通过原有扩展注册入口加载；不修改第三方 jar，不进入现代源地图进程或转换器类路径。读取原始相邻方块，不递归扩展，不复制世界、不新增全世界缓存。

结果缓存完成标记升级为 `complete-render-v4.marker`。旧缓存下次渲染强制完整重建一次；完成后继续增量更新。相同兼容包与补丁不重写，避免重复触发资源变化检查。

## 验证（2026-09-16）

- 使用自带 BlueMap 和原版 Minecraft 客户端模型测试六种木质栅栏门：4 朝向 × 2 开合 × 2 通电 = 96 组，修复前均未命中模型条件，修复后均命中；几何和旋转与补齐属性后的原版模型一致。
- 额外验证 96 组明确贴墙模型、木质和铁质活板门 32 组状态，全部通过。
- 使用实际 `aa_1.12.2` 地图和 BlueMap 的完整状态扩展链抽取 203 种状态：12 种栅栏门模型回退全部消除。活板门未复现同类匹配问题；空花盆另有旧版模型回退，与本次方向问题无关。
- 在独立目录完整渲染真实地图，并通过浏览器在同一坐标和镜头下对照旧预览：错向的栅栏门恢复正确方向。
- 单元测试覆盖兼容包重复安装时保持时间戳、损坏后恢复；预览生命周期回归测试通过。
- 邻接回归通过真实 Anvil 读写链验证六种门 × 16 元数据状态，四向连接／不连接、不同木种连接、跨区块边界、圆石及苔石墙、完整方块与空气、木栅栏不连接地狱砖栅栏。不会把任意相邻门无条件连接。
- 独立重渲染 `aa_1.12.2`，在 `x=-107,y=30,z=22` 的同一镜头核对：原来门与栅栏之间断开的横杆已连通。用新端口隔离浏览器旧瓦片缓存；完整 Gradle 测试通过。

## 复跑模型测试

`tools/tests/LegacyPreviewStates.java` 直接调用随包提供的 BlueMap API，不是模拟模型选择逻辑。用 Java 21 编译运行，参数依次为缓存客户端 jar、资源扩展 zip、兼容资源包 zip：

```powershell
$bm = 'dist/bluemap/BlueMap-1.5.5-cli.jar'
$preview = 'dist/work/viewers/对应的结果目录'
New-Item -ItemType Directory -Force work/legacy-state-tests | Out-Null
javac -encoding UTF-8 -cp $bm -d work/legacy-state-tests tools/tests/LegacyPreviewStates.java
java -Xmx1G -cp "work/legacy-state-tests;$bm" LegacyPreviewStates `
  "$preview/data/minecraft-client-1.12.0.jar" `
  "$preview/data/resourceExtensions.zip" `
  "$preview/resourcepacks/chunker-legacy-states-v1.zip"
```

预期输出：`PASS gates=96 wallVariants=96 trapdoors=32`。
