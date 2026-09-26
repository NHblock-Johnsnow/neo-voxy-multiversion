package me.cortex.voxy.client.compat.powergrid;

import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.compat.create.DistantLightSampler;
import me.cortex.voxy.client.compat.create.DistantMesh;
import me.cortex.voxy.client.compat.create.DistantMeshBuilder;
import me.cortex.voxy.client.compat.create.DistantShaders;
import me.cortex.voxy.client.compat.create.DistantVisibility;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

public final class PowerGridWireRenderer implements LodPipelineHooks.Renderer {
    static final int SHAPE_CATENARY = 0;
    static final int SHAPE_POLYLINE = 1;
    static final int MAX_POLYLINE_POINTS = 4096;
    private static final java.util.Set<String> WIRE_TYPES = java.util.Set.of(
            "powergrid:hanging_wire", "powergrid:block_wire", "powergrid:cord", "powergrid:string_light_cord");
    private static final int MAX_SEGMENTS = 192;
    private static final long SAVE_INTERVAL_MS = 2000;
    private final Map<UUID, Entity> live = new HashMap<>();
    private final Map<UUID, Entry> wires = new HashMap<>();
    private final Map<Class<?>, Reflection> reflections = new HashMap<>();
    private net.minecraft.resources.ResourceLocation loadedDimension;
    private int tick;
    private boolean reflectionError;

    public static volatile int wireCount;
    public static volatile int lastFrameDrawn;

    public record Source(double x1, double y1, double z1, double x2, double y2, double z2,
                         double length, float thickness, int color, int shape, double[] points) {
        Source(double x1, double y1, double z1, double x2, double y2, double z2,
               double length, float thickness, int color) {
            this(x1, y1, z1, x2, y2, z2, length, thickness, color, SHAPE_CATENARY, null);
        }

        boolean valid() {
            boolean base = Double.isFinite(x1) && Double.isFinite(y1) && Double.isFinite(z1)
                    && Double.isFinite(x2) && Double.isFinite(y2) && Double.isFinite(z2)
                    && Double.isFinite(length) && length > 0 && Float.isFinite(thickness) && thickness > 0;
            if (!base || (shape != SHAPE_CATENARY && shape != SHAPE_POLYLINE)) return false;
            if (shape == SHAPE_CATENARY) return true;
            if (points == null || points.length < 6 || points.length % 3 != 0
                    || points.length > MAX_POLYLINE_POINTS * 3) return false;
            for (double point : points) if (!Double.isFinite(point)) return false;
            return true;
        }

        long signature() {
            long h = Double.doubleToLongBits(x1);
            h = h * 31 + Double.doubleToLongBits(y1); h = h * 31 + Double.doubleToLongBits(z1);
            h = h * 31 + Double.doubleToLongBits(x2); h = h * 31 + Double.doubleToLongBits(y2);
            h = h * 31 + Double.doubleToLongBits(z2); h = h * 31 + Double.doubleToLongBits(length);
            h = h * 31 + Float.floatToIntBits(thickness); h = h * 31 + color; h = h * 31 + shape;
            h = h * 31 + java.util.Arrays.hashCode(points);
            return h;
        }
    }

    private static final class Entry {
        Source source;
        DistantMesh mesh;
        long signature;
        long lastSavedMs;

        Entry(Source source) {
            this.source = source;
            this.signature = source.signature();
        }

        void close() {
            if (mesh != null) mesh.free();
            mesh = null;
        }
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ClientLevel && isWire(event.getEntity())) {
            live.put(event.getEntity().getUUID(), event.getEntity());
        }
    }

    @SubscribeEvent
    public void onEntityLeave(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        if (!(event.getLevel() instanceof ClientLevel level) || !isWire(entity)) return;
        capture(level, entity, true);
        live.remove(entity.getUUID());
        var reason = entity.getRemovalReason();
        var player = Minecraft.getInstance().player;
        boolean removedNearby = player != null && entity.distanceToSqr(player) < 32.0 * 32.0;
        if (reason == Entity.RemovalReason.KILLED || (reason == Entity.RemovalReason.DISCARDED && removedNearby)) {
            Entry removed = wires.remove(entity.getUUID());
            if (removed != null) removed.close();
            PowerGridWireStore.remove(storageFor(level), entity.getUUID());
        }
        wireCount = wires.size();
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !VoxyConfig.CONFIG.distantPowerGridWires) return;
        ensureDimension(mc.level);
        if ((++tick & 1) != 0) return;
        for (Entity entity : live.values().toArray(Entity[]::new)) {
            if (entity.isRemoved()) live.remove(entity.getUUID());
            else capture(mc.level, entity, false);
        }
        int baked = 0;
        for (Entry entry : wires.values()) {
            if (entry.mesh == null) {
                entry.mesh = bake(mc.level, entry.source);
                if (++baked >= 16) break;
            }
        }
        wireCount = wires.size();
    }

    @SubscribeEvent
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        var level = Minecraft.getInstance().level;
        if (level != null) checkpoint(level);
        clear();
    }

    private void ensureDimension(ClientLevel level) {
        var dimension = level.dimension().location();
        if (dimension.equals(loadedDimension)) return;
        clearMeshes();
        live.clear();
        wires.clear();
        for (Entity entity : level.entitiesForRendering()) {
            if (isWire(entity)) live.put(entity.getUUID(), entity);
        }
        loadedDimension = dimension;
        var storage = storageFor(level);
        if (storage == null) {
            loadedDimension = null;
            return;
        }
        for (var stored : PowerGridWireStore.loadAll(storage)) wires.put(stored.id(), new Entry(stored.source()));
    }

    private void capture(ClientLevel level, Entity entity, boolean forceSave) {
        try {
            Reflection reflection = reflections.computeIfAbsent(entity.getClass(), type -> {
                try {
                    return new Reflection(type, wireType(entity));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Source source = reflection.read(entity);
            if (source == null || !source.valid()) return;
            UUID id = entity.getUUID();
            Entry entry = wires.get(id);
            long signature = source.signature();
            boolean changed = false;
            if (entry == null) {
                entry = new Entry(source);
                wires.put(id, entry);
                forceSave = true;
            } else if (entry.signature != signature) {
                entry.close();
                entry.source = source;
                entry.signature = signature;
                changed = true;
            }
            long now = System.currentTimeMillis();
            if (forceSave || (changed && now - entry.lastSavedMs >= SAVE_INTERVAL_MS)) {
                PowerGridWireStore.save(storageFor(level), id, source);
                entry.lastSavedMs = now;
            }
        } catch (Throwable t) {
            if (!reflectionError) {
                reflectionError = true;
                Logger.error("PowerGrid wire LOD API mismatch; compatibility disabled for this session", t);
            }
            live.clear();
        }
    }

    @Override
    public void render(me.cortex.voxy.client.core.AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        lastFrameDrawn = 0;
        var cfg = VoxyConfig.CONFIG;
        var mc = Minecraft.getInstance();
        if (mc.level == null || !cfg.isRenderingEnabled() || !cfg.distantPowerGridWires || wires.isEmpty()) return;
        double max = cfg.createRenderDistance(cfg.distantPowerGridWireMaxChunks);
        double maxSq = max * max;
        pipeline.setupAndBindOpaque(viewport);
        boolean active = false;
        int drawn = 0;
        var transform = new Matrix4f();
        try {
            for (var mapEntry : wires.entrySet()) {
                if (live.containsKey(mapEntry.getKey())) continue;
                Entry entry = mapEntry.getValue();
                if (entry.mesh == null) continue;
                Source s = entry.source;
                double ox = (s.x1 + s.x2) * 0.5, oy = s.y1, oz = (s.z1 + s.z2) * 0.5;
                double dx = ox - viewport.cameraX, dy = oy - viewport.cameraY, dz = oz - viewport.cameraZ;
                if (dx * dx + dy * dy + dz * dz > maxSq) continue;
                var mesh = entry.mesh;
                if (!DistantVisibility.isBoxVisible(viewport,
                        ox + mesh.minX, oy + mesh.minY, oz + mesh.minZ,
                        ox + mesh.maxX, oy + mesh.maxY, oz + mesh.maxZ)) continue;
                if (!active) {
                    DistantShaders.forPipeline(pipeline, false).bind();
                    DistantShaders.bindTextures();
                    glEnable(GL_DEPTH_TEST); glDepthFunc(depthFunc); glDepthMask(true);
                    glDisable(GL_CULL_FACE);
                    glEnable(GL_STENCIL_TEST); glStencilFunc(GL_ALWAYS, 3, 0xFF);
                    glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
                    active = true;
                }
                transform.set(viewport.MVP).translate((float) (ox - viewport.cameraX),
                        (float) (oy - viewport.cameraY), (float) (oz - viewport.cameraZ));
                DistantShaders.uploadTransform(transform);
                mesh.drawThin(viewport, transform, ox, oy, oz);
                drawn++;
            }
        } finally {
            if (active) {
                glBindVertexArray(0); glUseProgram(0);
                glStencilFunc(GL_EQUAL, 1, 0x1); glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            }
            lastFrameDrawn = drawn;
        }
    }

    private static DistantMesh bake(ClientLevel level, Source s) {
        var builder = new DistantMeshBuilder(false);
        try {
            var sprite = Minecraft.getInstance().getBlockRenderer().getBlockModel(Blocks.WHITE_CONCRETE.defaultBlockState())
                    .getParticleIcon(net.neoforged.neoforge.client.model.data.ModelData.EMPTY);
            float u = (sprite.getU0() + sprite.getU1()) * 0.5f;
            float v = (sprite.getV0() + sprite.getV1()) * 0.5f;
            double ox = (s.x1 + s.x2) * 0.5, oy = s.y1, oz = (s.z1 + s.z2) * 0.5;
            if (s.shape == SHAPE_POLYLINE) {
                double[] points = s.points;
                for (int i = 3; i < points.length; i += 3) {
                    Vec3 previous = new Vec3(points[i - 3] - ox, points[i - 2] - oy, points[i - 1] - oz);
                    Vec3 next = new Vec3(points[i] - ox, points[i + 1] - oy, points[i + 2] - oz);
                    if (previous.distanceToSqr(next) < 1.0e-10) continue;
                    Vec3 worldMid = previous.add(next).scale(0.5).add(ox, oy, oz);
                    int light = DistantLightSampler.samplePeek(level, (int) Math.floor(worldMid.x),
                            (int) Math.floor(worldMid.y), (int) Math.floor(worldMid.z));
                    emitPrism(builder, previous, next, Math.max(0.0225f, s.thickness), u, v,
                            DistantLightSampler.sky(light), DistantLightSampler.block(light), s.color);
                }
                return builder.buildPolyline();
            }
            double hx = s.x2 - s.x1, hz = s.z2 - s.z1, horizontal = Math.sqrt(hx * hx + hz * hz);
            double vertical = s.y2 - s.y1;
            int segments = Math.clamp((int) Math.ceil(s.length / 2.0), 5, MAX_SEGMENTS);
            Catenary curve = new Catenary(horizontal, vertical, Math.max(s.length, Math.sqrt(horizontal * horizontal + vertical * vertical) + 0.001));
            Vec3 previous = point(curve, 0, segments, hx, hz, horizontal);
            for (int i = 1; i <= segments; i++) {
                Vec3 next = point(curve, i, segments, hx, hz, horizontal);
                Vec3 worldMid = previous.add(next).scale(0.5).add(ox, oy, oz);
                int light = DistantLightSampler.samplePeek(level, (int) Math.floor(worldMid.x),
                        (int) Math.floor(worldMid.y), (int) Math.floor(worldMid.z));
                emitPrism(builder, previous, next, Math.max(0.0225f, s.thickness), u, v,
                        DistantLightSampler.sky(light), DistantLightSampler.block(light), s.color);
                previous = next;
            }
            return builder.buildPolyline();
        } catch (Throwable t) {
            builder.discard();
            Logger.error("Baking PowerGrid wire LOD", t);
            return null;
        }
    }

    private static Vec3 point(Catenary c, int i, int count, double hx, double hz, double horizontal) {
        double x = ((double) i / count - 0.5) * horizontal;
        if (horizontal < 1.0e-6) return new Vec3(0, c.dy * i / count, 0);
        return new Vec3(hx / horizontal * x, c.y(x), hz / horizontal * x);
    }

    private static void emitPrism(DistantMeshBuilder b, Vec3 a, Vec3 z, float thickness,
                                  float u, float v, int sky, int block, int color) {
        Vec3 d = z.subtract(a).normalize();
        Vec3 side = d.cross(new Vec3(0, 1, 0));
        if (side.lengthSqr() < 1.0e-8) side = d.cross(new Vec3(1, 0, 0));
        side = side.normalize().scale(thickness * 0.5);
        Vec3 up = d.cross(side).normalize().scale(thickness * 0.5);
        Vec3[] p = {a.add(side).add(up), a.subtract(side).add(up), a.subtract(side).subtract(up), a.add(side).subtract(up)};
        Vec3[] q = {z.add(side).add(up), z.subtract(side).add(up), z.subtract(side).subtract(up), z.add(side).subtract(up)};
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            Vec3 normal = p[i].subtract(a).add(p[j].subtract(a)).normalize();
            Direction face = face(normal);
            float shade = switch (face) { case DOWN -> 0.5f; case NORTH, SOUTH -> 0.8f; case WEST, EAST -> 0.6f; default -> 1.0f; };
            raw(b, p[i], u, v, sky, block, shade, face, color);
            raw(b, p[j], u, v, sky, block, shade, face, color);
            raw(b, q[j], u, v, sky, block, shade, face, color);
            raw(b, q[i], u, v, sky, block, shade, face, color);
        }
    }

    private static void raw(DistantMeshBuilder b, Vec3 p, float u, float v, int sky, int block,
                            float shade, Direction face, int color) {
        b.rawVertex((float) p.x, (float) p.y, (float) p.z, u, v, sky, block, shade,
                face.get3DDataValue(), color & 0xFFFFFF);
    }

    private static Direction face(Vec3 n) {
        double ax = Math.abs(n.x), ay = Math.abs(n.y), az = Math.abs(n.z);
        if (ay >= ax && ay >= az) return n.y >= 0 ? Direction.UP : Direction.DOWN;
        if (az >= ax) return n.z >= 0 ? Direction.SOUTH : Direction.NORTH;
        return n.x >= 0 ? Direction.EAST : Direction.WEST;
    }

    private static boolean isWire(Entity entity) {
        return WIRE_TYPES.contains(wireType(entity));
    }

    private static String wireType(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static me.cortex.voxy.common.config.section.SectionStorage storageFor(ClientLevel level) {
        var engine = me.cortex.voxy.commonImpl.WorldIdentifier.ofEngineNullable(level);
        return engine == null ? null : engine.storage;
    }

    private void clearMeshes() {
        for (Entry entry : wires.values()) entry.close();
    }

    private void checkpoint(ClientLevel level) {
        var storage = storageFor(level);
        for (var entry : wires.entrySet()) PowerGridWireStore.save(storage, entry.getKey(), entry.getValue().source);
    }

    private void clear() {
        clearMeshes();
        wires.clear(); live.clear(); loadedDimension = null; reflections.clear(); reflectionError = false;
        wireCount = 0; lastFrameDrawn = 0;
    }

    private static final class Reflection {
        final boolean polyline;
        final Field terminal1, terminal2, placedLength, segments, pointDirection, pointLength;
        final Method getWireEntry, getColor, thickness, texture;

        Reflection(Class<?> wireClass, String type) throws Exception {
            polyline = "powergrid:block_wire".equals(type);
            terminal1 = polyline ? null : wireClass.getField("terminalPos1");
            terminal2 = polyline ? null : wireClass.getField("terminalPos2");
            placedLength = polyline ? null : findField(wireClass, "placedLength");
            segments = polyline ? wireClass.getField("segments") : null;
            Class<?> point = polyline ? Class.forName("org.patryk3211.powergrid.electricity.wire.BlockWireEntity$Point") : null;
            pointDirection = polyline ? point.getField("direction") : null;
            pointLength = polyline ? point.getField("gridLength") : null;
            getWireEntry = wireClass.getMethod("getWireEntry");
            getColor = wireClass.getMethod("getColor");
            Class<?> entry = getWireEntry.getReturnType();
            thickness = entry.getMethod("wireThickness");
            texture = entry.getMethod("texture");
        }

        Source read(Entity entity) throws Exception {
            Object entry = getWireEntry.invoke(entity);
            if (entry == null) return null;
            float width = ((Number) thickness.invoke(entry)).floatValue();
            int dye = ((Number) getColor.invoke(entity)).intValue();
            String tex = String.valueOf(texture.invoke(entry));
            int color = dye == -1 ? materialColor(tex) : dye;
            if (polyline) return readPolyline(entity, width, color);
            Vec3 a = (Vec3) terminal1.get(entity), b = (Vec3) terminal2.get(entity);
            if (a == null || b == null) return null;
            double length = ((Number) placedLength.get(entity)).doubleValue();
            return new Source(a.x, a.y, a.z, b.x, b.y, b.z, length, width, color);
        }

        private Source readPolyline(Entity entity, float width, int color) throws Exception {
            List<?> sourceSegments = (List<?>) segments.get(entity);
            if (sourceSegments == null || sourceSegments.isEmpty()
                    || sourceSegments.size() + 1 > MAX_POLYLINE_POINTS) return null;
            double[] points = new double[(sourceSegments.size() + 1) * 3];
            Vec3 current = entity.position();
            points[0] = current.x; points[1] = current.y; points[2] = current.z;
            double length = 0.0;
            int offset = 3;
            for (Object segment : sourceSegments) {
                Direction direction = (Direction) pointDirection.get(segment);
                double segmentLength = pointLength.getInt(segment) / 16.0;
                current = current.add(direction.getStepX() * segmentLength,
                        direction.getStepY() * segmentLength, direction.getStepZ() * segmentLength);
                points[offset++] = current.x; points[offset++] = current.y; points[offset++] = current.z;
                length += Math.abs(segmentLength);
            }
            return new Source(points[0], points[1], points[2], current.x, current.y, current.z,
                    length, width, color, SHAPE_POLYLINE, points);
        }

        private static Field findField(Class<?> type, String name) throws Exception {
            for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
                try {
                    Field field = cursor.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                }
            }
            throw new NoSuchFieldException(name);
        }
    }

    private static int materialColor(String texture) {
        String s = texture.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("gold")) return 0xD6A82E;
        if (s.contains("iron")) return 0x8B8B86;
        if (s.contains("insulated") || s.contains("cord")) return 0x3D332B;
        return 0xB66A3C;
    }

    private static final class Catenary {
        final double a, b, c, dy;

        Catenary(double dx, double dy, double length) {
            this.dy = dy;
            if (dx < 1.0e-6) { a = b = c = 0; return; }
            double r = Math.sqrt(Math.max(0, length * length - dy * dy)) / dx;
            r = Math.max(1.000001, r);
            double x = r < 3 ? Math.sqrt(6 * (r - 1)) : Math.log(2 * r) + Math.log(Math.log(2 * r));
            for (int i = 0; i < 5; i++) x -= (Math.sinh(x) - r * x) / (Math.cosh(x) - r);
            a = dx / (2 * x);
            double z = Math.clamp(dy / length, -0.999999, 0.999999);
            b = -a * 0.5 * Math.log((1 + z) / (1 - z));
            c = 0.5 * (dy - length / Math.tanh(x));
        }

        double y(double x) { return a * Math.cosh((x - b) / a) + c; }
    }
}
