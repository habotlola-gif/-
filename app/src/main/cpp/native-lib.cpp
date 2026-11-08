#include "module.h"
#include "hook.h"
#include "memory.h"
#include "options.h"
#include "sigscan.h"
#include <chrono>
#include <thread>
#include <android/log.h>
#include <fstream>
#include <sstream>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "HvhClient", __VA_ARGS__)

void ModuleManager::loadConfig(const std::string& path) {
    std::ifstream in(path);
    std::string line;
    while (std::getline(in, line)) {
        auto eq = line.find('=');
        auto dot = line.find('.');
        if (eq == std::string::npos || dot == std::string::npos || dot > eq) continue;
        std::string id = line.substr(0, dot), key = line.substr(dot + 1, eq - dot - 1);
        float val = std::stof(line.substr(eq + 1));
        for (auto& m : mods_) {
            if (m->id() != id) continue;
            if (key == "enabled") m->enabled = val != 0; else m->settings[key] = val;
        }
    }
}

// ---- Модули ------------------------------------------------------------
// ВАЖНО: тела модулей — заглушки. Для реальной работы нужен доступ к структурам игры
// (LocalPlayer, Level, Actor и т.д.), адреса/оффсеты которых зависят от версии MCPE.
class KillAura : public Module {
public:
    KillAura() : Module("killaura") { settings = {{"range", 4.f}, {"cps", 12.f}}; }
    void onTick() override { /* TODO: найти ближайшего игрока в settings["range"] и вызвать attack() */ }
};
class Reach : public Module {
public: Reach() : Module("reach") { settings = {{"distance", 4.f}}; }
};
class Velocity : public Module {
public: Velocity() : Module("velocity") { settings = {{"horizontal", 100.f}, {"vertical", 100.f}}; }
};
class Criticals : public Module { public: Criticals() : Module("criticals") {} };
// AutoSprint: включает штатную настройку игры "ctrl_sprintonmovement" в options.txt.
// Игра читает её при старте, поэтому изменение вступает в силу при следующем запуске.
class AutoSprint : public Module {
public:
    AutoSprint() : Module("sprint") {}
    void onLoad() override {
        const char* path = "/storage/emulated/0/Android/data/com.mojang.minecraftpe/files/games/com.mojang/minecraftpe/options.txt";
        bool ok = options::set(path, "ctrl_sprintonmovement", "1");
        LOG("AutoSprint: options.txt %s", ok ? "обновлён" : "без изменений/не записан");
    }
};
class AutoTotem : public Module {
public: AutoTotem() : Module("autototem") { settings = {{"health", 6.f}}; }
};

// Сигнатуры игровых функций. Заполняются после анализа libminecraftpe.so конкретной версии (26.60.24).
// Пустая строка = функция ещё не найдена, модуль, который от неё зависит, не включается.
namespace sigs {
    static const char* PLAYER_TICK = "";
}

static void* findInGame(const char* pattern) {
    if (!pattern || !*pattern) return nullptr;
    for (auto& r : mem::moduleRegions("libminecraftpe.so")) {
        if (!r.exec) continue;
        long off = sig::findUnique((const uint8_t*)r.start, r.end - r.start, pattern);
        if (off >= 0) return (void*)(r.start + off);
    }
    return nullptr;
}

static void setup() {
    // ждём, пока игра загрузит свою библиотеку
    for (int i = 0; i < 600 && !mem::moduleBase("libminecraftpe.so"); ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    if (!mem::moduleBase("libminecraftpe.so")) { LOG("libminecraftpe.so не найдена"); return; }
    LOG("libminecraftpe.so at %p", (void*)mem::moduleBase("libminecraftpe.so"));

    void* playerTick = findInGame(sigs::PLAYER_TICK);
    LOG("PLAYER_TICK: %s", playerTick ? "найден" : "сигнатура не задана/не найдена");
    // TODO: hook::install(playerTick, (void*)&hookedPlayerTick) -> в нём ModuleManager::get().tick()
}

// Точка входа: вызывается при загрузке библиотеки в процесс игры.
__attribute__((constructor)) static void init() {
    auto& mm = ModuleManager::get();
    mm.add(std::make_unique<KillAura>());
    mm.add(std::make_unique<Reach>());
    mm.add(std::make_unique<Velocity>());
    mm.add(std::make_unique<Criticals>());
    mm.add(std::make_unique<AutoSprint>());
    mm.add(std::make_unique<AutoTotem>());
    // Конфиг ищем в нескольких местах: каталог игры (доступен самой игре) и каталог лаунчера.
    mm.loadConfig("/sdcard/Android/data/com.hvh.client/files/hvh.cfg");
    mm.loadConfig("/storage/emulated/0/Android/data/com.mojang.minecraftpe/files/hvh.cfg");
    mm.load();
    LOG("HvhClient loaded");
    std::thread(setup).detach();  // не блокируем загрузку игры
}
