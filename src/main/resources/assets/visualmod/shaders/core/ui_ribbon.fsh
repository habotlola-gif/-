#version 330

#moj_import <minecraft:dynamictransforms.glsl>

in vec4 vertexColor;
in vec2 ribbonData;

out vec4 fragColor;

void main() {
    float side = abs(ribbonData.y);
    float width = abs(ribbonData.x);
    float coverage = ribbonData.x < 0.0
        ? exp(-side * side * 9.0) * (1.0 - smoothstep(0.82, 1.0, side))
        : 1.0 - smoothstep(1.0 - 2.0 / max(width, 2.0), 1.0, side);
    float alpha = vertexColor.a * coverage;
    if (alpha <= 0.0) discard;
    fragColor = vec4(vertexColor.rgb, alpha) * ColorModulator;
}
