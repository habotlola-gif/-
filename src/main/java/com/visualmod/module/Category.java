package com.visualmod.module;

public enum Category {
    COMBAT("Combat"),
    MOVEMENT("Movement"),
    VISUALS("Visuals"),
    PLAYER("Player"),
    MISC("Miscellaneous");

    private final String title;

    private Category(String title) {
        this.title = title;
    }

    public String getTitle() {
        return this.title;
    }
}

