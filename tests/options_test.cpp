// g++ -std=c++17 -I app/src/main/cpp tests/options_test.cpp -o /tmp/optest && /tmp/optest
#include "options.h"
#include <cassert>
#include <cstdio>
#include <fstream>
#include <string>
static std::string slurp(const char* p){ std::ifstream i(p); std::stringstream s; s<<i.rdbuf(); return s.str(); }
int main() {
    const char* p = "/tmp/opt_test.txt";
    { std::ofstream o(p); o << "gfx_fancygraphics:1\r\nctrl_sprintonmovement:0\r\nmp_username:Steve\r\n"; }
    assert(options::set(p, "ctrl_sprintonmovement", "1"));
    assert(slurp(p) == "gfx_fancygraphics:1\nctrl_sprintonmovement:1\nmp_username:Steve\n");
    assert(!options::set(p, "ctrl_sprintonmovement", "1"));   // уже стоит
    std::remove(p);
    assert(options::set(p, "ctrl_sprintonmovement", "1"));    // файла не было
    assert(slurp(p) == "ctrl_sprintonmovement:1\n");
    std::remove(p);
    puts("OK");
}
