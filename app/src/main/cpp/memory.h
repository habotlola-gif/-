#pragma once
// Android: границы libminecraftpe.so в памяти процесса и запись в код.
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <sys/mman.h>
#include <unistd.h>

namespace mem {

struct Region { uintptr_t start, end; bool exec; };

// Читаем /proc/self/maps и собираем читаемые сегменты модуля.
inline std::vector<Region> moduleRegions(const char* name) {
    std::vector<Region> r;
    FILE* f = fopen("/proc/self/maps", "r");
    if (!f) return r;
    char line[512];
    while (fgets(line, sizeof line, f)) {
        if (!strstr(line, name)) continue;
        unsigned long a, b; char perms[5];
        if (sscanf(line, "%lx-%lx %4s", &a, &b, perms) == 3 && perms[0] == 'r')
            r.push_back({a, b, perms[2] == 'x'});
    }
    fclose(f);
    return r;
}

inline uintptr_t moduleBase(const char* name) {
    auto r = moduleRegions(name);
    return r.empty() ? 0 : r.front().start;
}

// Делает страницы записываемыми, пишет байты, возвращает защиту и сбрасывает кэш инструкций.
inline bool patch(void* addr, const void* data, size_t len) {
    long ps = sysconf(_SC_PAGESIZE);
    uintptr_t a = (uintptr_t)addr & ~(uintptr_t)(ps - 1);
    size_t span = ((uintptr_t)addr + len) - a;
    if (mprotect((void*)a, span, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) return false;
    memcpy(addr, data, len);
    __builtin___clear_cache((char*)addr, (char*)addr + len);
    return true;
}

}  // namespace mem
