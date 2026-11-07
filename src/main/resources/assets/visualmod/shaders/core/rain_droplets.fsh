#version 330

#moj_import <minecraft:globals.glsl>

// Копия кадра мира: внутри капли берём её со смещением (преломление).
uniform sampler2D SceneSampler;

layout(std140) uniform DropletInfo {
    float Intensity;  // 0..1 — сколько капель и насколько они заметны
    float Time;       // секунды
};

in vec2 texCoord;

out vec4 fragColor;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

vec2 hash2(vec2 p) {
    return vec2(hash(p), hash(p + 17.17));
}

// Капля в форме линзы: xy — «нормаль» (куда наклонена поверхность), z — маска 0..1.
vec3 lens(vec2 d, float radius) {
    float dist = length(d);
    float mask = smoothstep(radius, radius * 0.75, dist);
    vec2 normal = radius > 0.0 ? d / radius : vec2(0.0);
    return vec3(normal * mask, mask);
}

// Неподвижные капли: в каждой клетке сетки одна капля, появляется и высыхает по своему циклу.
vec3 staticDrops(vec2 p, float scale, float t) {
    vec2 g = p * scale;
    vec2 id = floor(g);
    vec2 f = fract(g) - 0.5;
    vec2 r = hash2(id);
    if (r.x > Intensity * 0.9 + 0.1) {
        return vec3(0.0);
    }
    vec2 center = (r - 0.5) * 0.6;
    float life = fract(t * (0.05 + r.y * 0.08) + r.x * 13.0);
    float grow = smoothstep(0.0, 0.08, life) * (1.0 - smoothstep(0.6, 1.0, life));
    float radius = mix(0.1, 0.24, r.y) * grow;
    return lens(f - center, radius);
}

// Стекающие капли: в каждой колонке одна большая капля сползает вниз и оставляет мелкий след.
vec3 runningDrops(vec2 p, float t) {
    const float COLUMNS = 7.0;
    vec2 g = vec2(p.x * COLUMNS, p.y * 1.4);
    float column = floor(g.x);
    float r = hash(vec2(column, 3.7));
    if (r > Intensity * 0.8 + 0.1) {
        return vec3(0.0);
    }
    float x = fract(g.x) - 0.5;
    // неровный путь вниз
    x += sin(g.y * 9.0 + r * 40.0) * 0.07 + sin(g.y * 3.1 + r * 11.0) * 0.05;
    float speed = 0.07 + r * 0.1;
    float headY = 1.6 - fract(t * speed + r * 7.0) * 2.2; // сверху вниз, с паузой за кадром
    float dy = g.y - headY;

    vec3 result = lens(vec2(x, dy * 0.7), 0.17);

    // след: мелкие капельки выше головы, тают по мере удаления
    if (dy > 0.0 && dy < 1.2) {
        float cell = floor(dy * 9.0);
        float cy = fract(dy * 9.0) - 0.5;
        float seed = hash(vec2(column, cell));
        float size = 0.05 * (1.0 - dy / 1.2) * step(0.35, seed);
        vec3 trail = lens(vec2(x * 1.6, cy * 0.5), size * 3.0);
        result = result.z > trail.z ? result : trail;
    }
    return result;
}

void main() {
    if (Intensity <= 0.0) {
        discard;
    }
    float aspect = ScreenSize.x / ScreenSize.y;
    vec2 p = vec2(texCoord.x * aspect, texCoord.y);

    vec3 a = staticDrops(p, 7.0, Time);
    vec3 b = staticDrops(p + vec2(3.17, 1.41), 12.0, Time * 1.3);
    vec3 c = runningDrops(p, Time);

    float mask = max(max(a.z, b.z), c.z);
    if (mask < 0.02) {
        discard;
    }
    vec2 normal = a.xy + b.xy * 0.7 + c.xy;

    // преломление: капля работает как маленькая линза и отражает картинку вокруг
    vec2 offset = -normal * 0.045 * vec2(1.0 / aspect, 1.0);
    vec3 refracted = texture(SceneSampler, texCoord + offset).rgb;
    vec3 base = texture(SceneSampler, texCoord).rgb;

    // блик от неба сверху-слева и тёмный ободок по краю капли
    vec3 n = normalize(vec3(normal, 1.0));
    float highlight = pow(max(dot(n, normalize(vec3(-0.45, 0.55, 0.7))), 0.0), 36.0);
    float rim = smoothstep(0.55, 1.0, length(normal)) * 0.18;
    vec3 color = refracted * (1.02 - rim) + highlight * 0.65;

    float alpha = mask * clamp(Intensity * 1.2, 0.0, 1.0);
    fragColor = vec4(mix(base, color, alpha), 1.0);
}
