#version 460 core

//Distant contraption/track mesh: fully self-contained vertex path, no vanilla vertex formats or
//RenderTypes involved anywhere in the chain.

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec2 aLightUv;
layout(location = 3) in float aShade;
layout(location = 4) in uint aFace;
//Tint (biome grass/foliage colour etc.), white for untinted geometry
layout(location = 5) in vec4 aColor;
layout(location = 6) in uint aCustomId;

//Full transform: (pipeline MVP or vanilla proj*view) * model
layout(location = 0) uniform mat4 uTransform;
layout(location = 6) uniform vec3 uMeshLod;
#ifdef UNIFORM_LIGHT
//Moving meshes bake without light; one light level per draw
layout(location = 4) uniform vec2 uLightUv;
//Baked faces are assembly-local; a rotated carriage/bogey needs them re-aimed or shader packs
//light (and specular-flare) the wrong side. Quarter turns about +Y, nearest-90 of the model yaw.
layout(location = 5) uniform uint uFaceRotY;
//+90 deg about +Y: NORTH->WEST, SOUTH->EAST, WEST->SOUTH, EAST->NORTH (indexed by face-2)
const uint FACE_ROT_Y[4] = uint[4](4u, 5u, 3u, 2u);
#endif

layout(location = 0) out vec2 fUv;
layout(location = 1) out vec2 fLightUv;
layout(location = 2) out float fShade;
layout(location = 3) flat out uint fFace;
layout(location = 4) out vec4 fColor;
layout(location = 5) flat out uint fCustomId;

#ifdef TRAIN_DEPTH_REPLAY
layout(location = 8) uniform mat4 uLodTransform;
layout(location = 6) out vec4 fLodClip;
#endif

#if defined(PATCHED_SHADER) || defined(TRAIN_DEPTH_REPLAY)
vec2 distantTaaShift();
#endif

void main() {
    vec3 position = aPos;
    if (uMeshLod.x > 0.0) position = floor(aPos / uMeshLod.x) * uMeshLod.x;
    if (uMeshLod.z > 0.0) {
        vec3 coarse = floor(aPos / uMeshLod.y) * uMeshLod.y;
        position = mix(position, coarse, uMeshLod.z);
    }
    gl_Position = uTransform * vec4(position, 1.0);
    #ifdef PATCHED_SHADER
    gl_Position.xy += distantTaaShift() * gl_Position.w;
    #endif
    #ifdef TRAIN_DEPTH_REPLAY
    // 回写沿用原版投影，LOD 遮挡采样则匹配光影抖动。
    fLodClip = uLodTransform * vec4(position, 1.0);
    fLodClip.xy += distantTaaShift() * fLodClip.w;
    #endif
    fUv = aUv;
    fColor = aColor;
    fCustomId = aCustomId;
    #ifdef UNIFORM_LIGHT
    fLightUv = uLightUv;
    uint face = aFace;
    float shade = aShade;
    for (uint i = 0u; i < uFaceRotY; i++) {
        if (face >= 2u) {
            face = FACE_ROT_Y[face - 2u];
        }
    }
    if (uFaceRotY != 0u && face >= 2u) {
        //Horizontal shade follows the re-aimed face (vanilla: N/S 0.8, W/E 0.6)
        shade = face <= 3u ? 0.8 : 0.6;
    }
    fShade = shade;
    fFace = face;
    #else
    fLightUv = aLightUv;
    fShade = aShade;
    fFace = aFace;
    #endif
}
