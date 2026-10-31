#import <voxy:lod/pos_util.glsl>
#import <voxy:lod/lighting.glsl>
//Common utility functions for decoding and operating on quads

vec3 swizzelDataAxis(uint axis, vec3 data) {
    return mix(mix(data.zxy,data.xzy,bvec3(axis==0)),data,bvec3(axis==1));
}

vec4 getFaceSize(uint faceData) {
    float EPSILON = 0.00005f;

    vec4 faceOffsetsSizes = extractFaceSizes(faceData);

    //Expand the quads by a very small amount (because of the subtraction after this also becomes an implicit add)
    faceOffsetsSizes.xz -= vec2(EPSILON);

    //Make the end relative to the start
    faceOffsetsSizes.yw -= faceOffsetsSizes.xz;

    return faceOffsetsSizes;
}


vec2 taaOffset = vec2(0);//TODO: compute this

struct QuadData {
    uvec4 attributeData;

    float lodScale;
    uint axis;
    //Used for computing the 4 corners of the quad
    vec3 basePoint;
    vec2 quadSizeAddin;
    vec2 uvCorner;
    vec4 fluidCornerHeights;
    float fluidBaseY;
    uint fluidShape;
};

uint makeQuadFlags(uint faceData, uint modelId, ivec2 quadSize, const in BlockModel model, uint face) {
    //bit: 0-use cuttout, 1-dont use mipmaps, 2|3-tint state, 4|6-face, 8|11-width, 12|15-height, 16|31-model id
    uint flags = 0;

    flags |= modelId<<16;//Model id
    flags |= (uint(quadSize.x-1)<<8)|(uint(quadSize.y-1)<<12);//quad size

    {//Cuttout
        flags |= faceHasAlphaCuttout(faceData);
        flags |= uint(any(greaterThan(quadSize, ivec2(1)))) & faceHasAlphaCuttoutOverride(faceData);
    }

    flags |= modelUsesBalancedLeafCutout(model) ? 2u : 0u;

    //TODO: remove, there is no non mip code path anymore
    //flags |= uint(!modelHasMipmaps(model))<<1;//Not mipmaps

    flags |= faceTintState(faceData)<<2;
    flags |= face<<4;//Face

    return flags;
}

uint makeBalancedLeafSeed(const in Quad quad, ivec3 lodPos, uint lodLevel, uint face) {
    uvec3 worldPos = (uvec3(lodPos) << lodLevel) * 32u
            + (uvec3(extractPos(quad)) << lodLevel);
    uint hash = worldPos.x * 0x8da6b343u;
    hash ^= worldPos.y * 0xd8163841u;
    hash ^= worldPos.z * 0xcb1ab31fu;
    hash ^= face * 0x165667b1u;
    hash ^= lodLevel * 0x9e3779b9u;
    hash ^= hash >> 16u;
    hash *= 0x7feb352du;
    hash ^= hash >> 15u;
    return hash & 0xFFFFu;
}

uint packVec4(vec4 vec) {
    uvec4 vec_=uvec4(vec*255)<<uvec4(24,16,8,0);
    return vec_.x|vec_.y|vec_.z|vec_.w;
}


#ifndef PATCHED_SHADER
float computeDirectionalFaceTint(bool isShaded, uint face);
#endif

uvec3 makeRemainingAttributes(const in BlockModel model, const in Quad quad, uint lodLevel, uint face) {
    uvec3 attributes = uvec3(0);

    uint lighting = extractLightId(quad);

    //Apply model colour tinting
    uint tintColour = model.colourTint;

    if (modelHasBiomeLUT(model)) {
        tintColour = quadUsesBlendPalette(quad) != 0u
                ? colourData[57344u + extractBlendIdx(quad)]
                : colourData[tintColour + extractBiomeId(quad)];
    }

    #ifdef PATCHED_SHADER
    attributes.x = lighting;
    attributes.y = tintColour;
    #else
    bool isTranslucent = modelIsTranslucent(model);

    //afak, these are the same variable in vanilla, (i.e. shaded == ao)
    bool isShaded = modelIsShaded(model);
    bool hasAO = isShaded;

    vec4 tinting = getLighting(lighting);

    uint conditionalTinting = tintColour;

    uint addin = 0;
    if (!isTranslucent) {
        tinting.w = 0.0;
        //Encode the face, the lod level and
        uint encodedData = 0;
        encodedData |= face;
        encodedData |= (lodLevel<<3);
        encodedData |= uint(hasAO)<<6;
        addin = encodedData;
    }

    tinting.rgb *= computeDirectionalFaceTint(isShaded, face);

    attributes.x = packVec4(tinting);
    attributes.y = conditionalTinting;
    attributes.z = addin|(face<<8);
    #endif

    attributes.z |= modelUsesFluidDatum(model) ? (1u << 11u) : 0u;
    attributes.z |= modelIsLeaf(model) ? (1u << 12u) : 0u;
    attributes.z |= (modelUsesFluidDatum(model) || modelIsLava(model))
            ? (1u << 13u) : 0u;

    return attributes;
}

bool modelIsVanillaFluid(BlockModel model) {
    return modelUsesFluidDatum(model) || modelIsLava(model);
}

float coarseFluidTopIndentation(BlockModel model, float lodScale) {
    return (1.0 - modelFluidHeight(model)) / lodScale;
}

float resolveFluidTopIndentation(BlockModel model, uint face, float bakedIndentation, float localY, float lodScale, ivec3 lodPos, uint lodLevel) {
    if (face != 1u || !modelIsVanillaFluid(model)) return bakedIndentation;

    if (lodLevel > 0u && modelUsesFluidDatum(model)) {
        float coarseBottom = localY * lodScale + float((lodPos.y << lodLevel) << 5);
        float datumPosition = (fluidDatumY - coarseBottom) / lodScale;
        if (datumPosition > 0.0 && datumPosition <= 1.0) {
            return clamp(1.0 - datumPosition, 0.0, 62.0 / 64.0);
        }
    }

    return coarseFluidTopIndentation(model, lodScale);
}

vec4 resolveFluidSideSize(BlockModel model, const in Quad rawQuad, uint face, vec4 faceSize, float lodScale, uint lodLevel, bool fluidShape) {
    if (!modelIsVanillaFluid(model)) return faceSize;
    if (fluidShape) return vec4(0.0, 1.0, 0.0, 1.0);
    const float fluidEpsilon = 0.00005f;
    float top = 1.0 - coarseFluidTopIndentation(model, lodScale);
    if (face == 1u) {
        faceSize = vec4(-fluidEpsilon, 1.0 + fluidEpsilon, -fluidEpsilon, 1.0 + fluidEpsilon);
    } else if (face == 2u || face == 3u) {
        float lowerHeight = float(extractFluidLowerHeight(rawQuad)) / 9.0;
        float bottom = lowerHeight > 0.0 ? 1.0 - ((1.0 - lowerHeight) / lodScale) : 0.0;
        faceSize = vec4(-fluidEpsilon, 1.0 + fluidEpsilon, bottom, max(top - bottom, fluidEpsilon));
    } else if (face == 4u || face == 5u) {
        float lowerHeight = float(extractFluidLowerHeight(rawQuad)) / 9.0;
        float bottom = lowerHeight > 0.0 ? 1.0 - ((1.0 - lowerHeight) / lodScale) : 0.0;
        faceSize = vec4(bottom, max(top - bottom, fluidEpsilon), -fluidEpsilon, 1.0 + fluidEpsilon);
    }
    return faceSize;
}

float decodeFluidCornerHeight(uint code) {
    if (code == 0u) return 0.0;
    if (code == 6u) return 8.0 / 9.0;
    if (code == 7u) return 1.0;
    return float(code + 1u) / 9.0;
}

vec4 decodeFluidCorners(const in Quad rawQuad, float lodScale) {
    uint payload = extractFluidShapePayload(rawQuad);
    vec4 heights = vec4(
            decodeFluidCornerHeight(payload & 7u),
            decodeFluidCornerHeight((payload >> 3u) & 7u),
            decodeFluidCornerHeight((payload >> 6u) & 7u),
            decodeFluidCornerHeight((payload >> 9u) & 7u));
    for (int i = 0; i < 4; i++) {
        if (heights[i] > 0.0) {
            heights[i] = 1.0 - ((1.0 - heights[i]) / lodScale);
        }
    }
    return heights;
}

void setupQuad(out QuadData quad, const in Quad rawQuad, uvec2 sPos, bool generateAttributes) {
    uint lodLevel = getLoDLevel(sPos);
    float lodScale = 1<<lodLevel;
    ivec3 lodPos = getLoDPosition(sPos);
    ivec3 baseSection = (lodPos<<lodLevel) - baseSectionPos;

    uint face = extractFace(rawQuad);
    uint modelId = extractStateId(rawQuad);
    BlockModel model = modelData[modelId];
    uint faceData = model.faceData[face];
    bool fluidShape = modelIsVanillaFluid(model) && face != 0u && quadHasFluidShape(rawQuad);
    ivec2 quadSize = fluidShape ? ivec2(1) : extractSize(rawQuad);

    if (generateAttributes) {
        quad.attributeData.x = makeQuadFlags(faceData, modelId, quadSize, model, face);
        quad.attributeData.yzw = makeRemainingAttributes(model, rawQuad, lodLevel, face);
        quad.attributeData.w |= ((model.flagsA >> 13u) & 1u) << 14u;
        if (modelUsesBalancedLeafCutout(model)) {
            quad.attributeData.w |= makeBalancedLeafSeed(rawQuad, lodPos, lodLevel, face) << 16u;
        }
    }

    vec4 faceSize = resolveFluidSideSize(model, rawQuad, face, getFaceSize(faceData), lodScale, lodLevel, fluidShape);
    #ifdef USE_SINGLE_TRI
    if (!fluidShape) faceSize *= 2;
    #endif
    vec3 quadStart = extractPos(rawQuad);
    quad.fluidShape = fluidShape ? 1u : 0u;
    quad.fluidCornerHeights = fluidShape ? decodeFluidCorners(rawQuad, lodScale) : vec4(0.0);
    quad.fluidBaseY = quadStart.y * lodScale + float(baseSection.y << 5);
    float depthOffset = resolveFluidTopIndentation(
            model, face, extractFaceIndentation(faceData), quadStart.y, lodScale, lodPos, lodLevel);
    if (fluidShape && face == 1u && lodLevel > 0u && modelUsesFluidDatum(model)) {
        quad.fluidCornerHeights = vec4(1.0 - depthOffset);
    }
    if (fluidShape) depthOffset = 0.0;
    quadStart += swizzelDataAxis(face>>1, vec3(faceSize.xz, mix(depthOffset, 1-depthOffset, float(face&1u))));

    quad.lodScale = lodScale;
    quad.axis = face>>1;
    quad.basePoint = (quadStart*lodScale)+vec3(baseSection<<5);
    #ifdef USE_SINGLE_TRI
    quad.quadSizeAddin = fluidShape ? vec2(1.0) : (faceSize.yw + (quadSize - 1)*2);
    #else
    quad.quadSizeAddin = fluidShape ? vec2(1.0) : faceSize.yw + quadSize - 1;
    #endif
    quad.uvCorner = faceSize.xz;
}

vec3 getQuadCornerPoint(in QuadData quad, uint cornerId) {
    vec2 cornerMask = vec2((cornerId>>1)&1u, cornerId&1u)*quad.lodScale;
    vec3 point = quad.basePoint + swizzelDataAxis(quad.axis,vec3(quad.quadSizeAddin*cornerMask,0));
    if (quad.fluidShape != 0u) {
        point.y = quad.fluidBaseY + quad.fluidCornerHeights[cornerId] * quad.lodScale;
    }
    return point;
}

vec3 applyWorldCurvature(vec3 point) {
    float radius = worldCurveData.x;
    vec2 delta = point.xz - cameraSubPos.xz;
    float distanceFromCamera = length(delta);
    float curvedDistance = distanceFromCamera - worldCurveData.y;
    if (radius <= 0.0 || curvedDistance <= 0.0) return point;
    float localRadius = max(radius + point.y, 1.0);
    float angle = curvedDistance / localRadius;
    point.y += (cos(angle) - 1.0) * localRadius;
    point.xz = cameraSubPos.xz + delta * ((worldCurveData.y + sin(angle) * localRadius) / distanceFromCamera);
    return point;
}

vec4 getQuadCornerPos(in QuadData quad, uint cornerId) {
    vec4 pos = MVP * vec4(applyWorldCurvature(getQuadCornerPoint(quad, cornerId)), 1.0f);
    pos.xy += taaOffset*pos.w;
    return pos;
}

#ifndef USE_NV_BARRY
vec2 getCornerUV(const in QuadData quad, uint cornerId) {
    return quad.uvCorner + quad.quadSizeAddin*vec2((cornerId>>1)&1u, cornerId&1u);
}
#endif

#ifndef PATCHED_SHADER
float computeDirectionalFaceTint(bool isShaded, uint face) {
    //Apply face tint
    if (isShaded) {
        //just index on a const array with the face as an index, will be much faster
        // or use a vector and select/sum
        // but per face might be easier?


        if ((face>>1) == 1) {//NORTH, SOUTH
            return Z_AXIS_FACE_TINT;
        } else if ((face>>1) == 2) {//EAST, WEST
            return X_AXIS_FACE_TINT;
        } else if (face == 1) {//UP
            return UP_FACE_TINT;
        }
        //DOWN
        return DOWN_FACE_TINT;
    } else {
        return NO_SHADE_FACE_TINT;
    }
}
#endif
