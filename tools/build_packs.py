#!/usr/bin/env python3
"""Собирает ресурспаки HvH Client (.mcpack): Swing Animations (3 стиля) и Trail.

Берёт официальные файлы Mojang из репозитория Mojang/bedrock-samples, патчит их и пакует.
Сами файлы Mojang в репозиторий не кладём — они скачиваются при сборке.

  python3 tools/build_packs.py [папка_вывода]
Результат: HvhTrail.mcpack, HvhSwing_Smooth.mcpack, HvhSwing_Slash.mcpack, HvhSwing_Big.mcpack
"""
import json, os, re, struct, sys, urllib.request, zipfile, zlib

RAW = "https://raw.githubusercontent.com/Mojang/bedrock-samples/main/resource_pack/"
OUT = sys.argv[1] if len(sys.argv) > 1 else "dist"

def fetch(path):
    with urllib.request.urlopen(RAW + path, timeout=60) as r:
        return r.read().decode("utf-8")

def load_jsonc(text):
    text = re.sub(r"(?m)^\s*//.*$", "", text)
    text = re.sub(r",(\s*[}\]])", r"\1", text)
    return json.loads(text)

def png(size, pixel):
    """Минимальный RGBA PNG без внешних библиотек."""
    raw = b"".join(b"\x00" + b"".join(bytes(pixel(x, y)) for x in range(size)) for y in range(size))
    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))

def manifest(name, desc, uuid_pack, uuid_mod):
    return json.dumps({
        "format_version": 2,
        "header": {"name": name, "description": desc, "uuid": uuid_pack,
                   "version": [1, 0, 0], "min_engine_version": [1, 21, 0]},
        "modules": [{"type": "resources", "description": desc, "uuid": uuid_mod, "version": [1, 0, 0]}],
    }, ensure_ascii=False, indent=2)

def write_mcpack(name, files):
    os.makedirs(OUT, exist_ok=True)
    p = os.path.join(OUT, name)
    with zipfile.ZipFile(p, "w", zipfile.ZIP_DEFLATED) as z:
        for path, data in files.items():
            z.writestr(path, data)
    print("готово:", p, f"({os.path.getsize(p)} байт)")

# ---------------- Swing ----------------
def swing_pack(style, anim_text):
    anim = load_jsonc(anim_text)
    for key in ("animation.player.first_person.attack_rotation", "animation.player.first_person.attack_rotation_item"):
        bones = anim["animations"][key]["bones"]
        for bone in bones.values():
            for channel in ("position", "rotation"):
                if channel not in bone: continue
                out = []
                for expr in bone[channel]:
                    e = str(expr)
                    if style == "smooth":
                        e = e.replace("variable.attack_time", "(0.5-0.5*math.cos(variable.attack_time*180.0))")
                    elif style == "slash":
                        e = e.replace("variable.attack_time", "math.pow(variable.attack_time,0.6)")
                    elif style == "big" and channel == "rotation":
                        e = f"({e})*1.6"
                    out.append(e)
                bone[channel] = out
    return json.dumps(anim, indent=1)

SWING = {
    "smooth": ("HvhSwing_Smooth", "HvH Swing: Smooth", "Плавный взмах (ease-in-out)",
               "b1f0c1a0-5a7e-4c0e-9a11-1c2d3e4f5a01", "b1f0c1a0-5a7e-4c0e-9a11-1c2d3e4f5a02"),
    "slash":  ("HvhSwing_Slash", "HvH Swing: Slash", "Резкий старт, мягкий конец",
               "b1f0c1a0-5a7e-4c0e-9a11-1c2d3e4f5a11", "b1f0c1a0-5a7e-4c0e-9a11-1c2d3e4f5a12"),
    "big":    ("HvhSwing_Big", "HvH Swing: Big", "Увеличенная амплитуда взмаха",
               "b1f0c1a0-5a7e-4c0e-9a11-1c2d3e4f5a21", "b1f0c1a0-5a7e-4c0e-9a11-1c2d3e4f5a22"),
}

# ---------------- Trail ----------------
TRAIL_PARTICLE = {
    "format_version": "1.10.0",
    "particle_effect": {
        "description": {"identifier": "hvh:trail", "basic_render_parameters": {
            "material": "particles_alpha", "texture": "textures/particle/hvh_trail"}},
        "components": {
            "minecraft:emitter_rate_instant": {"num_particles": 1},
            "minecraft:emitter_lifetime_once": {"active_time": 1},
            "minecraft:emitter_shape_point": {"offset": [0, 0.1, 0], "direction": [0, 0, 0]},
            "minecraft:particle_lifetime_expression": {"max_lifetime": 0.8},
            "minecraft:particle_initial_speed": 0,
            "minecraft:particle_motion_dynamic": {},
            "minecraft:particle_appearance_billboard": {
                "size": ["0.2*(1.0-variable.particle_age/variable.particle_lifetime)",
                         "0.2*(1.0-variable.particle_age/variable.particle_lifetime)"],
                "facing_camera_mode": "lookat_xyz",
                "uv": {"texture_width": 16, "texture_height": 16, "uv": [0, 0], "uv_size": [16, 16]}},
            "minecraft:particle_appearance_tinting": {"color": [
                "0.5+0.5*math.sin(variable.particle_random_1*360.0+variable.particle_age*200.0)",
                "0.5+0.5*math.sin(variable.particle_random_1*360.0+variable.particle_age*200.0+120.0)",
                "0.5+0.5*math.sin(variable.particle_random_1*360.0+variable.particle_age*200.0+240.0)",
                "1.0-variable.particle_age/variable.particle_lifetime"]},
        },
    },
}
TRAIL_ANIM = {
    "format_version": "1.8.0",
    "animations": {"animation.hvh.trail": {"loop": True, "animation_length": 0.05,
                                           "particle_effects": {"0.0": {"effect": "hvh_trail"}}}},
}

def trail_pack(entity_text):
    ent = load_jsonc(entity_text)
    d = ent["minecraft:client_entity"]["description"]
    d.setdefault("particle_effects", {})["hvh_trail"] = "hvh:trail"
    d["animations"]["hvh_trail"] = "animation.hvh.trail"
    d["scripts"]["animate"].append({"hvh_trail": "query.ground_speed > 0.5"})
    def pixel(x, y):  # мягкий круг
        r = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
        a = max(0, min(255, int((8 - r) / 3 * 255)))
        return (255, 255, 255, a)
    return {
        "manifest.json": manifest("HvH Trail", "Радужный след за игроком", "b1f0c1a0-5a7e-4c0e-9a11-1c2d3e4f5a31",
                                  "b1f0c1a0-5a7e-4c0e-9a11-1c2d3e4f5a32"),
        "entity/player.entity.json": json.dumps(ent, indent=1),
        "animations/hvh_trail.animation.json": json.dumps(TRAIL_ANIM, indent=1),
        "particles/hvh_trail.json": json.dumps(TRAIL_PARTICLE, indent=1),
        "textures/particle/hvh_trail.png": png(16, pixel),
    }

def main():
    fp = fetch("animations/player_firstperson.animation.json")
    for style, (fname, name, desc, u1, u2) in SWING.items():
        write_mcpack(fname + ".mcpack", {
            "manifest.json": manifest(name, desc, u1, u2),
            "animations/player_firstperson.animation.json": swing_pack(style, fp)})
    write_mcpack("HvhTrail.mcpack", trail_pack(fetch("entity/player.entity.json")))

if __name__ == "__main__":
    main()
