#version 330

#moj_import <minecraft:globals.glsl>

// Руки отрисованы в отдельный буфер: alpha > 0 там, где рука/предмет, 0 — пусто.
uniform sampler2D InSampler;

// Параметры из настроек модуля ShaderHands (см. ShaderHandsRenderer).
layout(std140) uniform HandsInfo {
    vec4 ColorA;
    vec4 ColorB;
    float Fill;        // 0..1 — насколько заливка перекрывает текстуру
    float GlowRadius;  // радиус свечения в пикселях при 1080p
    float Phase;       // фаза анимации градиента, радианы
    int Mode;          // 0 — заливка + свечение, 1 — только заливка, 2 — только свечение
    float Trail;       // яркость огненного шлейфа, 0 — выключен
    int TrailPalette;  // 0 — настоящий огонь, 1 — цвета рук
};

// Жар шлейфа (см. hands_trail.fsh), красный канал: 0 — холодно, 1 — у самых рук.
uniform sampler2D TrailSampler;

in vec2 texCoord;

out vec4 fragColor;

const int GLOW_RINGS = 6;
const int GLOW_DIRECTIONS = 14;
const float TAU = 6.2831853;

vec3 gradient(vec2 uv) {
    float phase = (uv.x * 1.6 + uv.y) * 3.0 - Phase;
    return mix(ColorA.rgb, ColorB.rgb, 0.5 + 0.5 * sin(phase));
}

// Цвет пламени по жару: кончики тёмные, ближе к рукам — ярче и почти белые.
vec3 flame(float heat, vec3 grad) {
    if (TrailPalette == 0) {
        vec3 c = mix(vec3(0.55, 0.04, 0.0), vec3(1.0, 0.38, 0.03), smoothstep(0.0, 0.45, heat));
        c = mix(c, vec3(1.0, 0.78, 0.25), smoothstep(0.4, 0.75, heat));
        return mix(c, vec3(1.0, 0.95, 0.78), smoothstep(0.75, 1.0, heat));
    }
    vec3 c = mix(ColorB.rgb * 0.6, grad, smoothstep(0.0, 0.55, heat));
    return mix(c, vec3(1.0), smoothstep(0.7, 1.0, heat) * 0.6);
}

void main() {
    vec4 hand = texture(InSampler, texCoord);
    vec3 grad = gradient(texCoord);

    if (hand.a > 0.01) {
        // Заливка сохраняет объём: яркость исходной текстуры модулирует градиент.
        float fill = Mode == 2 ? 0.0 : Fill;
        float lum = dot(hand.rgb, vec3(0.299, 0.587, 0.114));
        vec3 tinted = grad * (0.45 + 0.75 * lum);
        fragColor = vec4(mix(hand.rgb, tinted, fill), hand.a);
        return;
    }

    float glow = 0.0;
    if (Mode != 1) {
        vec2 texel = 1.0 / ScreenSize;
        float radius = GlowRadius * max(ScreenSize.y / 1080.0, 1.0);
        float coverage = 0.0;
        float total = 0.0;
        for (int ring = 1; ring <= GLOW_RINGS; ring++) {
            float dist = radius * float(ring) / float(GLOW_RINGS);
            float weight = 1.0 - float(ring - 1) / float(GLOW_RINGS);
            for (int i = 0; i < GLOW_DIRECTIONS; i++) {
                float angle = TAU * (float(i) + 0.5 * float(ring)) / float(GLOW_DIRECTIONS);
                vec2 offset = vec2(cos(angle), sin(angle)) * dist * texel;
                coverage += step(0.01, texture(InSampler, texCoord + offset).a) * weight;
                total += weight;
            }
        }
        glow = smoothstep(0.0, 0.3, coverage / total);
    }
    vec3 glowColor = mix(grad, vec3(1.0), 0.15);

    // огненный шлейф поверх свечения
    float fireAlpha = 0.0;
    vec3 fireColor = vec3(0.0);
    if (Trail > 0.0) {
        float heat = clamp(texture(TrailSampler, texCoord).r * Trail, 0.0, 1.0);
        fireAlpha = smoothstep(0.03, 0.5, heat);
        fireColor = flame(heat, grad);
    }

    float alpha = fireAlpha + glow * (1.0 - fireAlpha);
    if (alpha <= 0.003) {
        discard;
    }
    vec3 rgb = (fireColor * fireAlpha + glowColor * glow * (1.0 - fireAlpha)) / alpha;
    fragColor = vec4(rgb, alpha);
}
