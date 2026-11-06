package com.visualmod.module;

import com.mojang.blaze3d.platform.InputConstants;

public final class Keybind {
    public static final int NONE = -1;
    public static final int MOUSE_OFFSET = 1000;

    private Keybind() {
    }

    public static int mouse(int button) {
        return 1000 + button;
    }

    public static boolean isMouse(int bind) {
        return bind >= 1000;
    }

    public static String displayName(int bind) {
        if (bind == -1) {
            return "\u041d\u0435\u0442";
        }
        if (bind >= 65 && bind <= 90 || bind >= 48 && bind <= 57) {
            return String.valueOf((char)bind);
        }
        InputConstants.Key key = Keybind.isMouse(bind) ? InputConstants.Type.MOUSE.getOrCreate(bind - 1000) : InputConstants.Type.KEYSYM.getOrCreate(bind);
        return key.getDisplayName().getString();
    }
}

