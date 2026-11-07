# Neo Voxy · 多版本

[English](README_EN.md)

Neo Voxy 由 **JohnSnow** 维护，基于 [NHblock714/voxy](https://github.com/NHblock714/voxy) 继续维护多版本、客户端优化与可选模组联动。

> [!IMPORTANT]
> 更新 Neo Voxy 后若出现异常，请先删除 Neo Voxy 配置文件和对应存档中的 Voxy 缓存。
>
> **构建**：GitHub Actions 构建产物已被人为删除。请 Fork 本仓库，并在自己的仓库中运行 `manual-artifact` 后下载产物；也可按照下方教程手动构建。
>
> **服务器数据共享**：Neo Voxy 兼容 [voxy-server-side](https://www.curseforge.com/minecraft/mc-mods/voxy-server-side-forge-neoforge)，可在服务器端安装以共享 LOD 数据。
>
> **前置**：NeoForge 使用 Sodium，Forge 使用 Embeddium。Sinytra Connector 与 Forgified Fabric API 不是本项目依赖，不建议额外安装。

## 支持版本

| 版本 | 安装位置 | 渲染前置 | Java | 发布文件 |
|---|---|---|---:|---|
| Minecraft 1.21.1 · NeoForge | 客户端必装，服务端可选 | Sodium 0.8.x / Iris 1.8.12+ | 21 | `neo-voxy-0.5.0-beta-mc1.21.1-neoforge.jar` |
| Minecraft 1.20.1 · Forge | 仅客户端 | Embeddium / Oculus | 17 | `neo-voxy-0.4.0-beta-mc1.20.1-forge-client.jar` |
| Minecraft 26.1.2 · NeoForge | 仅客户端 | Sodium 0.9.2 / Iris 1.11.4+ | 25 | `neo-voxy-0.4.0-beta-mc26.1.2-neoforge-client.jar` |


## 实验性优化

实验性页面包含静止时优化、显卡负担优化、内存缓存、远近地形过渡和光影简化。提供静止复用、遮挡精度、Hi-Z 批量计算与缓存上限等选项；说明列出帧率、画面、延迟和内存取舍。Hi-Z 编译失败会回退并提示。请逐项比较效果，不保证所有硬件均有性能提升。

## 原版功能

| 原版功能 | 1.21.1 | 1.20.1 | 26.1.2 | 功能说明 |
|---|:---:|:---:|:---:|---|
| Iris / Oculus  | ✅ | ✅ | ✅ | 光影支持 |
| 地面植物交叉模型 | ✅ | ✅ | ✅ | 使用轻量交叉模型显示植物 |
| 树叶 LOD 模式 | ✅ | ✅ | ✅ | 提供性能、平衡和质量模式 |
| 扩展区块请求 | ✅ | ✅ | ✅ | 单人游戏中请求更远区块，默认关闭 |
| LOD 群系水色 | ✅ | ✅ | ✅ | 处理不同群系之间的水色过渡 |
| LOD 构建压力控制 | ✅ | ✅ | ✅ | 调整帧率与构建速度的优先级 |
| 世界曲率 | ✅ | ✅ | ✅ | 在 GPU 顶点阶段弯曲远景地形 |
| LOD信标光束 | ✅ | — | — | 在远距离显示信标光束 |

`✅` 表示支持，`❌` 表示未提供，`—` 表示不适用。

## Mod 联动功能

联动功能仅在对应 Mod 已安装时启用。目前1.21.1完全支持，1.20.1部分支持。

| Mod（中文 / English）与版本 | 状态 | 联动功能 |
|---|:---:|---|
| [机械动力](https://www.curseforge.com/minecraft/mc-mods/create)<br>MC 1.21.1 + Create 6.0.10 | ✅ | LOD远景列车、轨道、动态结构与动力部件 |
| [机械动力：航空学](https://www.curseforge.com/minecraft/mc-mods/create-aeronautics)<br>MC 1.21.1 + Aeronautics 1.3.2 | 🧪 | 激光指示器LOD光束 |
| [机械动力：交错电网](https://www.curseforge.com/minecraft/mc-mods/power-grid)<br>MC 1.21.1 + Power Grid 0.6.1 | ✅ | LOD悬垂线、软线和灯串线 |
| [机械动力：伪装方块+](https://www.curseforge.com/minecraft/mc-mods/copycats)<br>MC 1.21.1 + Copycats+ 3.0.9 | ✅ | 斜坡、切片、传动杆和齿轮的专属 LOD 模型 |
| [Sable](https://www.curseforge.com/minecraft/mc-mods/sable)<br>MC 1.21.1 + Sable 2.0.5 | ✅ | LOD载具 |
| [节气](https://www.curseforge.com/minecraft/mc-mods/ecliptic-seasons)<br>MC 1.21.1 + Ecliptic Seasons 0.15.0-rc-3-1 | ✅ | LOD季节积雪、结冰水体、季节模型与颜色 |
| [模拟殖民地](https://www.curseforge.com/minecraft/mc-mods/domum-ornamentum)<br>MC 1.21.1 + Domum Ornamentum 1.0.236-snapshot | ✅ | LOD DO模型与材质 |
| [框架方块](https://www.curseforge.com/minecraft/mc-mods/framedblocks)<br>MC 1.21.1 + FramedBlocks 10.6.1 | ✅ | LOD框架方块与伪装材质 |
| [小方块](https://www.curseforge.com/minecraft/mc-mods/littletiles)<br>MC 1.21.1 + LittleTiles 1.6.0-pre226 | 🧪 | 仅测试功能，性能较差 |


## 构建

Windows 单独构建：

```powershell
.\scripts\build.ps1 neoforge-1.21.1
.\scripts\build.ps1 client-1.20.1
.\scripts\build.ps1 client-26.1.2
```

构建全部版本：

```powershell
.\scripts\build-all.ps1
```

脚本优先读取 `JAVA_HOME_17`、`JAVA_HOME_21` 和 `JAVA_HOME_25`。Linux/macOS 可使用 `scripts/build.sh` 和 `scripts/build-all.sh`。最终产物复制到 `dist/`。

## 许可证

请查看各版本随附的许可证文件。
