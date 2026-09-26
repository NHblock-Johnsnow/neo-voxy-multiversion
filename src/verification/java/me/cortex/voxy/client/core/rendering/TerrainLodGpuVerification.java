package me.cortex.voxy.client.core.rendering;

import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.lwjgl.opengl.GL45C.*;

/** 直接执行地形投影和模型解码 shader，检查玻璃与流体共用的细分路径。 */
public final class TerrainLodGpuVerification {
    private static final Path SHADERS = Path.of("src/main/resources/assets/voxy/shaders");
    private static int checks;

    public static void run() throws IOException {
        verifyTraversal();
        verifyNativeSelection();
        verifyFluidHeights();
        for (boolean wide : new boolean[]{false,true}) {
            verifyQuadFormat(wide);
            verifyModels(wide);
        }
        System.out.println("Passed " + checks + " GPU terrain zoom/glass/fluid checks");
    }

    private static void verifyFluidHeights() throws IOException {
        String source="#version 460 core\nlayout(local_size_x=10) in;\n"
                +read("lod/block_model.glsl")+"""
                layout(binding=0,std430) buffer Results { float heights[]; };
                void main() {
                    uint height=gl_GlobalInvocationID.x;
                    BlockModel model;
                    model.flagsA=4096u|(height<<8u);
                    heights[height]=modelFluidHeight(model);
                }
                """;
        int program=program(source),buffer=glCreateBuffers();
        glNamedBufferData(buffer,40,GL_STREAM_READ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,buffer);
        glUseProgram(program); glDispatchCompute(1,1,1);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        float[] result=new float[10];
        glGetNamedBufferSubData(buffer,0,result);
        for (int h=0;h<=9;h++) expect(Math.abs(result[h]-h/9f)<1e-6,"流体标记不能污染高度");
        glDeleteProgram(program); glDeleteBuffers(buffer);
    }

    private static void verifyTraversal() throws IOException {
        String source = """
                #version 460 core
                #define NODE_DATA_BINDING 1
                #define HIZ_BINDING 0
                layout(local_size_x=1) in;
                layout(location=0) uniform mat4 MVP;
                layout(location=4) uniform float minSSS;
                layout(location=5) uniform float invP00;
                layout(location=6) uniform float invP11;
                layout(location=7) uniform float stretchMax;
                layout(binding=0,std430) buffer Results { vec4 result; };
                ivec3 camSecPos = ivec3(0);
                vec3 camSubSecPos = vec3(0);
                uint packedHizSize = 65537u;
                """ + read("lod/frustum.glsl") + "\nFrustum frustum;\n"
                + read("lod/hierarchical/node.glsl") + read("lod/hierarchical/screenspace.glsl") + """
                void main() {
                    for (int i=0;i<6;i++) frustum.planes[i]=vec4(0,0,0,1);
                    UnpackedNode node;
                    node.pos=ivec3(-1,-1,-32);
                    node.lodLevel=2u;
                    setupScreenspace(node);
                    result=vec4(_screenSize,float(shouldDecend()),MVP[0][0],MVP[1][1]);
                }
                """;
        int buffer=glCreateBuffers();
        glNamedBufferData(buffer,16,GL_STREAM_READ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,buffer);
        for (String defines : new String[]{"", "#define USE_REVERSE_Z\n", "#define USE_ZERO_ONE_DEPTH\n",
                "#define USE_REVERSE_Z\n#define USE_ZERO_ONE_DEPTH\n"}) {
            int program=program(source.replace("#version 460 core", "#version 460 core\n"+defines));
            glUseProgram(program);
            for (int width : new int[]{1920,3840}) {
                float previous=0;
                for (float fov : new float[]{70,30,7,3,1,70}) {
                    var m=new Matrix4f().perspective((float)Math.toRadians(fov),16f/9,.1f,50000);
                    glUniformMatrix4fv(0,false,m.get(new float[16]));
                    glUniform1f(4,256f*256/(width*(width*9/16)));
                    glUniform1f(5,1/m.m00()); glUniform1f(6,1/m.m11());
                    glUniform1f(7,(float)Math.pow(1+1/(m.m00()*m.m00())+1/(m.m11()*m.m11()),1.5));
                    glDispatchCompute(1,1,1);
                    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
                    float[] result=new float[4];
                    glGetNamedBufferSubData(buffer,0,result);
                    if (fov==70) expect(result[1]==0,"缩小后恢复粗级判断");
                    else {
                        expect(result[0]>previous,"Terrain projection did not grow: fov="+fov+", width="+width
                                +", area="+result[0]+", previous="+previous+", P00="+result[2]+"/"+m.m00()+", GL="+glGetError());
                        if (fov<=3) expect(result[1]==1,"放大后必须请求子节点");
                    }
                    previous=result[0];
                }
            }
            glDeleteProgram(program);
        }
        glDeleteBuffers(buffer);
    }

    private static String wideDefines(boolean wide) {
        return wide ? "#extension GL_ARB_gpu_shader_int64 : require\n#define QUAD_DATA_USE_64_BIT\n" : "";
    }

    private static void verifyNativeSelection() throws IOException {
        String source = """
                #version 460 core
                #define NODE_DATA_BINDING 1
                #define HIZ_BINDING 0
                layout(local_size_x=64) in;
                layout(location=0) uniform mat4 MVP;
                layout(location=4) uniform float minSSS;
                layout(location=5) uniform float invP00;
                layout(location=6) uniform float invP11;
                layout(location=7) uniform float stretchMax;
                layout(location=8) uniform float fullDetailDistSq;
                layout(binding=0,std430) buffer Results { uint results[]; };
                layout(binding=2,std430) readonly buffer Inputs { ivec4 inputs[]; };
                ivec3 camSecPos = ivec3(0);
                vec3 camSubSecPos = vec3(0);
                uint packedHizSize = 65537u;
                """ + read("lod/frustum.glsl") + "\nFrustum frustum;\n"
                + read("lod/hierarchical/node.glsl") + read("lod/hierarchical/screenspace.glsl") + """
                void main() {
                    for (int i=0;i<6;i++) frustum.planes[i]=vec4(0,0,0,1);
                    uint id=gl_GlobalInvocationID.x;
                    UnpackedNode node;
                    node.pos=inputs[id].xyz;
                    node.lodLevel=uint(inputs[id].w);
                    setupScreenspace(node);
                    vec3 p=vec3(node.pos)*(32<<node.lodLevel);
                    vec2 nearest=max(p.xz,max(vec2(0),-p.xz-float(32<<node.lodLevel)));
                    bool near=fullDetailDistSq>0 && dot(nearest,nearest)<=fullDetailDistSq;
                    results[id]=uint(near||shouldDecend());
                }
                """;
        int count=4096,program=program(source),input=glCreateBuffers(),output=glCreateBuffers();
        var random=new java.util.Random(925);
        int[] nodes=new int[count*4],result=new int[count];
        for (int i=0;i<count;i++) {
            nodes[i*4]=random.nextInt(33)-16;
            nodes[i*4+1]=random.nextInt(17)-8;
            nodes[i*4+2]=-2-random.nextInt(120);
            nodes[i*4+3]=1+random.nextInt(4);
        }
        glNamedBufferData(input,nodes,GL_STATIC_DRAW); glNamedBufferData(output,count*4L,GL_STREAM_READ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,output); glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,input);
        glUseProgram(program);
        var cpu=new NativeLodSelection();
        for (float fov : new float[]{110,70,30,7}) for (float quality : new float[]{28,123,256,1024}) {
            var projection=new Matrix4f().perspective((float)Math.toRadians(fov),16f/9,.1f,50000);
            cpu.update(projection,projection,1920,1080,0,0,0,quality,256);
            glUniformMatrix4fv(0,false,projection.get(new float[16]));
            glUniform1f(4,cpu.minScreenArea); glUniform1f(5,cpu.invP00); glUniform1f(6,cpu.invP11);
            glUniform1f(7,cpu.stretchMax); glUniform1f(8,cpu.fullDetailDistanceSquared);
            glDispatchCompute(count/64,1,1);
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
            glGetNamedBufferSubData(output,0,result);
            for (int i=0;i<count;i++) expect(result[i]==(cpu.shouldDescend(nodes[i*4],nodes[i*4+1],nodes[i*4+2],nodes[i*4+3])?1:0),
                    "Native GPU/CPU subdivision mismatch: node="+i+", fov="+fov+", quality="+quality);
        }
        glDeleteProgram(program); glDeleteBuffers(input); glDeleteBuffers(output);
        System.out.println("Native screenspace.glsl and CPU section selection agree on 65536 cases");
    }

    private static void verifyQuadFormat(boolean wide) throws IOException {
        String source = "#version 460 core\n" + wideDefines(wide) + """
                layout(local_size_x=64) in;
                layout(binding=0,std430) readonly buffer Input { uvec2 inputData[]; };
                layout(binding=1,std430) writeonly buffer Output { uvec4 outputData[]; };
                """ + read("lod/quad_format.glsl") + """
                void main() {
                    uint id=gl_GlobalInvocationID.x;
                    uvec2 words=inputData[id];
                    #ifdef QUAD_DATA_USE_64_BIT
                    Quad raw=uint64_t(words.x)|(uint64_t(words.y)<<32);
                    #else
                    Quad raw=ivec2(words);
                    #endif
                    outputData[id*3u]=uvec4(extractStateId(raw),extractFace(raw),extractBiomeId(raw),extractLightId(raw));
                    outputData[id*3u+1u]=uvec4(extractFluidLowerHeight(raw),extractFluidShapePayload(raw),quadUsesBlendPalette(raw),extractBlendIdx(raw));
                    outputData[id*3u+2u]=uvec4(extractPos(raw),uint(quadHasFluidShape(raw)));
                }
                """;
        int count=4096,program=program(source),input=glCreateBuffers(),output=glCreateBuffers();
        int[] data=new int[count*2];
        var random=new java.util.Random(715);
        for (int i=0;i<data.length;i++) data[i]=random.nextInt();
        glNamedBufferData(input,data,GL_STATIC_DRAW);
        glNamedBufferData(output,count*48L,GL_STREAM_READ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,input); glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,output);
        glUseProgram(program); glDispatchCompute(count/64,1,1);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        int[] result=new int[count*12];
        glGetNamedBufferSubData(output,0,result);
        for (int i=0;i<count;i++) {
            long raw=Integer.toUnsignedLong(data[i*2])|(Integer.toUnsignedLong(data[i*2+1])<<32);
            int palette=(int)(raw>>>63),aux=(int)(raw>>>42)&15;
            int[] expected={(int)(raw>>>26)&65535,(int)raw&7,(int)(raw>>>46)&511,(int)(raw>>>55)&255,
                    palette==0?aux:0,((int)(raw>>>3)&255)|(aux<<8),palette,((int)(raw>>>46)&511)|(aux<<9),
                    (int)(raw>>>21)&31,(int)(raw>>>16)&31,(int)(raw>>>11)&31,palette==0&&aux!=0?1:0};
            for (int j=0;j<12;j++) expect(result[i*12+j]==expected[j],"Quad format mismatch: wide="+wide+", field="+j);
        }
        glDeleteProgram(program); glDeleteBuffers(input); glDeleteBuffers(output);
    }

    private static void verifyModels(boolean wide) throws IOException {
        String source = """
                #version 460 core
                #define PATCHED_SHADER
                layout(local_size_x=1) in;
                mat4 MVP=mat4(1);
                ivec3 baseSectionPos=ivec3(0);
                vec3 cameraSubPos=vec3(0);
                vec2 worldCurveData=vec2(0);
                float fluidDatumY=0.75;
                """ + read("lod/block_model.glsl") + read("lod/quad_format.glsl") + """
                BlockModel modelData[1];
                uint colourData[1];
                struct Result { vec4 corners[4]; uvec4 attributes; };
                layout(binding=0,std430) buffer Results { Result results[]; };
                """ + read("lod/quad_util.glsl") + """
                void main() {
                    uint level=gl_GlobalInvocationID.x;
                    uint kind=gl_GlobalInvocationID.y;
                    uint face=gl_GlobalInvocationID.z;
                    BlockModel model;
                    for (int i=0;i<6;i++) model.faceData[i]=0xF0F0u;
                    model.flagsA=4u;
                    model.colourTint=0xDC462360u;
                    model.customId=73u;
                    if (kind!=0u) model.flagsA|=4096u|(8u<<8u);
                    if (kind==2u || kind==5u) model.flagsA|=64u;
                    if (kind==3u) model.flagsA|=16u;
                    modelData[0]=model;
                    uint shape=2u|(4u<<3u)|(6u<<6u)|(7u<<9u);
                    ivec2 words=ivec2(face,0);
                    if (kind!=0u && kind<4u && face!=0u) {
                        words.x|=int((shape&255u)<<3u);
                        words.y|=int((shape>>8u)<<10u);
                    }
                    #ifdef QUAD_DATA_USE_64_BIT
                    Quad raw=uint64_t(uint(words.x))|(uint64_t(uint(words.y))<<32);
                    #else
                    Quad raw=words;
                    #endif
                    QuadData quad;
                    setupQuad(quad,raw,uvec2(level<<28u,0),true);
                    uint id=(face*6u+kind)*5u+level;
                    for (uint i=0u;i<4u;i++) results[id].corners[i]=vec4(getQuadCornerPoint(quad,i),quad.lodScale);
                    results[id].attributes=uvec4(quad.attributeData.z,quad.fluidShape,quad.attributeData.x,quad.attributeData.w);
                }
                """;
        int program=program(source.replace("#version 460 core","#version 460 core\n"+wideDefines(wide))),buffer=glCreateBuffers();
        int bytes=6*6*5*80;
        glNamedBufferData(buffer,bytes,GL_STREAM_READ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,buffer);
        glUseProgram(program); glDispatchCompute(5,6,6);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        float[] result=new float[bytes/4];
        glGetNamedBufferSubData(buffer,0,result);
        double[] heights={3/9.0,5/9.0,8/9.0,1};
        for (int face=0;face<6;face++) for (int kind=0;kind<6;kind++) for (int level=4;level>=0;level--) {
            int base=((face*6+kind)*5+level)*20,scale=1<<level;
            int colour=Float.floatToRawIntBits(result[base+16]);
            expect(colour==0xDC462360,"Tint changed: face="+face+", kind="+kind+", level="+level
                    +", colour="+Integer.toHexString(colour)+", scale="+result[base+3]+", GL="+glGetError());
            int shape=Float.floatToRawIntBits(result[base+17]);
            expect(shape==(kind!=0 && kind<4 && face!=0 ? 1 : 0),"只有带四角数据的流体顶面和侧面使用斜面编码");
            for (int corner=0;corner<4;corner++) {
                expect(result[base+corner*4+3]==scale,"模型尺寸使用当前地形级别");
                if (kind!=0 && kind<4 && face!=0) {
                    double expected=kind==3 && face==1 && level>0 ? .75 : scale-1+heights[corner];
                    expect(Math.abs(result[base+corner*4+1]-expected)<2e-6,"放大恢复细级流体斜面，粗级水位不膨胀");
                } else if (kind>=4 && face!=0) {
                    double y=result[base+corner*4+1],top=scale-1+8/9.0;
                    expect(Math.abs(y-top)<2e-6 || (face!=1 && Math.abs(y)<2e-6),
                            "平顶流体与侧壁高度不能包含流体标记位");
                }
            }
        }
        glDeleteProgram(program); glDeleteBuffers(buffer);
        expect(glGetError()==GL_NO_ERROR,"地形模型验证无 GL 错误");
    }

    private static String read(String path) throws IOException {
        String source=Files.readString(SHADERS.resolve(path));
        var matcher=Pattern.compile("#import <voxy:([^>]+)>").matcher(source);
        var result=new StringBuilder();
        int end=0;
        while (matcher.find()) {
            result.append(source,end,matcher.start());
            result.append(read(matcher.group(1)));
            end=matcher.end();
        }
        result.append(source,end,source.length());
        return result.toString();
    }

    private static int program(String source) {
        int shader=glCreateShader(GL_COMPUTE_SHADER);
        // 测试不绑定 GPU 日志缓冲，仅去掉未调用的节点调试输出。
        glShaderSource(shader,source.replaceAll("printf\\([^\\n]*\\);", "")); glCompileShader(shader);
        if (glGetShaderi(shader,GL_COMPILE_STATUS)==0) throw new AssertionError(glGetShaderInfoLog(shader));
        int program=glCreateProgram(); glAttachShader(program,shader); glLinkProgram(program);
        if (glGetProgrami(program,GL_LINK_STATUS)==0) throw new AssertionError(glGetProgramInfoLog(program));
        glDeleteShader(shader);
        return program;
    }

    private static void expect(boolean condition,String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
