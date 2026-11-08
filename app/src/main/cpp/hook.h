#pragma once
// Минимальный inline-хук для ARM64.
// Пишет в начало функции 16 байт: LDR X16,#8 ; BR X16 ; .quad <hook>.
// Оригинал вызывается через трамплин: 16 сохранённых байт + прыжок на target+16.
// Ограничение: если среди первых 4 инструкций есть PC-относительные (ADR/ADRP/B/BL/LDR literal/CBZ/TBZ/B.cond),
// их нельзя просто скопировать — хук откажется ставиться (вернёт nullptr), такую функцию нужно хукать иначе.
#include "memory.h"
#include <cstdint>
#include <cstring>

namespace hook {

inline bool isPcRelative(uint32_t i) {
    return (i & 0x9F000000) == 0x10000000 ||  // ADR/ADRP
           (i & 0x7C000000) == 0x14000000 ||  // B/BL
           (i & 0xFF000000) == 0x54000000 ||  // B.cond
           (i & 0x7E000000) == 0x34000000 ||  // CBZ/CBNZ
           (i & 0x7E000000) == 0x36000000 ||  // TBZ/TBNZ
           (i & 0x3B000000) == 0x18000000;    // LDR literal
}

// Возвращает указатель на трамплин с оригиналом или nullptr.
inline void* install(void* target, void* replacement) {
    uint32_t orig[4];
    memcpy(orig, target, 16);
    for (uint32_t i : orig) if (isPcRelative(i)) return nullptr;

    // трамплин: 16 байт оригинала + LDR X16,#8 ; BR X16 ; .quad target+16
    void* tramp = mmap(nullptr, 64, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (tramp == MAP_FAILED) return nullptr;
    uint32_t* t = (uint32_t*)tramp;
    memcpy(t, orig, 16);
    t[4] = 0x58000050;  // LDR X16, #8
    t[5] = 0xD61F0200;  // BR X16
    uint64_t back = (uint64_t)target + 16;
    memcpy(&t[6], &back, 8);
    __builtin___clear_cache((char*)tramp, (char*)tramp + 64);

    uint32_t jmp[6] = {0x58000050, 0xD61F0200, 0, 0};
    uint64_t hk = (uint64_t)replacement;
    memcpy(&jmp[2], &hk, 8);
    if (!mem::patch(target, jmp, 16)) { munmap(tramp, 64); return nullptr; }
    return tramp;
}

}  // namespace hook
