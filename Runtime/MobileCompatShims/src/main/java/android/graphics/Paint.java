package android.graphics;

import app.tachiaz.compat.NativeBridge;

public class Paint {
    public static final int ANTI_ALIAS_FLAG = 0x01;
    public static final int FILTER_BITMAP_FLAG = 0x02;
    public static final int DITHER_FLAG = 0x04;
    public static final int UNDERLINE_TEXT_FLAG = 0x08;
    public static final int STRIKE_THRU_TEXT_FLAG = 0x10;
    public static final int FAKE_BOLD_TEXT_FLAG = 0x20;
    public static final int START_HYPHEN_EDIT_NO_EDIT = 0;
    public static final int END_HYPHEN_EDIT_NO_EDIT = 0;

    private int flags;
    private int color = Color.BLACK;
    private float textSize = 12;
    private Typeface typeface = Typeface.DEFAULT;
    private Style style = Style.FILL;
    private float strokeWidth = 1;
    private Cap strokeCap = Cap.BUTT;

    public Paint() {
        this(ANTI_ALIAS_FLAG);
    }

    public Paint(int flags) {
        this.flags = flags;
    }

    public Paint(Paint source) {
        set(source);
    }

    public void set(Paint source) {
        flags = source.flags;
        color = source.color;
        textSize = source.textSize;
        typeface = source.typeface;
        style = source.style;
        strokeWidth = source.strokeWidth;
        strokeCap = source.strokeCap;
    }

    public void reset() {
        flags = ANTI_ALIAS_FLAG;
        color = Color.BLACK;
        textSize = 12;
        typeface = Typeface.DEFAULT;
        style = Style.FILL;
        strokeWidth = 1;
        strokeCap = Cap.BUTT;
    }

    public int getFlags() {
        return flags;
    }

    public void setFlags(int flags) {
        this.flags = flags;
    }

    public boolean isAntiAlias() {
        return (flags & ANTI_ALIAS_FLAG) != 0;
    }

    public void setAntiAlias(boolean value) {
        setFlag(ANTI_ALIAS_FLAG, value);
    }

    public boolean isDither() {
        return (flags & DITHER_FLAG) != 0;
    }

    public void setDither(boolean value) {
        setFlag(DITHER_FLAG, value);
    }

    public boolean isFilterBitmap() {
        return (flags & FILTER_BITMAP_FLAG) != 0;
    }

    public void setFilterBitmap(boolean value) {
        setFlag(FILTER_BITMAP_FLAG, value);
    }

    public int getColor() {
        return color;
    }

    public long getColorLong() {
        return Color.pack(color);
    }

    public void setColor(int color) {
        this.color = color;
    }

    public void setColor(long color) {
        this.color = Color.toArgb(color);
    }

    public float getTextSize() {
        return textSize;
    }

    public void setTextSize(float textSize) {
        if (textSize < 0) {
            throw new IllegalArgumentException("text size must be >= 0");
        }
        this.textSize = textSize;
    }

    public Typeface getTypeface() {
        return typeface;
    }

    public Typeface setTypeface(Typeface value) {
        Typeface previous = typeface;
        typeface = value == null ? Typeface.DEFAULT : value;
        return previous;
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style style) {
        if (style == null) {
            throw new NullPointerException("style");
        }
        this.style = style;
    }

    public float getStrokeWidth() {
        return strokeWidth;
    }

    public void setStrokeWidth(float strokeWidth) {
        this.strokeWidth = strokeWidth;
    }

    public Cap getStrokeCap() {
        return strokeCap;
    }

    public void setStrokeCap(Cap strokeCap) {
        this.strokeCap = strokeCap;
    }

    public float ascent() {
        return -fontMetrics()[0];
    }

    public float descent() {
        return fontMetrics()[1];
    }

    public float getFontMetrics(FontMetrics metrics) {
        float[] nativeMetrics = fontMetrics();
        metrics.ascent = -nativeMetrics[0];
        metrics.descent = nativeMetrics[1];
        metrics.leading = nativeMetrics[2];
        metrics.top = metrics.ascent;
        metrics.bottom = metrics.descent;
        return nativeMetrics[0] + nativeMetrics[1] + nativeMetrics[2];
    }

    public FontMetrics getFontMetrics() {
        FontMetrics result = new FontMetrics();
        getFontMetrics(result);
        return result;
    }

    public int getFontMetricsInt(FontMetricsInt metrics) {
        FontMetrics value = getFontMetrics();
        metrics.ascent = (int) Math.floor(value.ascent);
        metrics.descent = (int) Math.ceil(value.descent);
        metrics.top = (int) Math.floor(value.top);
        metrics.bottom = (int) Math.ceil(value.bottom);
        metrics.leading = (int) Math.ceil(value.leading);
        return metrics.descent - metrics.ascent + metrics.leading;
    }

    public FontMetricsInt getFontMetricsInt() {
        FontMetricsInt result = new FontMetricsInt();
        getFontMetricsInt(result);
        return result;
    }

    public float getUnderlineThickness() {
        return Math.max(1, textSize / 14);
    }

    public float measureText(String text) {
        if (text == null) {
            throw new NullPointerException("text");
        }
        return NativeBridge.textMeasure(
            text,
            textSize,
            typeface.isBold(),
            typeface.getNativeName()
        );
    }

    public float measureText(String text, int start, int end) {
        return measureText(text.substring(start, end));
    }

    public float measureText(CharSequence text, int start, int end) {
        return measureText(text.subSequence(start, end).toString());
    }

    public float measureText(char[] text, int index, int count) {
        return measureText(new String(text, index, count));
    }

    private float[] fontMetrics() {
        return NativeBridge.textFontMetrics(
            textSize,
            typeface.isBold(),
            typeface.getNativeName()
        );
    }

    private void setFlag(int flag, boolean value) {
        flags = value ? flags | flag : flags & ~flag;
    }

    public enum Style { FILL, STROKE, FILL_AND_STROKE }
    public enum Cap { BUTT, ROUND, SQUARE }
    public enum Join { MITER, ROUND, BEVEL }
    public enum Align { LEFT, CENTER, RIGHT }

    public static class FontMetrics {
        public float top;
        public float ascent;
        public float descent;
        public float bottom;
        public float leading;
    }

    public static class FontMetricsInt {
        public int top;
        public int ascent;
        public int descent;
        public int bottom;
        public int leading;
    }
}
