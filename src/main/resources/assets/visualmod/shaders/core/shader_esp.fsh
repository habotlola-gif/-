#version 330

#moj_import <minecraft:globals.glsl>

// Ванильный буфер контуров: силуэты подсвеченных сущностей (цвет команды), видны сквозь стены.
uniform sampler2D InSampler;

// Параметры из настроек модуля ShaderESP (см. ShaderEspRenderer).
layout(std140) uniform EspInfo {
    vec4 ColorA;
    vec4 ColorB;
    float Fill;        // 0..1 — непрозрачность заливки силуэта
    float GlowRadius;  // радиус свечения в пикселях при 1080p, 0 — без свечения
    float Phase;       // фаза анимации градиента, радианы
    int ColorMode;     // 0 — градиент, 1 — цвет команды
};

in vec2 texCoord;

out vec4 fragColor;

const float TAU = 6.2831853;
const int GLOW_RINGS = 5;
const int GLOW_DIRECTIONS = 12;
const float OUTLINE_WIDTH = 1.5;   // px при 1080p
const float GLOW_STRENGTH = 0.85;
// EntityRendererMixin пишет эту метку для друзей в буфер контуров.
const vec3 FRIEND_MARKER = vec3(242.0, 11.0, 239.0) / 255.0;
const vec3 FRIEND_COLOR = vec3(74.0, 222.0, 128.0) / 255.0;

bool isFriend(vec3 color) {
    return distance(color, FRIEND_MARKER) < 0.01;
}

vec3 gradient(vec2 uv) {
    float phase = (uv.x * 1.6 + uv.y) * 3.0 - Phase;
    return mix(ColorA.rgb, ColorB.rgb, 0.5 + 0.5 * sin(phase));
}

bool inside(vec2 uv) {
    return texture(InSampler, uv).a > 0.01;
}

void main() {
    float scale = max(ScreenSize.y / 1080.0, 1.0);
    vec2 texel = 1.0 / ScreenSize;
    vec4 center = texture(InSampler, texCoord);

    if (center.a > 0.01) {
        vec3 color = isFriend(center.rgb) ? FRIEND_COLOR : (ColorMode == 1 ? center.rgb : gradient(texCoord));
        // контур: внутри силуэта, но рядом есть пустота
        float w = OUTLINE_WIDTH * scale;
        bool edge = false;
        for (int i = 0; i < 8; i++) {
            float angle = TAU * float(i) / 8.0;
            if (!inside(texCoord + vec2(cos(angle), sin(angle)) * w * texel)) {
                edge = true;
                break;
            }
        }
        fragColor = vec4(color, edge ? 1.0 : Fill);
        if (fragColor.a <= 0.0) {
            discard;
        }
        return;
    }

    if (GlowRadius <= 0.0) {
        discard;
    }

    // быстрый выход: большинство пикселей далеко от сущностей
    float radius = GlowRadius * scale;
    bool near = false;
    for (int i = 0; i < GLOW_DIRECTIONS && !near; i++) {
        float angle = TAU * float(i) / float(GLOW_DIRECTIONS);
        vec2 dir = vec2(cos(angle), sin(angle)) * texel;
        near = inside(texCoord + dir * radius * 0.5) || inside(texCoord + dir * radius);
    }
    if (!near) {
        discard;
    }

    float coverage = 0.0;
    float friendCoverage = 0.0;
    float total = 0.0;
    vec3 teamColor = vec3(0.0);
    for (int ring = 1; ring <= GLOW_RINGS; ring++) {
        float dist = radius * float(ring) / float(GLOW_RINGS);
        float weight = 1.0 - float(ring - 1) / float(GLOW_RINGS);
        for (int i = 0; i < GLOW_DIRECTIONS; i++) {
            float angle = TAU * (float(i) + 0.5 * float(ring)) / float(GLOW_DIRECTIONS);
            vec4 s = texture(InSampler, texCoord + vec2(cos(angle), sin(angle)) * dist * texel);
            float hit = step(0.01, s.a);
            coverage += hit * weight;
            float friendHit = isFriend(s.rgb) ? hit * weight : 0.0;
            friendCoverage += friendHit;
            teamColor += s.rgb * (hit * weight - friendHit);
            total += weight;
        }
    }

    float glow = smoothstep(0.0, 0.35, coverage / total) * GLOW_STRENGTH;
    if (glow <= 0.0) {
        discard;
    }
    vec3 color = ColorMode == 1
        ? (teamColor + FRIEND_COLOR * friendCoverage) / max(coverage, 0.0001)
        : mix(gradient(texCoord), FRIEND_COLOR, friendCoverage / max(coverage, 0.0001));
    fragColor = vec4(color, glow);
}
