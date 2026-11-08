#pragma once
// Правка options.txt игры (формат "ключ:значение" по строке).
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

namespace options {

// Ставит key:value. Возвращает true, если файл изменён. Если файла нет — создаёт.
inline bool set(const std::string& path, const std::string& key, const std::string& value) {
    std::vector<std::string> lines;
    {
        std::ifstream in(path);
        std::string l;
        while (std::getline(in, l)) {
            if (!l.empty() && l.back() == '\r') l.pop_back();
            lines.push_back(l);
        }
    }
    const std::string prefix = key + ":";
    bool found = false, changed = false;
    for (auto& l : lines) {
        if (l.compare(0, prefix.size(), prefix) == 0) {
            found = true;
            if (l != prefix + value) { l = prefix + value; changed = true; }
        }
    }
    if (!found) { lines.push_back(prefix + value); changed = true; }
    if (!changed) return false;
    std::ofstream out(path, std::ios::trunc);
    if (!out) return false;
    for (auto& l : lines) out << l << "\n";
    return out.good();
}

}  // namespace options
