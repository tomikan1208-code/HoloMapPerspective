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

    public boolean contains(int x, int y, int z, int width, int height, int depth, int margin) {
        if(x < -margin || x >= width + margin || y < -margin || y >= height + margin
                || z < -margin || z >= depth + margin) return false;
        return switch (this) {
            case SOLID -> true;
            case EMPTY -> false;
            case SHELL -> x == -margin || x == width + margin - 1 || y == -margin || y == height + margin - 1
                    || z == -margin || z == depth + margin - 1;
        };
    }
}
