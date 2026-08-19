package android.text;

import android.graphics.Paint;

public class TextPaint extends Paint {
    public int bgColor;
    public int baselineShift;
    public int linkColor;
    public int[] drawableState;
    public float density = 1;
    public int underlineColor;
    public float underlineThickness;

    public TextPaint() {
        super();
    }

    public TextPaint(int flags) {
        super(flags);
    }

    public TextPaint(Paint paint) {
        super(paint);
    }

    public void set(TextPaint paint) {
        super.set(paint);
        bgColor = paint.bgColor;
        baselineShift = paint.baselineShift;
        linkColor = paint.linkColor;
        drawableState = paint.drawableState;
        density = paint.density;
        underlineColor = paint.underlineColor;
        underlineThickness = paint.underlineThickness;
    }

    public void setUnderlineText(int color, float thickness) {
        underlineColor = color;
        underlineThickness = thickness;
    }

    @Override
    public float getUnderlineThickness() {
        return underlineColor == 0
            ? super.getUnderlineThickness()
            : underlineThickness;
    }
}
