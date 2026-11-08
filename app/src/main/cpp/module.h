#pragma once
#include <map>
#include <memory>
#include <string>
#include <vector>

// Базовый класс модуля. onTick вызывается каждый игровой тик из хука (см. native-lib.cpp).
class Module {
public:
    explicit Module(std::string id) : id_(std::move(id)) {}
    virtual ~Module() = default;
    virtual void onTick() {}
    // Вызывается один раз после загрузки конфига (до старта игры, если либа грузится рано).
    virtual void onLoad() {}
    const std::string& id() const { return id_; }
    bool enabled = false;
    std::map<std::string, float> settings;
private:
    std::string id_;
};

class ModuleManager {
public:
    static ModuleManager& get() { static ModuleManager m; return m; }
    void add(std::unique_ptr<Module> m) { mods_.push_back(std::move(m)); }
    void load() { for (auto& m : mods_) if (m->enabled) m->onLoad(); }
    void tick() { for (auto& m : mods_) if (m->enabled) m->onTick(); }
    // Читает hvh.cfg (key=value), который пишет Config.kt
    void loadConfig(const std::string& path);
private:
    std::vector<std::unique_ptr<Module>> mods_;
};
