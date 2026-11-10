"""Оверлей-прицел для CS2 (Windows). Только стандартная библиотека Python 3.

Запуск:  python crosshair.py
Опции:   python crosshair.py --color 00ff00 --size 8 --gap 4 --thickness 2 --dot --outline

Горячие клавиши (работают в игре):
  F8  - показать / скрыть прицел
  F9  - выход
  F6 / F7 - уменьшить / увеличить размер линий

Важно: CS2 должна быть в режиме «Окно без рамки» (Borderless Windowed),
иначе оверлей не отобразится поверх игры в полноэкранном режиме.
Прицел — просто картинка поверх экрана, он не трогает память игры.
"""
import argparse
import ctypes
import sys
import tkinter as tk

TRANSPARENT = "#010101"  # цвет, который станет прозрачным


def parse():
    p = argparse.ArgumentParser(description="Оверлей-прицел для CS2")
    p.add_argument("--color", default="00ff00", help="HEX цвет без # (по умолч. 00ff00)")
    p.add_argument("--size", type=int, default=8, help="длина линий, px")
    p.add_argument("--gap", type=int, default=4, help="зазор от центра, px")
    p.add_argument("--thickness", type=int, default=2, help="толщина линий, px")
    p.add_argument("--dot", action="store_true", help="точка в центре")
    p.add_argument("--no-tee", action="store_true", help="убрать верхнюю линию (T-образный)")
    p.add_argument("--outline", action="store_true", help="чёрная обводка")
    return p.parse_args()


class Crosshair:
    def __init__(self, a):
        self.a = a
        self.visible = True
        self.root = tk.Tk()
        r = self.root
        r.overrideredirect(True)
        r.attributes("-topmost", True)
        r.config(bg=TRANSPARENT)
        r.attributes("-transparentcolor", TRANSPARENT)
        self.sw, self.sh = r.winfo_screenwidth(), r.winfo_screenheight()
        self.box = 200
        x, y = (self.sw - self.box) // 2, (self.sh - self.box) // 2
        r.geometry(f"{self.box}x{self.box}+{x}+{y}")
        self.cv = tk.Canvas(r, width=self.box, height=self.box, bg=TRANSPARENT,
                            highlightthickness=0)
        self.cv.pack()
        r.update_idletasks()
        self.click_through()
        self.draw()
        self.poll()

    def click_through(self):
        """Клики проходят сквозь окно прямо в игру."""
        user32 = ctypes.windll.user32
        hwnd = user32.GetParent(self.root.winfo_id()) or self.root.winfo_id()
        GWL_EXSTYLE, LAYERED, TRANSP, TOOL = -20, 0x80000, 0x20, 0x80
        st = user32.GetWindowLongW(hwnd, GWL_EXSTYLE)
        user32.SetWindowLongW(hwnd, GWL_EXSTYLE, st | LAYERED | TRANSP | TOOL)

    def draw(self):
        a, cv = self.a, self.cv
        cv.delete("all")
        if not self.visible:
            return
        c = self.box // 2
        col = "#" + a.color.lstrip("#")
        t, g, s = a.thickness, a.gap, a.size
        h = t / 2
        rects = [
            (c - g - s, c - h, c - g, c + h),      # лево
            (c + g, c - h, c + g + s, c + h),      # право
            (c - h, c + g, c + h, c + g + s),      # низ
        ]
        if not a.no_tee:
            rects.append((c - h, c - g - s, c + h, c - g))  # верх
        if a.dot:
            rects.append((c - h, c - h, c + h, c + h))
        for x1, y1, x2, y2 in rects:
            if a.outline:
                cv.create_rectangle(x1 - 1, y1 - 1, x2 + 1, y2 + 1, fill="#000000", outline="")
        for x1, y1, x2, y2 in rects:
            cv.create_rectangle(x1, y1, x2, y2, fill=col, outline="")

    def poll(self):
        key = ctypes.windll.user32.GetAsyncKeyState
        pressed = lambda vk: key(vk) & 0x0001  # было нажатие с прошлого опроса
        if pressed(0x77):          # F8
            self.visible = not self.visible
            self.draw()
        if pressed(0x78):          # F9
            self.root.destroy()
            return
        if pressed(0x75):          # F6
            self.a.size = max(1, self.a.size - 1)
            self.draw()
        if pressed(0x76):          # F7
            self.a.size = min(90, self.a.size + 1)
            self.draw()
        self.root.after(50, self.poll)

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    if sys.platform != "win32":
        sys.exit("Эта программа работает только на Windows.")
    Crosshair(parse()).run()
