package me.cortex.voxy.client.compat.create;

import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;

/** 在隐藏窗口中运行实际顶点 shader、索引级别和列车深度回放坐标。 */
public final class DistantMeshGpuVerification {
    public static void main(String[] args) throws Exception {
        if (!glfwInit()) throw new AssertionError("GLFW unavailable");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(32,32,"mesh-lod-verification",0,0);
        if (window == 0) throw new AssertionError("OpenGL 4.6 unavailable");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        verifyShaderVariants();
        verifyZoomDraws();
        verifyVolumetricDraws();
        me.cortex.voxy.client.core.rendering.TerrainLodGpuVerification.run();
        String vertex = Files.readString(Path.of("src/main/resources/assets/voxy/shaders/compat/distant.vert"))
                .replace("#version 460 core", "#version 460 core\n#define TRAIN_DEPTH_REPLAY")
                + "\nvec2 distantTaaShift() { return vec2(0.015625, -0.015625); }";
        int shader = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(shader,vertex);
        glCompileShader(shader);
        if (glGetShaderi(shader,GL_COMPILE_STATUS)==0) throw new AssertionError(glGetShaderInfoLog(shader));
        int program = glCreateProgram();
        glAttachShader(program,shader);
        glTransformFeedbackVaryings(program,new String[]{"gl_Position","fLodClip"},GL_INTERLEAVED_ATTRIBS);
        glLinkProgram(program);
        if (glGetProgrami(program,GL_LINK_STATUS)==0) throw new AssertionError(glGetProgramInfoLog(program));
        glUseProgram(program);
        float[] identity = {1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
        glUniformMatrix4fv(0,false,identity);
        glUniformMatrix4fv(8,false,identity);
        var cpu = DistantMeshLodVerification.tiledPlane(64);
        var data = MemoryUtil.memAlloc(cpu.remaining());
        data.put(cpu).flip();
        var lod = DistantMeshLod.build(data,4096);
        var mesh = new DistantMesh(data,4096,lod);
        MemoryUtil.memFree(data);
        // 扩容后旧 VAO 仍须正确读取同一共享索引对象。
        var largerCpu = DistantMeshLodVerification.tiledPlane(65);
        var largerData = MemoryUtil.memAlloc(largerCpu.remaining());
        largerData.put(largerCpu).flip();
        var larger = new DistantMesh(largerData,65*65,DistantMeshLod.EMPTY);
        MemoryUtil.memFree(largerData);
        larger.free();
        int output = glCreateBuffers();
        glNamedBufferData(output,4096L*6*8*4,GL_STREAM_READ);
        glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER,0,output);
        glEnable(GL_RASTERIZER_DISCARD);
        int query = glGenQueries();
        int checks = 0;
        for (int level=0; level<=lod.grids.length; level++) {
            for (float blend : new float[]{0,.5f,1}) {
                if (level==lod.grids.length && blend!=0) continue;
                glBeginQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN,query);
                glBeginTransformFeedback(GL_TRIANGLES);
                mesh.draw(level,blend);
                glEndTransformFeedback();
                glEndQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN);
                int[] indices = level==0 ? fullIndices(4096) : lod.indices[level-1];
                if (glGetQueryObjecti(query,GL_QUERY_RESULT)!=indices.length/3) throw new AssertionError("Wrong triangle count");
                checks++;
                float[] result = new float[indices.length*8];
                glGetNamedBufferSubData(output,0,result);
                for (int i=0;i<indices.length;i++) {
                    int id = indices[i], quad=id/4, corner=id%4;
                    float x = (quad%64+(corner==1||corner==2?1:0))/64f;
                    float y = (quad/64+(corner>=2?1:0))/64f;
                    float grid = level==0 ? 0 : lod.grids[level-1];
                    float next = level<lod.grids.length ? lod.grids[level] : grid;
                    float px = grid==0?x:DistantMeshLod.snap(x,grid), py=grid==0?y:DistantMeshLod.snap(y,grid);
                    if (blend>0) {
                        px += (DistantMeshLod.snap(x,next)-px)*blend;
                        py += (DistantMeshLod.snap(y,next)-py)*blend;
                    }
                    check(result[i*8],px); check(result[i*8+1],py);
                    check(result[i*8+4],px+.015625f); check(result[i*8+5],py-.015625f);
                    checks+=4;
                }
            }
        }
        if (glGetError()!=GL_NO_ERROR) throw new AssertionError("OpenGL error");
        mesh.free();
        glDeleteQueries(query); glDeleteBuffers(output); glDeleteProgram(program); glDeleteShader(shader);
        System.out.println("Passed " + checks + " GPU mesh/index/morph/depth-coordinate checks; " + glGetString(GL_RENDERER));
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private static void verifyZoomDraws() throws Exception {
        String vertex = Files.readString(Path.of("src/main/resources/assets/voxy/shaders/compat/distant.vert"));
        int v = compile(GL_VERTEX_SHADER,vertex), program = glCreateProgram();
        glAttachShader(program,v);
        glTransformFeedbackVaryings(program,new String[]{"gl_Position","fColor","fCustomId"},GL_INTERLEAVED_ATTRIBS);
        glLinkProgram(program);
        if (glGetProgrami(program,GL_LINK_STATUS)==0) throw new AssertionError(glGetProgramInfoLog(program));
        glUseProgram(program);
        var data = MemoryUtil.memAlloc(4096*4*32);
        data.put(DistantMeshLodVerification.tiledPlane(64)).flip();
        for (int i=0;i<4096*4;i++) {
            data.put(i*32+24,(byte) 220).put(i*32+25,(byte) 70).put(i*32+26,(byte) 35).put(i*32+27,(byte) 96);
            data.putInt(i*32+28,73);
        }
        var mesh = new DistantMesh(data,4096,DistantMeshLod.build(data,4096));
        mesh.maxX=mesh.maxY=1;
        MemoryUtil.memFree(data);
        int query=glGenQueries(), output=glCreateBuffers();
        glNamedBufferData(output,4096L*6*9*4,GL_STREAM_READ);
        glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER,0,output);
        glEnable(GL_RASTERIZER_DISCARD);
        float[] matrix=new float[16];
        int checks=0;
        var selection = new me.cortex.voxy.client.core.rendering.NativeLodSelection();
        for (int width : new int[]{1920,3840}) {
            long previous=0;
            for (float fov : new float[]{70,30,7,3,1,.5f,.1f,.005f}) {
                var projection=new org.joml.Matrix4f().perspective((float)Math.toRadians(fov),16f/9,.1f,50000);
                var m=new org.joml.Matrix4f(projection).translate(-.5f,-.5f,-9000);
                selection.update(projection,projection,width,width*9/16,0,0,0,256,64);
                glUniformMatrix4fv(0,false,m.get(matrix));
                DistantMesh.beginFrame(256,64);
                glBeginQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN,query);
                glBeginTransformFeedback(GL_TRIANGLES);
                mesh.drawAt(selection,-.5,-.5,-9000);
                glEndTransformFeedback(); glEndQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN);
                int triangles=glGetQueryObjecti(query,GL_QUERY_RESULT);
                if (triangles<previous || triangles!=DistantMesh.submittedTriangles) throw new AssertionError("Zoom lost detail");
                if (fov==70 && triangles>=8192) throw new AssertionError("Far mesh was not simplified");
                if (fov==.005f && (triangles!=8192 || mesh.lastDrawBlend()!=0)) throw new AssertionError("Zoom did not restore full mesh");
                previous=triangles;
                checks++;
            }
        }
        // 透明网格缩小时剔除、放大时恢复原始索引顺序与逐顶点颜色/透明度。
        for (float fov : new float[]{70,7,70,7}) {
            var m=new org.joml.Matrix4f().perspective((float)Math.toRadians(fov),16f/9,.1f,50000)
                    .translate(-.5f,-.5f,-4096);
            glUniformMatrix4fv(0,false,m.get(matrix));
            DistantMesh.beginFrame(256,64);
            glBeginQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN,query);
            glBeginTransformFeedback(GL_TRIANGLES);
            mesh.drawTranslucent(m,1920,1080);
            glEndTransformFeedback(); glEndQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN);
            int triangles=glGetQueryObjecti(query,GL_QUERY_RESULT);
            if (triangles!=(fov==70?0:8192)) throw new AssertionError("Transparent zoom visibility");
            checks++;
            if (triangles==0) continue;
            float[] result=new float[triangles*3*9];
            glGetNamedBufferSubData(output,0,result);
            int[] indices=fullIndices(4096);
            for (int i=0;i<indices.length;i++) {
                int id=indices[i],quad=id/4,corner=id%4;
                float x=(quad%64+(corner==1||corner==2?1:0))/64f;
                float y=(quad/64+(corner>=2?1:0))/64f;
                var expected=m.transform(new org.joml.Vector4f(x,y,0,1));
                check(result[i*9],expected.x); check(result[i*9+1],expected.y);
                check(result[i*9+4],220/255f); check(result[i*9+5],70/255f);
                check(result[i*9+6],35/255f); check(result[i*9+7],96/255f);
                if (Float.floatToRawIntBits(result[i*9+8])!=73) throw new AssertionError("Material ID changed");
                checks+=7;
            }
        }
        mesh.free(); glDeleteQueries(query); glDeleteBuffers(output); glDeleteProgram(program); glDeleteShader(v);
        glDisable(GL_RASTERIZER_DISCARD);
        System.out.println("Passed " + checks + " GPU zoom/refinement/transparent-attribute checks");
    }

    private static int[] fullIndices(int quads) {
        int[] result=new int[quads*6];
        for(int i=0;i<quads;i++) {
            int p=i*6,v=i*4;
            result[p]=v;result[p+1]=v+1;result[p+2]=v+2;
            result[p+3]=v+2;result[p+4]=v+3;result[p+5]=v;
        }
        return result;
    }

    private static void verifyVolumetricDraws() throws Exception {
        String vertex = Files.readString(Path.of("src/main/resources/assets/voxy/shaders/compat/distant.vert"))
                .replace("#version 460 core", "#version 460 core\n#define TRAIN_DEPTH_REPLAY")
                + "\nvec2 distantTaaShift() { return vec2(0.015625, -0.015625); }";
        int shader = compile(GL_VERTEX_SHADER,vertex), program = glCreateProgram();
        glAttachShader(program,shader);
        glTransformFeedbackVaryings(program,new String[]{"gl_Position","fLodClip","fColor","fCustomId"},GL_INTERLEAVED_ATTRIBS);
        glLinkProgram(program);
        if (glGetProgrami(program,GL_LINK_STATUS)==0) throw new AssertionError(glGetProgramInfoLog(program));
        glUseProgram(program);
        glEnable(GL_RASTERIZER_DISCARD);
        int output = glCreateBuffers(), query = glGenQueries(), checks = 0;
        var fixtures = DistantMeshCoarseningVerification.fixtures();
        fixtures.add(DistantMeshCoarseningVerification.wireFixture());
        float[] matrix = new float[16];
        for (var fixture : fixtures) {
            var original = fixture.data();
            int quads = original.remaining()/128;
            var lod = fixture.name().contains("wire") ? DistantPolylineLod.build(original,quads) : DistantMeshLod.build(original,quads);
            var data = MemoryUtil.memAlloc(original.remaining());
            data.put(original.duplicate()).flip();
            var mesh = new DistantMesh(data,quads,lod);
            MemoryUtil.memFree(data);
            var bounds = DistantMeshCoarseningVerification.bounds(original);
            mesh.minX=bounds[0]; mesh.minY=bounds[1]; mesh.minZ=bounds[2];
            mesh.maxX=bounds[3]; mesh.maxY=bounds[4]; mesh.maxZ=bounds[5];
            glNamedBufferData(output,quads*6L*13*4,GL_STREAM_READ);
            glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER,0,output);
            var nativeLod = new me.cortex.voxy.client.core.rendering.NativeLodSelection();
            int previousLevel = 0;
            for (float quality : new float[]{28,64,123,256,512,768,1024}) {
                var projection = new org.joml.Matrix4f().perspective((float)Math.toRadians(70),16f/9,.1f,50000);
                var transform = new org.joml.Matrix4f(projection).translate(0,0,-512);
                nativeLod.update(projection,projection,1920,1080,0,0,0,quality,256);
                glUniformMatrix4fv(0,false,transform.get(matrix)); glUniformMatrix4fv(8,false,transform.get(matrix));
                mesh.drawAt(nativeLod,0,0,-512);
                int farLevel = mesh.lastDrawLevel();
                if (farLevel<previousLevel) throw new AssertionError("Lower quality refined dedicated mesh");
                if (quality==256 && farLevel==0) throw new AssertionError("Standard should coarsen "+fixture.name());
                previousLevel = farLevel;
                mesh.drawAt(nativeLod,0,0,-128);
                if (mesh.lastDrawLevel()!=0) throw new AssertionError("Handoff area must keep full detail");
                mesh.draw(farLevel,0);
                if (mesh.lastDrawLevel()!=farLevel) throw new AssertionError("Shared instance replay level changed");
                checks+=4;
            }
            for (int width : new int[]{1920,3840}) {
                int previousTriangles = 0;
                for (float fov : new float[]{70,30,7,1,.05f}) {
                    var projection = new org.joml.Matrix4f().perspective((float)Math.toRadians(fov),16f/9,.1f,50000);
                    var local = new org.joml.Matrix4f().translate(0,0,-512);
                    var transform = new org.joml.Matrix4f(projection).mul(local);
                    nativeLod.update(projection,projection,width,width*9/16,0,0,0,256,256);
                    glUniformMatrix4fv(0,false,transform.get(matrix)); glUniformMatrix4fv(8,false,transform.get(matrix));
                    int terrainLevel = nativeLod.level(bounds[0],bounds[1],bounds[2]-512,bounds[3],bounds[4],bounds[5]-512);
                    int expectedLevel = 0;
                    while (terrainLevel>0 && expectedLevel<lod.grids.length && lod.grids[expectedLevel]<=(1<<terrainLevel)) expectedLevel++;
                    for (boolean moving : new boolean[]{false,true}) {
                        DistantMesh.beginFrame(256,256);
                        glBeginQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN,query);
                        glBeginTransformFeedback(GL_TRIANGLES);
                        if (moving) mesh.drawModel(nativeLod,local,0,0,0); else mesh.drawAt(nativeLod,0,0,-512);
                        glEndTransformFeedback(); glEndQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN);
                        int triangles = glGetQueryObjecti(query,GL_QUERY_RESULT);
                        if (triangles!=DistantMesh.submittedTriangles) throw new AssertionError("Native triangle counter mismatch");
                        int expectedTriangles = expectedLevel==0?quads*2:lod.indexCount(expectedLevel-1)/3;
                        if (triangles!=expectedTriangles || mesh.lastDrawLevel()!=expectedLevel) throw new AssertionError("Native grid draw mismatch: "+fixture.name());
                        if (triangles<previousTriangles) throw new AssertionError("Native zoom lost detail");
                        if (fov==.05f && triangles!=quads*2) throw new AssertionError("Native zoom failed to restore original");
                        previousTriangles = triangles;
                        checks+=3;
                    }
                    // 同一帧换实例或深度回放不能污染静态网格的缓存级别。
                    mesh.drawAt(nativeLod,0,0,-128);
                    if (mesh.lastDrawLevel()!=0) throw new AssertionError("Native handoff lost detail");
                    mesh.drawAt(nativeLod,0,0,-512);
                    if (mesh.lastDrawLevel()!=expectedLevel) throw new AssertionError("Native instance cache lost level");
                    int evaluations = nativeLod.evaluations;
                    mesh.draw(0,0);
                    mesh.drawAt(nativeLod,0,0,-512);
                    if (mesh.lastDrawLevel()!=expectedLevel || nativeLod.evaluations!=evaluations) throw new AssertionError("Native cached replay mismatch");
                    checks+=3;
                }
            }
            var identity = new org.joml.Matrix4f();
            glUniformMatrix4fv(0,false,identity.get(matrix)); glUniformMatrix4fv(8,false,identity.get(matrix));
            // 往返每个 VBO 偏移，并读取真正提交给颜色/深度 shader 的顶点。
            for (int pass = 0; pass < 2; pass++) for (int step = 0; step <= lod.grids.length; step++) {
                int level = pass==0 ? step : lod.grids.length-step;
                glBeginQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN,query);
                glBeginTransformFeedback(GL_TRIANGLES);
                mesh.draw(level,.5f);
                glEndTransformFeedback(); glEndQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN);
                int count = level==0 ? quads*6 : lod.indexCount(level-1);
                if (glGetQueryObjecti(query,GL_QUERY_RESULT)!=count/3) throw new AssertionError("Coarse VBO count mismatch");
                float[] result = new float[count*13];
                glGetNamedBufferSubData(output,0,result);
                int[] indices = fullIndices(count/6);
                for (int i = 0; i < count; i++) {
                    int v = indices[i];
                    for (int axis = 0; axis < 3; axis++) {
                        float expected = level==0 ? original.getFloat(v*32+axis*4) : Float.intBitsToFloat(lod.vertices[level-1][v*8+axis]);
                        check(result[i*13+axis],expected);
                        check(result[i*13+4+axis],expected+(axis==0?.015625f:axis==1?-.015625f:0));
                        checks+=2;
                    }
                    int color = level==0 ? original.getInt(v*32+24) : lod.vertices[level-1][v*8+6];
                    for (int c = 0; c < 4; c++) check(result[i*13+8+c],((color>>>(c*8))&255)/255f);
                    int material = level==0 ? original.getInt(v*32+28) : lod.vertices[level-1][v*8+7];
                    if (Float.floatToRawIntBits(result[i*13+12])!=material) throw new AssertionError("Coarse material mismatch");
                    checks+=5;
                }
            }
            mesh.free();
            System.out.println("GPU coarse/full/zoom/depth replay: "+fixture.name());
        }
        if (glGetError()!=GL_NO_ERROR) throw new AssertionError("Volumetric OpenGL error");
        glDeleteQueries(query); glDeleteBuffers(output); glDeleteProgram(program); glDeleteShader(shader);
        glDisable(GL_RASTERIZER_DISCARD);
        System.out.println("Passed "+checks+" GPU volumetric/wire VBO/attribute/depth checks");
    }

    private static void verifyShaderVariants() throws Exception {
        String base = "src/main/resources/assets/voxy/shaders/compat/";
        String vertex = Files.readString(Path.of(base + "distant.vert"));
        String fragment = Files.readString(Path.of(base + "distant.frag"));
        String stub = "\nlayout(location=0) out vec4 resultColour;\n"
                + "void voxy_emitFragment(VoxyFragmentParameters p) { resultColour = p.sampledColour; }";
        for (String defines : new String[]{"", "UNIFORM_LIGHT", "TRANSLUCENT", "COPYCAT_OCCLUSION",
                "COPYCAT_OCCLUSION TRANSLUCENT", "PATCHED_SHADER UNIFORM_LIGHT",
                "PATCHED_SHADER TRANSLUCENT", "PATCHED_SHADER COPYCAT_OCCLUSION TRANSLUCENT",
                "TRAIN_DEPTH_REPLAY", "PATCHED_SHADER LASER TRANSLUCENT"}) {
            String header = "#version 460 core\n";
            for (String define : defines.split(" ")) if (!define.isEmpty()) header += "#define " + define + "\n";
            String vs = vertex.replace("#version 460 core", header)
                    + "\nvec2 distantTaaShift() { return vec2(0.0); }";
            String fs = defines.contains("TRAIN_DEPTH_REPLAY") ? Files.readString(Path.of(base + "distant_depth.frag"))
                    : defines.contains("LASER") ? Files.readString(Path.of(base + "distant_laser.frag")) : fragment;
            fs = fs.replace("#version 460 core", header);
            if (defines.contains("PATCHED_SHADER")) fs += stub;
            int v = compile(GL_VERTEX_SHADER, vs), f = compile(GL_FRAGMENT_SHADER, fs);
            int p = glCreateProgram();
            glAttachShader(p,v); glAttachShader(p,f); glLinkProgram(p);
            if (glGetProgrami(p,GL_LINK_STATUS)==0) throw new AssertionError(defines + ": " + glGetProgramInfoLog(p));
            glDeleteProgram(p); glDeleteShader(v); glDeleteShader(f);
        }
        System.out.println("Linked 10 distant shader variants (stub shader-pack output)");
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source); glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS)==0) throw new AssertionError(glGetShaderInfoLog(shader));
        return shader;
    }

    private static void check(float actual,float expected) {
        if (Math.abs(actual-expected)>1e-6) throw new AssertionError(actual + " != " + expected);
    }
}
