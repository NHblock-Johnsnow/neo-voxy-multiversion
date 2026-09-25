# Neo Voxy · Multiversion

[简体中文](README.md)

Neo Voxy is maintained by **JohnSnow**. It continues [NHblock714/voxy](https://github.com/NHblock714/voxy) with multiversion maintenance, client optimizations, and optional mod integrations.

> [!IMPORTANT]
> If an update causes problems, delete the Neo Voxy configuration and the Voxy cache inside the affected world.
>
> **Build artifacts**: GitHub Actions artifacts have been manually deleted. Fork this repository, run `Build Neo Voxy multiversion` in your fork, and download the resulting artifacts; alternatively, follow the manual build instructions below. Artifacts that have not yet been deleted may occasionally remain visible on this branch.
>
> **Server data sharing**: Neo Voxy supports [voxy-server-side](https://www.curseforge.com/minecraft/mc-mods/voxy-server-side-forge-neoforge), which can be installed on the server to share LOD data.
>
> **Renderer dependencies**: NeoForge uses Sodium and Forge uses Embeddium. Sinytra Connector and Forgified Fabric API are not project dependencies and are not recommended.

## Supported Editions

| Edition | Install side | Renderer dependency | Java | Release file |
|---|---|---|---:|---|
| Minecraft 1.21.1 · NeoForge | Client required, server optional | Sodium 0.8.x / Iris 1.8.12+ | 21 | `neo-voxy-0.5.0-alpha.6-mc1.21.1-neoforge.jar` |
| Minecraft 1.20.1 · Forge | Client only | Embeddium / Oculus | 17 | `neo-voxy-0.3.5-alpha.2-mc1.20.1-forge-client.jar` |
| Minecraft 26.1.2 · NeoForge | Client only | Sodium 0.9.2 / Iris 1.11.4+ | 25 | `neo-voxy-0.3.4-mc26.1.2-neoforge-client.jar` |

## Installation and server compatibility (1.21.1)

In 1.21.1, static distant meshes hand over to sections in the vanilla draw list outside the terrain fade band. Domum keeps its previous mesh during replacement, beacons prepare ahead of camera movement, and trains share a per-frame ownership decision with delayed reacquisition. Prefetching slightly increases cache use near the boundary, while rebuild budgets remain limited. Teleporting into uncached areas still requires data and model preparation.

Starting with 0.5.0, 1.21.1 ships one JAR instead of separate integrations and client-only editions. Install it on the client to use distant rendering; optionally install the same JAR on the server. Dedicated servers do not need Sodium or Iris. Do not install both old editions together.

- Without Neo Voxy on the server: local terrain LODs, shaders, caches, and client-side integrations remain available. No Neo Voxy subscription payload is sent to unsupported servers.
- With the required negotiated channels: distant players and vehicles, Create trains, and moving-contraption poses can synchronize. Features lacking their required channels are disabled for that connection and unavailable in settings; saved preferences are retained for future supported connections.
- Without server support, Create contraptions can still use locally captured static snapshots, but distant live movement is unavailable. Extended chunk requests apply only to the integrated single-player server.
- Terrain LODs still depend on received or cached data. An optional server installation does not bypass server view distance to reveal unknown terrain. Other mods retain their own installation requirements.

1.20.1 and 26.1.2 keep their existing platform builds and installation sides; this update does not add server features to them.

## Experimental optimizations

The experimental page groups stationary optimizations, GPU workload, memory caching, terrain transitions, and simplified shading. Options cover stationary reuse, occlusion precision, batched Hi-Z, and cache limits, with FPS, visual, latency, and memory tradeoffs explained. Hi-Z compilation failures fall back with a warning. Compare options individually; performance gains are hardware-dependent.

## Vanilla Features

| Vanilla feature | 1.21.1 | 1.20.1 | 26.1.2 | Function |
|---|:---:|:---:|:---:|---|
| Terrain LODs and detail levels | ✅ | ✅ | ✅ | Renders distant terrain |
| Sodium / Embeddium settings integration | ✅ | ✅ | ✅ | Provides the Neo Voxy settings entry |
| Iris / Oculus shader pipeline | ✅ | ✅ | ✅ | Provides shader rendering support for the platform |
| Environmental fog, sky fog, and fluids | ✅ | ✅ | ✅ | Renders distant fog and fluid effects |
| Circular LOD handoff | ✅ | ❌ | ✅ | Transitions between vanilla chunks and LODs |
| Crossed ground plants | ✅ | ✅ | ✅ | Uses lightweight crossed plant models |
| Leaf LOD modes | ✅ | ✅ | ✅ | Provides Fast, Balanced, and Quality modes |
| Extended chunk requests | ✅ | ✅ | ✅ | Requests farther chunks in single-player; disabled by default |
| LOD biome water colours | ✅ | ✅ | ✅ | Handles water-colour transitions between biomes |
| LOD build-pressure control | ✅ | ✅ | ✅ | Balances frame rate and build speed |
| World curvature | ✅ | ✅ | ✅ | Curves distant terrain in the GPU vertex stage |
| Distant beacon beams | ✅ | — | — | Displays beacon beams at long range |
| Extended-height world coordinates | ✅ | — | — | Supports LODs outside the vanilla height range |

`✅` means supported, `❌` means unavailable, and `—` means not applicable. When a shader pack provides its own LOD transition, disable either its transition or Neo Voxy's circular handoff.

## Mod Integrations

Integrations activate only when the corresponding mod is installed. The version column lists the Minecraft and mod versions used as the current compatibility baseline.

| Mod (Chinese / English) and version | Status | Integration function |
|---|:---:|---|
| [机械动力 / Create](https://www.curseforge.com/minecraft/mc-mods/create)<br>MC 1.21.1 + Create 6.0.10 | ✅ | Covers distant trains, tracks, contraptions, and kinetic components |
| [机械动力：航空学 / Create Aeronautics](https://www.curseforge.com/minecraft/mc-mods/create-aeronautics)<br>MC 1.21.1 + Aeronautics 1.3.2 | 🧪 | Covers distant simulated structures |
| [机械动力：模拟 / Create Simulated](https://www.curseforge.com/minecraft/mc-mods/create-aeronautics)<br>MC 1.21.1 + Simulated 1.3.2 | 🧪 | Covers distant laser-pointer beams |
| [机械动力：交错电网 / Create: Power Grid](https://www.curseforge.com/minecraft/mc-mods/power-grid)<br>MC 1.21.1 + Power Grid 0.6.1 | ✅ | Covers hanging, routed, cord, and string-light wires |
| [机械动力：伪装方块+ / Create: Copycats+](https://www.curseforge.com/minecraft/mc-mods/copycats)<br>MC 1.21.1 + Copycats+ 3.0.9 | ✅ | Covers dedicated LOD models and materials for slopes, slices, doors, shafts, and cogwheels |
| [Sable / Sable](https://www.curseforge.com/minecraft/mc-mods/sable)<br>MC 1.21.1 + Sable 2.0.5 | ✅ | Covers distant physics structures and depth handoff |
| [节气 / Ecliptic Seasons](https://www.curseforge.com/minecraft/mc-mods/ecliptic-seasons)<br>MC 1.21.1 + Ecliptic Seasons 0.15.0-rc-3-1 | ✅ | Covers seasonal snow, frozen water, seasonal models, and colours |
| [模拟殖民地 / Domum Ornamentum](https://www.curseforge.com/minecraft/mc-mods/domum-ornamentum)<br>MC 1.21.1 + Domum Ornamentum 1.0.236-snapshot | ✅ | Covers detailed decorative models and materials |
| [框架方块 / FramedBlocks](https://www.curseforge.com/minecraft/mc-mods/framedblocks)<br>MC 1.21.1 + FramedBlocks 10.6.1 | ✅ | Covers framed blocks and camouflage materials |
| [小方块 / LittleTiles](https://www.curseforge.com/minecraft/mc-mods/littletiles)<br>MC 1.21.1 + LittleTiles 1.6.0-pre226 | 🧪 | Covers static microblock LOD meshes |

## Main Options

- Leaf mode: selects the performance, balanced, or quality leaf LOD mode.
- Extended chunk requests: requests chunks beyond vanilla view distance in single-player; disabled by default and capped at 48 chunks.
- LOD build pressure: balances maximum frame rate and catch-up speed.
- Circular LOD handoff: controls the transition between vanilla chunks and LODs; disable it when the shader pack provides its own transition.
- Render precision: a seven-level slider from Very low to Very high; Standard (256) is the default, Medium-high uses 123, and Very high uses 28. Higher precision increases detail and cost. Leaves use a three-level Fast / Balanced / Quality slider.
- World curvature: controls distant terrain curvature; 0 disables it.
- Integration switches: each mod integration has its own enable switch and distance option.

## Building

Build one edition on Windows:

```powershell
.\scripts\build.ps1 neoforge-1.21.1
.\scripts\build.ps1 client-1.20.1
.\scripts\build.ps1 client-26.1.2
```

Build all editions:

```powershell
.\scripts\build-all.ps1
```

The scripts prefer `JAVA_HOME_17`, `JAVA_HOME_21`, and `JAVA_HOME_25`. Linux/macOS equivalents are available as `scripts/build.sh` and `scripts/build-all.sh`. Final artifacts are copied to `dist/`.

## License

See the license file shipped with each edition.
