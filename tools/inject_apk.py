#!/usr/bin/env python3
"""Патчит APK Minecraft PE так, чтобы он при старте грузил libhvhclient.so.

Нужно: Java, apktool, zipalign и apksigner (из Android build-tools) в PATH.
Использование:
  python3 tools/inject_apk.py minecraft.apk libhvhclient.so out.apk

ВНИМАНИЕ: скрипт не проверялся на реальном устройстве. Патченый APK подписан другим ключом,
поэтому ставится только поверх удалённой оригинальной версии (данные миров сохрани заранее).
Игровой .so (libminecraftpe.so) мы НЕ меняем.
"""
import os, re, shutil, subprocess, sys, tempfile

def run(*a, **kw):
    print("+", " ".join(a))
    subprocess.run(a, check=True, **kw)

def main(apk, lib, out):
    work = tempfile.mkdtemp(prefix="hvh_")
    dec = os.path.join(work, "dec")
    run("apktool", "d", "-f", apk, "-o", dec)

    # 1. кладём библиотеку рядом с игровой
    dst = os.path.join(dec, "lib", "arm64-v8a")
    os.makedirs(dst, exist_ok=True)
    shutil.copy(lib, os.path.join(dst, "libhvhclient.so"))

    # 2. находим главную активность из манифеста
    manifest = open(os.path.join(dec, "AndroidManifest.xml"), encoding="utf-8").read()
    m = re.search(r'<activity[^>]*android:name="([^"]+)"[^>]*>(?:(?!</activity>).)*?android.intent.action.MAIN', manifest, re.S)
    if not m:
        sys.exit("не нашёл главную активность в манифесте")
    cls = m.group(1)
    print("главная активность:", cls)

    smali = None
    for d in sorted(os.listdir(dec)):
        p = os.path.join(dec, d, cls.replace(".", "/") + ".smali")
        if d.startswith("smali") and os.path.exists(p):
            smali = p; break
    if not smali:
        sys.exit("не нашёл smali-файл активности " + cls)

    # 3. вставляем System.loadLibrary("hvhclient") в начало статического инициализатора
    src = open(smali, encoding="utf-8").read()
    call = '    const-string v0, "hvhclient"\n    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V\n'
    if "hvhclient" in src:
        print("уже пропатчено")
    elif ".method static constructor <clinit>()V" in src:
        src = re.sub(r"(\.method static constructor <clinit>\(\)V\n(?:\s*\.locals \d+\n)?)",
                     lambda mm: mm.group(1).replace(re.search(r"\.locals \d+", mm.group(1)).group(0), ".locals 2") + call
                     if ".locals" in mm.group(1) else mm.group(1) + "    .locals 2\n" + call, src, count=1)
        open(smali, "w", encoding="utf-8").write(src)
    else:
        src += "\n.method static constructor <clinit>()V\n    .locals 2\n" + call + "    return-void\n.end method\n"
        open(smali, "w", encoding="utf-8").write(src)

    # 4. сборка, выравнивание, подпись
    unsigned = os.path.join(work, "unsigned.apk")
    aligned = os.path.join(work, "aligned.apk")
    run("apktool", "b", dec, "-o", unsigned)
    run("zipalign", "-f", "-p", "4", unsigned, aligned)
    ks = os.path.expanduser("~/.hvh-debug.keystore")
    if not os.path.exists(ks):
        run("keytool", "-genkeypair", "-keystore", ks, "-storepass", "android", "-keypass", "android",
            "-alias", "hvh", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-dname", "CN=hvh")
    run("apksigner", "sign", "--ks", ks, "--ks-pass", "pass:android", "--out", out, aligned)
    print("готово:", out)

if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
