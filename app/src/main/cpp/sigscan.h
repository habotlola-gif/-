#pragma once
// Поиск функций по байтовому шаблону. Формат: "FD 7B ?? A9 ?? ?? 00 94", "?" или "??" — любой байт.
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <string>
#include <vector>

namespace sig {

struct Pattern {
    std::vector<uint8_t> bytes;
    std::vector<bool> mask;  // true = байт должен совпасть
};

inline bool parse(const std::string& s, Pattern& out) {
    out = {};
    size_t i = 0;
    while (i < s.size()) {
        if (s[i] == ' ') { ++i; continue; }
        if (s[i] == '?') {
            out.bytes.push_back(0); out.mask.push_back(false);
            while (i < s.size() && s[i] == '?') ++i;
        } else {
            if (i + 1 >= s.size()) return false;
            char tmp[3] = {s[i], s[i + 1], 0}, *end;
            long v = strtol(tmp, &end, 16);
            if (*end) return false;
            out.bytes.push_back((uint8_t)v); out.mask.push_back(true);
            i += 2;
        }
    }
    return !out.bytes.empty();
}

// Все смещения совпадений в [data, data+len). limit=0 — без ограничения.
inline std::vector<size_t> findAll(const uint8_t* data, size_t len, const Pattern& p, size_t limit = 0) {
    std::vector<size_t> res;
    const size_t n = p.bytes.size();
    if (len < n) return res;
    // первый обязательный байт — для быстрого пропуска
    size_t first = 0;
    while (first < n && !p.mask[first]) ++first;
    for (size_t i = 0; i + n <= len; ++i) {
        if (first < n && data[i + first] != p.bytes[first]) continue;
        size_t j = 0;
        for (; j < n; ++j) if (p.mask[j] && data[i + j] != p.bytes[j]) break;
        if (j == n) { res.push_back(i); if (limit && res.size() >= limit) break; }
    }
    return res;
}

// Ровно одно совпадение -> смещение, иначе -1 (0 или несколько = шаблон ненадёжен).
inline long findUnique(const uint8_t* data, size_t len, const std::string& pat) {
    Pattern p;
    if (!parse(pat, p)) return -1;
    auto r = findAll(data, len, p, 2);
    return r.size() == 1 ? (long)r[0] : -1;
}

}  // namespace sig
