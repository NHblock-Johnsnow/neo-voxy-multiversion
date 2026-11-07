#version 330 core

layout(binding = 0) uniform sampler2D beforeDepthTex;
layout(binding = 1) uniform sampler2D mergedDepthTex;
layout(binding = 2) uniform sampler2D afterDepthTex;

in vec2 UV;

void main() {
    float before = texture(beforeDepthTex, UV).r;
    float merged = texture(mergedDepthTex, UV).r;
    float after = texture(afterDepthTex, UV).r;

        if (after < merged) {
        gl_FragDepth = after;
    } else {
        gl_FragDepth = before;
    }
}
