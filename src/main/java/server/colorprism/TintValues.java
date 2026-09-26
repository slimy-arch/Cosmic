package server.colorprism;

/**
 * The value rules every Coloring Prism tint shares, whether it is stored on an {@code Equip},
 * a cash effect {@code Item}, a character's hair/eyes/skin, or a skill.
 * <p>
 * The sign of a hue carries meaning: {@code 1..359} ROTATES the sprite's own hue by that many
 * degrees, {@code -1..-360} is an ABSOLUTE target encoded as {@code -(degrees + 1)}, and
 * {@code 0} leaves the hue alone. A plain {@code floorMod(hue, 360)} would turn {@code -1}
 * into {@code 359}, silently converting "absolute red" into "rotate by 359", so only the
 * positive half wraps. Chroma and brightness are {@code -100..100}. All three zero is the
 * identity, which is also what "never dyed" means, so no flag is stored anywhere.
 */
public final class TintValues {

    private TintValues() {
    }

    public static short normalizeHue(int hue) {
        if (hue < 0) {
            return (short) Math.max(-360, hue);
        }
        return (short) Math.floorMod(hue, 360);
    }

    public static byte clamp(int value) {
        return (byte) Math.max(-100, Math.min(100, value));
    }

    /** True when the values are in range as sent, before any normalisation. */
    public static boolean isValid(int hue, int chroma, int bright) {
        final boolean hueOk = (hue >= 0 && hue <= 359) || (hue >= -360 && hue <= -1);
        return hueOk && chroma >= -100 && chroma <= 100 && bright >= -100 && bright <= 100;
    }

    public static boolean isIdentity(int hue, int chroma, int bright) {
        return hue == 0 && chroma == 0 && bright == 0;
    }
}
