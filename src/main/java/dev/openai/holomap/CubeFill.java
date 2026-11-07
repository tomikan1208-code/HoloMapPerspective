package dev.openai.holomap;

import java.util.Locale;

public enum CubeFill {
    SOLID,
    SHELL,
    EMPTY;

    public static CubeFill parse(String value) {
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Fill must be solid, shell, or empty.");
        }
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean contains(int x, int y, int z, int width, int height, int depth) {
        return switch (this) {
            case SOLID -> true;
            case EMPTY -> false;
            case SHELL -> x == 0 || x == width - 1 || y == 0 || y == height - 1 || z == 0 || z == depth - 1;
        };
    }
}
