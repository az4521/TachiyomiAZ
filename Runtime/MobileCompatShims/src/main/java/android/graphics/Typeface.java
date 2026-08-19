package android.graphics;

import java.io.File;
import app.tachiaz.compat.NativeBridge;

public class Typeface {
    public static final int NORMAL = 0;
    public static final int BOLD = 1;
    public static final int ITALIC = 2;
    public static final int BOLD_ITALIC = 3;

    public static final Typeface DEFAULT = new Typeface("sans-serif", NORMAL);
    public static final Typeface DEFAULT_BOLD = new Typeface("sans-serif", BOLD);
    public static final Typeface SANS_SERIF = DEFAULT;
    public static final Typeface SERIF = new Typeface("serif", NORMAL);
    public static final Typeface MONOSPACE = new Typeface("monospace", NORMAL);

    private final String family;
    private final int style;

    private Typeface(String family, int style) {
        this.family = family == null ? "sans-serif" : family;
        this.style = style & BOLD_ITALIC;
    }

    public static Typeface create(String familyName, int style) {
        return new Typeface(familyName, style);
    }

    public static Typeface create(Typeface family, int style) {
        return new Typeface(family == null ? null : family.family, style);
    }

    public static Typeface create(Typeface family, int weight, boolean italic) {
        int style = weight >= 600 ? BOLD : NORMAL;
        if (italic) {
            style |= ITALIC;
        }
        return create(family, style);
    }

    public static Typeface defaultFromStyle(int style) {
        return create(DEFAULT, style);
    }

    public static Typeface createFromFile(File file) {
        if (file == null || !file.exists()) {
            throw new RuntimeException("Font asset not found");
        }
        String name = NativeBridge.textRegisterFont(file.getAbsolutePath());
        if (name == null || name.isEmpty()) {
            throw new RuntimeException("Unable to load font asset: " + file);
        }
        return new Typeface(name, NORMAL);
    }

    public static Typeface createFromFile(String path) {
        return createFromFile(new File(path));
    }

    public int getStyle() {
        return style;
    }

    public int getWeight() {
        return isBold() ? 700 : 400;
    }

    public boolean isBold() {
        return (style & BOLD) != 0;
    }

    public boolean isItalic() {
        return (style & ITALIC) != 0;
    }

    public String getSystemFontFamilyName() {
        return family;
    }

    public String getNativeName() {
        if ("sans-serif".equals(family)) {
            return "Helvetica";
        }
        if ("serif".equals(family)) {
            return "Times New Roman";
        }
        if ("monospace".equals(family)) {
            return "Menlo";
        }
        return family;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Typeface)) {
            return false;
        }
        Typeface value = (Typeface) other;
        return style == value.style && family.equals(value.family);
    }

    @Override
    public int hashCode() {
        return (31 * family.hashCode()) + style;
    }
}
