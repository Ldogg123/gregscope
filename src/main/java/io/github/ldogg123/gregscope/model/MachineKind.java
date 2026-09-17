package io.github.ldogg123.gregscope.model;

/** Coarse machine family. The id is part of the stable snapshot contract. [pure] */
public enum MachineKind {

    SINGLEBLOCK("singleblock"),
    MULTIBLOCK("multiblock");

    private final String id;

    MachineKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
