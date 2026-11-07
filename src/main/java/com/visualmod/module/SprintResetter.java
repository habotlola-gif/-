package com.visualmod.module;

public final class SprintResetter {
    private static final int RESPRINT_TICKS = 2;
    private int holdLeft;
    private int resprintLeft;
    private boolean pressBackward;

    public void start(int ticks, boolean sTap) {
        this.holdLeft = ticks;
        this.pressBackward = sTap;
        this.resprintLeft = 0;
    }

    public void cancel() {
        this.holdLeft = 0;
        this.resprintLeft = 0;
    }

    public void tick() {
        if (this.holdLeft > 0) {
            --this.holdLeft;
            if (this.holdLeft == 0) {
                this.resprintLeft = 2;
            }
        } else if (this.resprintLeft > 0) {
            --this.resprintLeft;
        }
    }

    public boolean adjustForward(boolean pressed) {
        return pressed && this.holdLeft == 0;
    }

    public boolean adjustBackward(boolean pressed) {
        return pressed || this.holdLeft > 0 && this.pressBackward;
    }

    public boolean adjustSprint(boolean pressed) {
        return pressed || this.resprintLeft > 0;
    }
}

