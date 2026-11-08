// Проверка сканера на реальном libminecraftpe.so (запускается на хосте, не на Android).
// g++ -std=c++17 -O2 -I app/src/main/cpp tests/sigscan_test.cpp -o /tmp/sigtest && /tmp/sigtest <путь к .so>
#include "sigscan.h"
#include <cassert>
#include <chrono>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

int main(int argc, char** argv) {
    if (argc < 2) { puts("usage: sigtest <libminecraftpe.so>"); return 1; }
    std::ifstream f(argv[1], std::ios::binary);
    std::vector<uint8_t> d((std::istreambuf_iterator<char>(f)), {});
    printf("loaded %zu bytes\n", d.size());

    // парсер
    sig::Pattern p;
    assert(sig::parse("FD 7B ?? A9 ? 00", p));
    assert(p.bytes.size() == 6 && !p.mask[2] && !p.mask[4] && p.mask[0]);
    assert(!sig::parse("ZZ", p));
    assert(!sig::parse("F", p));

    // берём 24 байта из середины кода и убеждаемся, что поиск находит именно их
    size_t off = 0x07ad3840 + 0x1000;
    std::string s;
    char b[4];
    for (int i = 0; i < 24; i++) { snprintf(b, sizeof b, "%02X ", d[off + i]); s += b; }
    // два байта заменяем на wildcard — должны всё равно найтись
    s[3 * 5] = '?'; s[3 * 5 + 1] = '?'; s[3 * 6] = '?'; s[3 * 6 + 1] = '?';
    auto t0 = std::chrono::steady_clock::now();
    long found = sig::findUnique(d.data(), d.size(), s);
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    printf("pattern: %s\nfound at 0x%lx (expected 0x%zx), %.0f ms\n", s.c_str(), found, off, ms);
    assert(found == (long)off);

    // слишком общий шаблон не должен считаться уникальным
    assert(sig::findUnique(d.data(), d.size(), "00 00 00 00") == -1);
    puts("OK");
}
