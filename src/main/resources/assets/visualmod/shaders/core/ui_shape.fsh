#version 330

#moj_import <minecraft:dynamictransforms.glsl>

// Скруглённый прямоугольник через SDF: одна четвёрка вершин на фигуру, сглаживание края — 1 px.
in vec4 vertexColor;
in vec2 localPos;
flat in ivec2 shapeSize;
flat in ivec2 shapeParams;

out vec4 fragColor;

// должны совпадать с ColorSetting.SATURATION / BRIGHTNESS
const float HUE_SATURATION = 0.65;
const float HUE_BRIGHTNESS = 0.97;

float roundedBoxDistance(vec2 p, vec2 halfSize, float radius) {
    vec2 q = abs(p) - halfSize + vec2(radius);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

void main() {
    vec2 halfSize = vec2(shapeSize) * 0.5;
    float distance = roundedBoxDistance(localPos, halfSize, float(shapeParams.x));

    float coverage;
    if (shapeParams.y > 0) {
        float blur = float(shapeParams.y);
        coverage = 1.0 - smoothstep(-blur, blur, distance);
    } else {
        coverage = clamp(0.5 - distance, 0.0, 1.0);
    }

    vec3 rgb = vertexColor.rgb;
    vec2 uv = clamp((localPos + halfSize) / max(vec2(1.0), vec2(shapeSize)), 0.0, 1.0);
    if (shapeParams.y == -1) {
        rgb = hsv2rgb(vec3(uv.x, HUE_SATURATION, HUE_BRIGHTNESS));
    } else if (shapeParams.y == -2) {
        // квадрат выбора цвета: вправо — насыщенность, вверх — яркость; цвет вершины — чистый оттенок
        rgb = mix(vec3(1.0), vertexColor.rgb, uv.x) * (1.0 - uv.y);
    } else if (shapeParams.y == -3) {
        // полная радуга (без приглушения)
        rgb = hsv2rgb(vec3(uv.x, 1.0, 1.0));
    }

    float alpha = vertexColor.a * coverage;
    if (alpha <= 0.0) {
        discard;
    }
    fragColor = vec4(rgb, alpha) * ColorModulator;
}
