package com.hivemc.chunker.downgrade;

/**
 * 一个保证站在真实地面上的坐标，可直接写进目标存档的 level.dat。
 * <p>
 * 现代存档的出生点常在 Y=-60 甚至更低，低于目标格式的下限。位置一旦越界会被直接拒绝，
 * 服务端想在那起步就会报「找不到安全的出生点」并拒绝加载世界。
 * 把测量阶段量到的位置带出来，两个问题一起解决，且不额外花代价。
 *
 * @param x 方块 X 坐标。
 * @param y 方块 Y 坐标，在地面之上。
 * @param z 方块 Z 坐标。
 */
public record SpawnPoint(int x, int y, int z) {
}
