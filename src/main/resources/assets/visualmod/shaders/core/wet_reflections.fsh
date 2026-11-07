#version 330

#moj_import <minecraft:globals.glsl>

// Мокрая земля: экранные отражения (SSR) на горизонтальных поверхностях + лужи и рябь от капель.
uniform sampler2D SceneSampler;  // копия кадра мира
uniform sampler2D DepthSampler;  // глубина мира (до рук)

layout(std140) uniform ReflectionInfo {
    mat4 InvProjMat;   // экран -> пространство камеры
    mat4 InvViewMat;   // поворот камеры -> мир
    mat4 ViewProjMat;  // мир (относительно камеры) -> экран
    vec4 SkyColor;     // цвет горизонта: им отражается небо, если луч ничего не задел
    vec4 CameraInfo;   // xyz — позиция камеры (x, z по модулю периода шума), w — время, с
    vec4 Params;       // x — намокание 0..1, y — количество луж, z — сила отражений, w — сила ряби (дождь)
};

in vec2 texCoord;

out vec4 fragColor;

const int MAX_STEPS = 48;
const float MAX_DISTANCE = 96.0;

// ---------------------------------------------------------------- шум

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
    float sum = 0.0;
    float amp = 0.55;
    for (int i = 0; i < 4; i++) {
        sum += valueNoise(p) * amp;
        p = p * 2.03 + vec2(17.1, 9.3);
        amp *= 0.5;
    }
    return sum;
}

// ---------------------------------------------------------------- геометрия

// Точка мира относительно камеры по координате экрана и глубине.
vec3 positionAt(vec2 uv, float depth) {
    vec4 view = InvProjMat * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    view /= view.w;
    return (InvViewMat * vec4(view.xyz, 0.0)).xyz;
}

vec3 positionAt(vec2 uv) {
    return positionAt(uv, texture(DepthSampler, uv).r);
}

// Нормаль по соседним пикселям: с каждой стороны берём ближайшего соседа, чтобы не ломаться на краях блоков.
vec3 normalAt(vec2 uv, vec3 p) {
    vec2 px = 1.0 / ScreenSize;
    vec3 l = positionAt(uv - vec2(px.x, 0.0));
    vec3 r = positionAt(uv + vec2(px.x, 0.0));
    vec3 d = positionAt(uv - vec2(0.0, px.y));
    vec3 u = positionAt(uv + vec2(0.0, px.y));
    vec3 dx = length(r - p) < length(p - l) ? r - p : p - l;
    vec3 dy = length(u - p) < length(p - d) ? u - p : p - d;
    vec3 n = normalize(cross(dx, dy));
    return dot(n, -p) < 0.0 ? -n : n;
}

// Рябь от падающих капель: расходящиеся кольца в случайных точках, возвращает наклон поверхности (xz).
vec2 ripples(vec2 p, float t) {
    const float CELL = 0.7;
    vec2 g = p / CELL;
    vec2 id = floor(g);
    vec2 tilt = vec2(0.0);
    for (int i = -1; i <= 1; i++) {
        for (int j = -1; j <= 1; j++) {
            vec2 cell = id + vec2(i, j);
            float h = hash(cell);
            vec2 center = cell + vec2(hash(cell + 3.1), hash(cell + 7.7));
            float phase = fract(t * (0.7 + h * 0.6) + h * 11.0);
            vec2 d = g - center;
            float dist = length(d);
            float radius = phase * 1.1;
            float wave = sin((dist - radius) * 28.0) * exp(-pow((dist - radius) * 7.0, 2.0)) * (1.0 - phase);
            tilt += (dist > 0.001 ? d / dist : vec2(0.0)) * wave;
        }
    }
    return tilt;
}

// ---------------------------------------------------------------- отражение

bool onScreen(vec2 uv) {
    return uv.x > 0.0 && uv.x < 1.0 && uv.y > 0.0 && uv.y < 1.0;
}

vec2 project(vec3 p, out bool valid) {
    vec4 clip = ViewProjMat * vec4(p, 1.0);
    valid = clip.w > 0.05;
    return clip.xy / clip.w * 0.5 + 0.5;
}

// Идём лучом по экрану; попадание — когда луч ушёл за поверхность ненамного (толщина растёт с шагом).
bool traceReflection(vec3 origin, vec3 dir, float startStep, out vec2 hitUv) {
    float stepLength = startStep;
    vec3 prev = origin;
    vec3 p = origin;
    for (int i = 0; i < MAX_STEPS; i++) {
        prev = p;
        p += dir * stepLength;
        bool valid;
        vec2 uv = project(p, valid);
        if (!valid || !onScreen(uv)) {
            return false;
        }
        float depth = texture(DepthSampler, uv).r;
        if (depth < 1.0) {
            vec3 scene = positionAt(uv, depth);
            float behind = length(p) - length(scene);
            if (behind > 0.0 && behind < stepLength * 2.0 + 0.25) {
                // уточняем точку попадания делением отрезка пополам
                vec3 a = prev;
                vec3 b = p;
                for (int k = 0; k < 5; k++) {
                    vec3 m = (a + b) * 0.5;
                    vec2 muv = project(m, valid);
                    vec3 ms = positionAt(muv);
                    if (length(m) > length(ms)) {
                        b = m;
                    } else {
                        a = m;
                    }
                }
                hitUv = project(b, valid);
                return true;
            }
        }
        stepLength *= 1.13;
    }
    return false;
}

void main() {
    float wetness = Params.x;
    vec3 base = texture(SceneSampler, texCoord).rgb;
    float depth = texture(DepthSampler, texCoord).r;
    if (wetness <= 0.0 || depth >= 1.0) {
        discard;
    }

    vec3 p = positionAt(texCoord, depth);
    float dist = length(p);
    if (dist > MAX_DISTANCE) {
        discard;
    }
    vec3 n = normalAt(texCoord, p);
    // мокнут только верхние грани (пол, земля, крыши)
    float up = smoothstep(0.82, 0.95, n.y);
    if (up <= 0.0) {
        discard;
    }

    vec3 world = p + CameraInfo.xyz;
    float time = CameraInfo.w;

    // лужи: пятна шума, их больше при большем «количестве луж»
    float noise = fbm(world.xz * 0.11);
    float threshold = mix(0.78, 0.38, Params.y);
    float puddle = smoothstep(threshold, threshold + 0.06, noise) * smoothstep(0.35, 1.0, wetness);

    // рябь только на лужах и только пока идёт дождь
    vec2 tilt = ripples(world.xz, time) * Params.w * puddle * 0.09;
    // лёгкая неровность мокрой земли вне луж
    tilt += (vec2(valueNoise(world.xz * 6.0), valueNoise(world.xz * 6.0 + 31.7)) - 0.5) * 0.06 * (1.0 - puddle);
    vec3 surfaceNormal = normalize(vec3(tilt.x, 1.0, tilt.y));

    vec3 viewDir = p / dist;
    vec3 reflected = reflect(viewDir, surfaceNormal);

    vec3 sky = SkyColor.rgb * mix(1.0, 1.15, clamp(reflected.y, 0.0, 1.0));
    vec3 reflection = sky;
    vec2 hitUv;
    if (traceReflection(p + n * 0.02, reflected, 0.25 + dist * 0.015, hitUv)) {
        vec2 edge = min(hitUv, 1.0 - hitUv);
        float fade = smoothstep(0.0, 0.1, min(edge.x, edge.y));
        reflection = mix(sky, texture(SceneSampler, hitUv).rgb, fade);
    }

    // Френель: под острым углом отражение сильнее, сверху вниз — почти прозрачная вода
    float cosTheta = clamp(dot(-viewDir, surfaceNormal), 0.0, 1.0);
    float fresnel = 0.02 + 0.98 * pow(1.0 - cosTheta, 5.0);
    float reflectivity = mix(0.07 + fresnel * 0.4, 0.38 + fresnel * 0.62, puddle);
    reflectivity *= Params.z * wetness * up * (1.0 - smoothstep(MAX_DISTANCE * 0.7, MAX_DISTANCE, dist));

    // мокрая поверхность темнее, лужа — ещё темнее
    vec3 wetBase = base * mix(1.0, mix(0.78, 0.62, puddle), wetness * up);
    vec3 color = mix(wetBase, reflection, reflectivity);
    fragColor = vec4(color, 1.0);
}
