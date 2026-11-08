#include "module.h"
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
class AutoSprint : public Module { public: AutoSprint() : Module("sprint") {} };
class AutoTotem : public Module {
public: AutoTotem() : Module("autototem") { settings = {{"health", 6.f}}; }
};

// Точка входа: вызывается при загрузке библиотеки в процесс игры.
__attribute__((constructor)) static void init() {
    auto& mm = ModuleManager::get();
    mm.add(std::make_unique<KillAura>());
    mm.add(std::make_unique<Reach>());
    mm.add(std::make_unique<Velocity>());
    mm.add(std::make_unique<Criticals>());
    mm.add(std::make_unique<AutoSprint>());
    mm.add(std::make_unique<AutoTotem>());
    // Путь к конфигу в итоговой версии определяется при инъекции.
    mm.loadConfig("/sdcard/Android/data/com.hvh.client/files/hvh.cfg");
    LOG("HvhClient loaded");
    // TODO: хукнуть игровой тик (напр. Level::tick / LocalPlayer::normalTick) и вызвать mm.tick()
}
