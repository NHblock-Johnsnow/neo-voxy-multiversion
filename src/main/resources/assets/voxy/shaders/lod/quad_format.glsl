#ifdef QUAD_DATA_USE_64_BIT
#define Quad uint64_t

#define Eu32(data, amountBits, shift) (uint((data)>>(shift))&((1u<<(amountBits))-1))

vec3 extractPos(uint64_t quad) {
    return vec3(Eu32(quad, 5, 21), Eu32(quad, 5, 16), Eu32(quad, 5, 11));
}

ivec2 extractSize(uint64_t quad) {
    return ivec2(Eu32(quad, 4, 3), Eu32(quad, 4, 7)) + ivec2(1);//the + 1 is cause you cant actually have a 0 size quad
}

uint extractFace(uint64_t quad) {
    return Eu32(quad, 3, 0);
}

uint extractStateId(uint64_t quad) {
    return Eu32(quad, 16, 26);
}

uint extractBiomeId(uint64_t quad) {
    return Eu32(quad, 9, 46);
}

uint extractLightId(uint64_t quad) {
    return Eu32(quad, 8, 55);
}

uint quadUsesBlendPalette(uint64_t quad) {
    return Eu32(quad, 1, 63);
}

uint extractBlendIdx(uint64_t quad) {
    return Eu32(quad, 9, 46) | (Eu32(quad, 4, 42) << 9);
}

uint extractFluidLowerHeight(uint64_t quad) {
    return quadUsesBlendPalette(quad) == 0u ? Eu32(quad, 4, 42) : 0u;
}

uint extractFluidShapePayload(uint64_t quad) {
    return Eu32(quad, 8, 3) | (Eu32(quad, 4, 42) << 8);
}

bool quadHasFluidShape(uint64_t quad) {
    return quadUsesBlendPalette(quad) == 0u && Eu32(quad, 4, 42) != 0u;
}

bool isQuadEmpty(uint64_t quad) {
    return quad == uint64_t(0);
}

#else

#define Quad ivec2


uint Eu32v(ivec2 data, int amount, int shift) {
    if (shift > 31) {
        shift -= 32;
        return (uint(data.y)>>uint(shift))&((1u<<uint(amount))-1);
    } else {
        return (uint(data.x)>>uint(shift))&((1u<<uint(amount))-1);
    }
}

vec3 extractPos(ivec2 quad) {
    return vec3(Eu32v(quad, 5, 21), Eu32v(quad, 5, 16), Eu32v(quad, 5, 11));
}

ivec2 extractSize(ivec2 quad) {
    return ivec2(Eu32v(quad, 4, 3), Eu32v(quad, 4, 7)) + ivec2(1);//the + 1 is cause you cant actually have a 0 size quad
}

uint extractFace(ivec2 quad) {
    return Eu32v(quad, 3, 0);
}

uint extractStateId(ivec2 quad) {
    // 模型编号只有 16 位；后四位属于流体形状或群系调色板。
    return Eu32v(quad, 6, 26)|(Eu32v(quad, 10, 32)<<6);
}

uint extractBiomeId(ivec2 quad) {
    return Eu32v(quad, 9, 46);
}

uint extractLightId(ivec2 quad) {
    return Eu32v(quad, 8, 55);
}

uint quadUsesBlendPalette(ivec2 quad) {
    return Eu32v(quad, 1, 63);
}

uint extractBlendIdx(ivec2 quad) {
    return Eu32v(quad, 9, 46) | (Eu32v(quad, 4, 42) << 9);
}

uint extractFluidLowerHeight(ivec2 quad) {
    return quadUsesBlendPalette(quad) == 0u ? Eu32v(quad, 4, 42) : 0u;
}

uint extractFluidShapePayload(ivec2 quad) {
    return Eu32v(quad, 8, 3) | (Eu32v(quad, 4, 42) << 8);
}

bool quadHasFluidShape(ivec2 quad) {
    return quadUsesBlendPalette(quad) == 0u && Eu32v(quad, 4, 42) != 0u;
}

bool isQuadEmpty(ivec2 quad) {
    return all(equal(quad, ivec2(0)));
}
#endif
