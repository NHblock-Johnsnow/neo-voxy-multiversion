#version 450 core

layout(binding = 0) uniform sampler2D depthTex;
layout(location = 1) uniform vec2 scaleFactor;
// inverse(vanillaProjection * modelView) and Voxy's MVP. Vanilla-owned
// pixels keep the baseline's real reprojected depth protocol.
layout(location = 2) uniform mat4 invSrcProjection;
layout(location = 3) uniform mat4 dstProjection;
// xy: destination NDC -> window depth; zw: source window -> NDC depth.
layout(location = 4) uniform vec4 depthRemap;
layout(location = 6) uniform float lodBoundaryFadeStart;
layout(location = 7) uniform float lodBoundaryFadeEnd;
layout(location = 8) uniform ivec3 cameraBlockOrigin;
layout(location = 9) uniform vec3 cameraFraction;
layout(location = 10) uniform int boundaryGuardPass;
layout(location = 11) uniform mat4 clipToSubLevel;
layout(location = 15) uniform vec3 subLevelHalfExtent;

#import <voxy:util/depthutils.glsl>

in vec2 UV;

float stableWorldDither(vec3 cameraRelativePosition) {
    // An eighth-block 3-D grid works on horizontal and vertical faces alike. Integer hashing avoids
    // trigonometry and, unlike screen-space Bayer noise, remains attached to the same world surface
    // while the camera moves.
    ivec3 cell = cameraBlockOrigin * 8
            + ivec3(floor((cameraRelativePosition + cameraFraction) * 8.0));
    uint hash = uint(cell.x) * 0x8da6b343u;
    hash ^= uint(cell.y) * 0xd8163841u;
    hash ^= uint(cell.z) * 0xcb1ab31fu;
    hash ^= hash >> 16u;
    hash *= 0x7feb352du;
    hash ^= hash >> 15u;
    return (float(hash & 1023u) + 0.5) * (1.0 / 1024.0);
}

float projectDepth(vec3 cameraRelativePosition) {
    vec4 clip = dstProjection * vec4(cameraRelativePosition, 1.0);
    float depth = (clip.z / clip.w) * depthRemap.x + depthRemap.y;
    return clamp(depth, 0.0, 1.0 - (2.0 / ((1 << 24) - 1)));
}

void main() {
    float sourceDepth = texture(depthTex, UV * scaleFactor).r;
    // Shader packs can leave sky depth very close to FAR without writing the
    // exact sentinel. Preserve the baseline tolerance to avoid masking sky.
    if (boundaryGuardPass == 2 ? sourceDepth == FAR : abs(sourceDepth - FAR) < 1e-5) {
        discard;
    }

    vec4 sourceClip = vec4(UV * 2.0 - 1.0,
            sourceDepth * depthRemap.z + depthRemap.w, 1.0);
    vec4 cameraRelative = invSrcProjection * sourceClip;

    if (boundaryGuardPass == 2) {
        // 只保留船体范围内已有的原版深度，不把空洞或后方地形误当成船体。
        vec4 local = clipToSubLevel * sourceClip;
        // 同时容纳 D24 量化和 float 矩阵点积误差；齐次比较避免远处除以极小 w。
        vec4 error = abs(clipToSubLevel[2]) * (depthRemap.z / 16777215.0)
                + (abs(clipToSubLevel[0] * sourceClip.x)
                 + abs(clipToSubLevel[1] * sourceClip.y)
                 + abs(clipToSubLevel[2] * sourceClip.z)
                 + abs(clipToSubLevel[3])) * (4.0 * 1.1920929e-7);
        if (any(greaterThan(abs(local.xyz),
                subLevelHalfExtent * local.w + error.xyz + subLevelHalfExtent * error.w))) {
            discard;
        }
        gl_FragDepth = projectDepth(cameraRelative.xyz / cameraRelative.w);
        return;
    }
    cameraRelative.xyz /= cameraRelative.w;

    float boundaryDistance = length(cameraRelative.xyz);
    float lodCoverage = 0.0;
    float ditherValue = 1.0;
    bool fadeEnabled = lodBoundaryFadeEnd > lodBoundaryFadeStart;
    if (fadeEnabled
            && boundaryDistance > lodBoundaryFadeStart
            && boundaryDistance < lodBoundaryFadeEnd) {
        lodCoverage = smoothstep(lodBoundaryFadeStart,
                lodBoundaryFadeEnd, boundaryDistance);
        // Delay most of the texture handoff until the outer half without introducing a second hard
        // radius. This reduces early simplified textures on stairs and glass while preserving coverage.
        lodCoverage *= lodCoverage;
        ditherValue = stableWorldDither(cameraRelative.xyz);
    } else if (fadeEnabled && boundaryDistance >= lodBoundaryFadeEnd) {
        lodCoverage = 1.0;
    }

    if (boundaryGuardPass != 0) {
        if (!fadeEnabled
                || boundaryDistance <= lodBoundaryFadeStart
                || boundaryDistance >= lodBoundaryFadeEnd
                || ditherValue >= lodCoverage) {
            discard;
        }

        float rayLength = max(length(cameraRelative.xyz), 1.0);
        vec3 guardedPosition = cameraRelative.xyz
                * (1.0 + min(2.0 / rayLength, 0.125));
        gl_FragDepth = projectDepth(guardedPosition);
        return;
    }

    if (fadeEnabled
            && (boundaryDistance >= lodBoundaryFadeEnd
                || (boundaryDistance > lodBoundaryFadeStart
                    && ditherValue < lodCoverage))) {
        // Leave the cleared stencil/depth in place: LOD owns this pixel.
        discard;
    }

    // Vanilla-owned pixels retain their real reprojected surface depth. This
    // is required by the compatibility baseline's occlusion and hook passes.
    gl_FragDepth = projectDepth(cameraRelative.xyz);
}
