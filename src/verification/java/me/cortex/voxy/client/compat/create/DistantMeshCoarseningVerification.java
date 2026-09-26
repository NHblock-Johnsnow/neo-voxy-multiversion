package me.cortex.voxy.client.compat.create;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 使用体积模型和本地 Create OBJ 检查实际面数、轮廓与缩放选择。 */
public final class DistantMeshCoarseningVerification {
    private static final int[][] CORNERS = {
            {0,1,5,4}, {2,6,7,3}, {0,2,3,1}, {4,5,7,6}, {0,4,6,2}, {1,3,7,5}
    };
    private static int checks;

    record Fixture(String name, ByteBuffer data) {}

    static List<Fixture> fixtures() throws Exception {
        var result = new ArrayList<Fixture>();
        var carriage = buffer();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) {
            if (y == 0 || y == 3 || x == 0 || x == 3) box(carriage,x,y,z,x+1,y+1,z+1);
        }
        result.add(new Fixture("block-built carriage", carriage.flip()));
        var roof = buffer();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            float y = (float) (4 * Math.sin((x + .5) * Math.PI / 16));
            box(roof,x,y,z,x+1,y+.25f,z+1);
            box(roof,x,y+.25f,z+.1f,x+.08f,y+.35f,z+.9f);
        }
        result.add(new Fixture("decorated roof fixture",roof.flip()));
        var small = buffer();
        for (int z = 0; z < 6; z++) box(small,0,0,z*.5f,1,.125f,z*.5f+.2f);
        result.add(new Fixture("small contraption below 128 quads",small.flip()));
        Path assets = Path.of(System.getProperty("voxy.verification.createTrackAssets",
                "../voxy integrations/Create/src/main/resources/assets/create/models/block/track"));
        if (Files.isRegularFile(assets.resolve("tie.obj"))) {
            var tie = readObj(assets.resolve("tie.obj"));
            var left = readObj(assets.resolve("segment_left.obj"));
            var right = readObj(assets.resolve("segment_right.obj"));
            var track = buffer();
            var transform = new Matrix4f();
            for (int i = 0; i < 48; i++) {
                float angle = i * .018f;
                transform.identity().translate((float) (8 * Math.sin(angle)),0,i*.5f).rotateY(angle);
                appendObj(track,tie,transform);
                appendObj(track,left,new Matrix4f(transform).translate(-.75f,0,0));
                appendObj(track,right,new Matrix4f(transform).translate(.75f,0,0));
            }
            result.add(new Fixture("Create tie/left/right OBJ curved track",track.flip()));
        } else {
            System.out.println("Create OBJ assets unavailable; real-asset check skipped");
        }
        return result;
    }

    public static void run() throws Exception {
        for (Fixture fixture : fixtures()) verify(fixture);
        verifyWire();
        verifyTransparentMerge();
        System.out.println("Passed " + checks + " volumetric mesh coarsening checks");
    }

    static Fixture wireFixture() {
        var out = buffer();
        // 与电线相同的四侧分段布局，模拟有垂度的细线。
        for (int segment = 0; segment < 64; segment++) {
            float z0 = segment, z1 = segment + 1;
            float y0 = (segment - 32) * (segment - 32) / 256f;
            float y1 = (segment - 31) * (segment - 31) / 256f;
            float[] x = {.025f,-.025f,-.025f,.025f};
            float[] y = {.025f,.025f,-.025f,-.025f};
            for (int face = 0; face < 4; face++) {
                int next = (face + 1) & 3;
                vertex(out,x[face],y0+y[face],z0,.5f,.5f,1);
                vertex(out,x[next],y0+y[next],z0,.5f,.5f,1);
                vertex(out,x[next],y1+y[next],z1,.5f,.5f,1);
                vertex(out,x[face],y1+y[face],z1,.5f,.5f,1);
            }
        }
        return new Fixture("curved thin wire",out.flip());
    }

    private static void verifyWire() {
        var fixture = wireFixture();
        var source = fixture.data;
        var original = source.array().clone();
        var lod = DistantPolylineLod.build(source,source.remaining()/128);
        expect(lod.grids.length > 0,"Curved wire needs coarse levels");
        expect(lod.byteSize() < source.remaining(),"Wire coarse storage budget");
        expect(Arrays.equals(original,source.array()),"Wire source modified");
        int previous = source.remaining()/128;
        for (int level = 0; level < lod.grids.length; level++) {
            var vertices = lod.vertices[level];
            expect(vertices.length/32 <= previous/2,"Wire levels must halve geometry");
            previous = vertices.length/32;
            for (int v = 0; v < vertices.length; v += 8) {
                expect(Math.abs(Float.intBitsToFloat(vertices[v])) == .025f,"Wire thickness changed");
                expect(vertices[v+7] == 73,"Wire material changed");
            }
            // 对照整条原线的每个截面，确认最大偏移不超过当前误差。
            for (int segment = 0; segment < vertices.length/128; segment++) {
                int offset = segment*128;
                float z0 = Float.intBitsToFloat(vertices[offset+2]), z1 = Float.intBitsToFloat(vertices[offset+26]);
                float y0 = Float.intBitsToFloat(vertices[offset+1]), y1 = Float.intBitsToFloat(vertices[offset+25]);
                for (int z = (int)z0; z <= z1; z++) {
                    float t = (z-z0)/(z1-z0);
                    float expected = (z-32)*(z-32)/256f+.025f;
                    expect(Math.abs(expected-(y0+(y1-y0)*t)) <= lod.grids[level]+1e-5,"Wire exceeded error budget");
                }
            }
        }
        System.out.println("Curved wire triangles: 512 -> " + lod.indexCount(0)/3 + " -> " + lod.indexCount(lod.grids.length-1)/3);
    }

    private static ByteBuffer transparentPair() {
        var out = ByteBuffer.allocate(256).order(ByteOrder.nativeOrder());
        for (int x = 0; x < 2; x++) {
            vertex(out,x,0,0,x*.5f,0,3);
            vertex(out,x+1,0,0,(x+1)*.5f,0,3);
            vertex(out,x+1,1,0,(x+1)*.5f,1,3);
            vertex(out,x,1,0,x*.5f,1,3);
        }
        for (int v = 0; v < 8; v++) out.put(v*32+27,(byte)96);
        return out.flip();
    }

    private static void verifyTransparentMerge() {
        var data = transparentPair();
        expect(DistantQuadMerger.compact(data,2)==1,"Coplanar transparent subdivision should merge");
        expect(data.getFloat(0)==0 && data.getFloat(32)==2,"Merged transparent extent");
        expect(data.getFloat(44)==1 && (data.get(27)&255)==96,"Transparent UV/alpha changed");
        var normal = new Vector3f(data.getFloat(32)-data.getFloat(0),0,0).cross(new Vector3f(0,1,0));
        expect(normal.z>0,"Transparent winding changed");
        for (int offset : new int[]{128+24,128+27,128+20,128+28}) {
            data = transparentPair();
            data.put(offset,(byte)(data.get(offset)+1));
            expect(DistantQuadMerger.compact(data,2)==2,"Different tint/alpha/light/material must not merge");
        }
        data = transparentPair();
        for (int v = 4; v < 8; v++) data.putFloat(v*32+8,.01f);
        expect(DistantQuadMerger.compact(data,2)==2,"Separate transparency layers must remain");
        data = transparentPair();
        data.putFloat(128+12,0);
        expect(DistantQuadMerger.compact(data,2)==2,"UV seam must remain");
    }

    private static void verify(Fixture fixture) {
        var data = fixture.data;
        byte[] original = data.array().clone();
        int quads = data.remaining() / 128;
        long start = System.nanoTime();
        var lod = DistantMeshLod.build(data,quads);
        long millis = (System.nanoTime() - start) / 1000000;
        expect(lod.vertices != null && lod.grids.length > 0, fixture.name + " needs real coarse meshes");
        expect(Arrays.equals(original,data.array()),"Original mesh changed");
        expect(lod.byteSize() < data.remaining(),"Extra coarse vertices must stay below original vertex bytes");
        float[] bounds = bounds(data);
        int previous = quads * 6;
        StringBuilder counts = new StringBuilder().append(quads * 2);
        for (int level = 0; level < lod.grids.length; level++) {
            int[] vertices = lod.vertices[level];
            int count = lod.indexCount(level);
            expect(count > 0 && count <= previous / 2,"Each coarse mesh must save at least half the triangles");
            previous = count;
            counts.append(" -> ").append(count / 3);
            for (int v = 0; v < vertices.length; v += 8) {
                for (int axis = 0; axis < 3; axis++) {
                    float p = Float.intBitsToFloat(vertices[v + axis]);
                    expect(Float.isFinite(p) && p >= bounds[axis] - 1e-5 && p <= bounds[axis + 3] + 1e-5,
                            "Coarse surface escaped original bounds");
                }
                expect(vertices[v + 7] == 73,"Material ID lost");
            }
            for (int q = 0; q < vertices.length; q += 32) {
                Vector3f a = position(vertices,q), b = position(vertices,q+8), c = position(vertices,q+16);
                var normal = b.sub(a).cross(c.sub(a));
                int face = vertices[q+5] >>> 24;
                float outward = switch (face) {
                    case 0 -> -normal.y; case 1 -> normal.y; case 2 -> -normal.z;
                    case 3 -> normal.z; case 4 -> -normal.x; default -> normal.x;
                };
                expect(outward > 0,"Collapsed or inward coarse face");
            }
        }
        var projection = new Matrix4f().perspective((float)Math.toRadians(70),16f/9,.1f,50000);
        var selection = new me.cortex.voxy.client.core.rendering.NativeLodSelection();
        selection.update(projection,projection,1920,1080,0,0,0,256,256);
        int level = selection.level(bounds[0],bounds[1],bounds[2]-512,bounds[3],bounds[4],bounds[5]-512);
        expect(level > 0 && (1 << level) >= lod.grids[0],fixture.name + " must coarsen at 512 blocks, Standard quality");
        System.out.println(fixture.name + ": triangles " + counts + "; bake " + millis + " ms; first grid " + lod.grids[0]);
    }

    static float[] bounds(ByteBuffer data) {
        float[] result = {Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        for (int v=0;v<data.limit();v+=32) for (int axis=0;axis<3;axis++) {
            float value=data.getFloat(v+axis*4);
            result[axis]=Math.min(result[axis],value); result[axis+3]=Math.max(result[axis+3],value);
        }
        return result;
    }

    private static ByteBuffer buffer() { return ByteBuffer.allocate(4*1024*1024).order(ByteOrder.nativeOrder()); }

    private static void box(ByteBuffer out,float x0,float y0,float z0,float x1,float y1,float z1) {
        for (int face=0;face<6;face++) for (int i=0;i<4;i++) {
            int bits=CORNERS[face][i];
            vertex(out,(bits&1)==0?x0:x1,(bits&2)==0?y0:y1,(bits&4)==0?z0:z1,
                    i==1||i==2?1:0,i>=2?1:0,face);
        }
    }

    private static void vertex(ByteBuffer out,float x,float y,float z,float u,float v,int face) {
        out.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
        out.putInt(0x00FF8878|(face<<24)).putInt(0xFF659CB4).putInt(73);
    }

    private static Vector3f position(int[] data,int offset) {
        return new Vector3f(Float.intBitsToFloat(data[offset]),Float.intBitsToFloat(data[offset+1]),Float.intBitsToFloat(data[offset+2]));
    }

    private static List<float[]> readObj(Path path) throws Exception {
        var positions=new ArrayList<float[]>(); var uv=new ArrayList<float[]>(); var quads=new ArrayList<float[]>();
        for (String line : Files.readAllLines(path)) {
            String[] parts=line.trim().split("\\s+");
            if (parts[0].equals("v")) positions.add(new float[]{Float.parseFloat(parts[1]),Float.parseFloat(parts[2]),Float.parseFloat(parts[3])});
            else if (parts[0].equals("vt")) uv.add(new float[]{Float.parseFloat(parts[1]),Float.parseFloat(parts[2])});
            else if (parts[0].equals("f")) {
                if (parts.length!=5) throw new AssertionError("Expected quad OBJ face: " + path);
                float[] quad=new float[20];
                for (int i=0;i<4;i++) {
                    String[] indices=parts[i+1].split("/");
                    System.arraycopy(positions.get(Integer.parseInt(indices[0])-1),0,quad,i*5,3);
                    System.arraycopy(uv.get(Integer.parseInt(indices[1])-1),0,quad,i*5+3,2);
                }
                quads.add(quad);
            }
        }
        return quads;
    }

    private static void appendObj(ByteBuffer out,List<float[]> quads,Matrix4f transform) {
        var p=new Vector3f();
        for (float[] quad : quads) for (int i=0;i<4;i++) {
            transform.transformPosition(p.set(quad[i*5],quad[i*5+1],quad[i*5+2]));
            vertex(out,p.x,p.y,p.z,quad[i*5+3],quad[i*5+4],1);
        }
    }

    private static void expect(boolean condition,String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
