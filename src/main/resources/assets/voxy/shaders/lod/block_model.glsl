struct BlockModel {
    uint faceData[6];
    uint flagsA;
    uint colourTint;
    uint customId;
    uint _pad[7];
};

// but im going to assume that since we are dealing with huge render distances, this shouldent matter that much
float extractFaceIndentation(uint faceData) {
    uint enc = (faceData>>16)&63u;
    enc += uint(enc==63u);//convert 63 to 64 cause of pain reasons
    return float(enc)/64.0;
}

vec4 extractFaceSizes(uint faceData) {
    return (vec4(faceData&0xFu, (faceData>>4)&0xFu, (faceData>>8)&0xFu, (faceData>>12)&0xFu)/16.0)+vec4(0.0,1.0/16.0,0.0,1.0/16.0);
}

uint faceHasAlphaCuttout(uint faceData) {
    return (faceData>>22)&1u;
}

uint faceHasAlphaCuttoutOverride(uint faceData) {
    return (faceData>>23)&1u;
}

uint faceTintState(uint faceData) {
    return (faceData>>24)&3u;
}

bool modelHasBiomeLUT(BlockModel model) {
    return ((model.flagsA)&2u) != 0;
}

bool modelIsTranslucent(BlockModel model) {
    return ((model.flagsA)&4u) != 0;
}

bool modelIsShaded(BlockModel model) {
    return ((model.flagsA)&8u) != 0;
}

bool modelUsesFluidDatum(BlockModel model) {
    return ((model.flagsA)&16u) != 0;
}

bool modelUsesBalancedLeafCutout(BlockModel model) {
    return ((model.flagsA)&32u) != 0;
}

bool modelIsLava(BlockModel model) {
    return ((model.flagsA)&64u) != 0;
}

bool modelIsLeaf(BlockModel model) {
    return ((model.flagsA)&128u) != 0;
}

bool modelIsFluid(BlockModel model) {
    return ((model.flagsA)&4096u) != 0;
}

bool modelIsFramedBlocks(BlockModel model) {
    return ((model.flagsA)&8192u) != 0;
}

bool modelIsCreateTrack(BlockModel model) {
    return ((model.flagsA)&16384u) != 0;
}

float modelFluidHeight(BlockModel model) {
    // 高度占 8..11 位，12 位是流体标记。
    return float((model.flagsA >> 8u) & 15u) / 9.0;
}
