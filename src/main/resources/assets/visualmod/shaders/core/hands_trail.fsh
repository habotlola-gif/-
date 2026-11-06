#version 330

#moj_import <minecraft:globals.glsl>

// Шлейф ShaderHands: «жар» прошлого кадра поднимается вверх, колышется шумом и остывает,
// а руки этого кадра подкладывают новый жар. Результат (жар 0..1) — в красном канале.
uniform sampler2D HandSampler;
uniform sampler2D TrailSampler;

layout(std140) uniform TrailInfo {
    float Decay;   // во сколько раз остывает жар за этот кадр (0..1)
    float Rise;    // насколько поднимается жар за этот кадр, доля высоты экрана
    float Time;    // секунды, для шума
    float Source;  // сколько жара даёт рука (0..1)
};

in vec2 texCoord;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

void main() {
    float aspect = ScreenSize.x / max(ScreenSize.y, 1.0);
    vec2 p = vec2(texCoord.x * aspect, texCoord.y);

    // языки пламени: горизонтальный снос по шуму, который сам ползёт вверх
    float n = noise(p * 7.0 + vec2(0.0, -Time * 2.2)) - 0.5;
    float n2 = noise(p * 17.0 + vec2(Time * 0.7, -Time * 4.0)) - 0.5;
    vec2 drift = vec2((n * 0.9 + n2 * 0.35) * Rise * 2.2 / aspect, Rise);

    // жар приходит снизу (texCoord.y растёт вверх); пара соседних выборок — мягкая диффузия
    vec2 src = texCoord - drift;
    vec2 texel = 1.0 / ScreenSize;
    float prev = texture(TrailSampler, src).r * 0.5
            + texture(TrailSampler, src + vec2(texel.x * 1.5, 0.0)).r * 0.25
            + texture(TrailSampler, src - vec2(texel.x * 1.5, 0.0)).r * 0.25;

    // неравномерное остывание — пламя рвётся на отдельные языки
    float flicker = 0.82 + 0.36 * noise(p * 11.0 + vec2(Time * 1.3, -Time * 3.1));
    // минус чуть-чуть сверху: в 8-битной текстуре слабый жар иначе застревает из-за округления
    float heat = prev * pow(Decay, flicker) - 1.5 / 255.0;

    float hand = texture(HandSampler, texCoord).a;
    heat = max(heat, step(0.01, hand) * Source);

    fragColor = vec4(clamp(heat, 0.0, 1.0), 0.0, 0.0, 1.0);
}
