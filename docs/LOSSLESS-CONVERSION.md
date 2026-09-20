# 无损转换地图：处理方接入说明

## 当前行为

前端「转换模式」可选择降级转换地图／无损转换地图。两者输出版本均为 Java 1.12.2，复用分析、内容筛选、高度调整、实体处理、增量写盘及 BlueMap 预览。旧客户端未传 mode 时仍按降级转换。

无损模式只加载用户映射，不加载内置近似替换，即使 API 传 approximate=true 也不会开启内置替换。用户显式规则始终优先；没有手动规则的内置替换候选、原生无法编码的方块，进入下方处理接口。1.12.2 可原生表示的普通方块照常编码。

**目前是接入框架，不是已经实现高版本内容的无损保存。** 默认接口返回空，按方块类型和状态输出一次 `[无损转换待接入]` 日志及示例位置，报告按状态汇总数量。继续原生编码：能够表示的保留原生结果，不能表示的仍是空气占位，不会自动选择近似材质。界面明确显示此限制。

默认输出分别为 `<地图名>_1.12.2` 和 `<地图名>_lossless_1.12.2`；用户明确指定输出目录时尊重该路径。

## 从哪里接

1. 接口：`app/chunker/cli/src/main/java/com/hivemc/chunker/downgrade/LosslessBlockHandler.java`。
2. 默认实现与注入：`ConversionJob.losslessHandler`、`ConversionJob.setLosslessHandler(...)`。Web 的预分析和正式转换都创建 `ConversionJob`，如要默认启用实际处理器，应替换任务中的默认工厂／初始化位置，使两者一致。
3. 调度：`LosslessBlocks.needsHandling(...)` 和 `handle(...)`。不要另写扫描器或第二套转换流水线。
4. 真正写入位置：`JavaChunkWriter.writeBlockPalette(...)`，写 Blocks/Add/Data 之前。用户映射不走扩展；接口成功返回后直接写入返回值，不再走原生丢失回退统计。

```java
ConversionJob job = new ConversionJob(input, output, output,
        true, true, userMappingsJson, false, true);
job.setLosslessHandler(block -> {
    // 接入方在这里实现；block.identifier() 含完整中间类型和当前状态。
    // block.dimension()/x()/y()/z() 是高度调整后的输出位置。
    // 处理成功：return Optional.of(new LegacyIdentifier(id, (byte) data));
    // 暂未处理：返回空，统一日志和报告仍由本工具负责。
    return Optional.empty();
});
job.run();
```

处理器每次任务独立，但同一任务会并发调用，必须线程安全。支持返回 ID 0–4095、Data 0–15，越界会失败。不要逐方块打印或缓存整个世界。若实现需要附加实体、资源包或额外文件，可在处理器内使用任务级服务并扩展任务收尾；当前接口只定义单方块旧编码返回，不宣称已经实现这些能力。实体清理和高度限制继续遵循原流程。

## 输出与检查

- JSON 报告：`mode` 为 `lossless`；`lossless.pendingBlocks` 为待接入数量；`lossless.pending` 含名称、状态及数量。
- 文本报告也写明待处理数量与阶段限制。
- 日志示例包含输出维度、位置；报告仅聚合，不保存每个实例，内存随不同状态数增长。
- `unmapped` 继续记录实际原生回退造成的损失；待接入数量与损失数量不是同一概念（部分候选仍可原生表示）。

## 已验证

真实 aa 地图：降级自动替换 22,774；无损默认自动替换 0；无损手动配置 quartz_bricks → stone 后替换 4,023，且这些方块不进入待处理列表。另有单元测试覆盖普通方块绕过接口、用户映射优先、状态聚合、注入返回编码。所有真实转换测试输出均在独立临时目录，未覆盖用户地图。
