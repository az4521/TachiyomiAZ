package android.text;

import android.graphics.Canvas;
import app.tachiaz.compat.NativeBridge;

public final class StaticLayout extends Layout {
    private final String value;
    private final TextPaint paint;
    private final int[] lineEnds;
    private final int lineHeight;
    private final int descent;
    private final Alignment alignment;

    @Deprecated
    public StaticLayout(
        CharSequence source,
        TextPaint paint,
        int width,
        Alignment alignment,
        float spacingMultiplier,
        float spacingAdd,
        boolean includePad
    ) {
        this(
            source,
            0,
            source.length(),
            paint,
            width,
            alignment,
            spacingMultiplier,
            spacingAdd,
            includePad
        );
    }

    @Deprecated
    public StaticLayout(
        CharSequence source,
        int start,
        int end,
        TextPaint paint,
        int width,
        Alignment alignment,
        float spacingMultiplier,
        float spacingAdd,
        boolean includePad
    ) {
        super(
            source.subSequence(start, end),
            paint,
            width,
            alignment,
            spacingMultiplier,
            spacingAdd
        );
        this.value = source.subSequence(start, end).toString();
        this.paint = paint;
        this.alignment = alignment;
        int[] measured = NativeBridge.textLineEnds(
            value,
            width,
            paint.getTextSize(),
            paint.getTypeface().isBold(),
            paint.getTypeface().getNativeName()
        );
        this.lineEnds = measured == null || measured.length == 0
            ? new int[] {value.length()}
            : measured;
        float[] metrics = NativeBridge.textFontMetrics(
            paint.getTextSize(),
            paint.getTypeface().isBold(),
            paint.getTypeface().getNativeName()
        );
        float naturalHeight = metrics[0] + metrics[1] + metrics[2];
        this.lineHeight = Math.max(
            1,
            (int) Math.ceil((naturalHeight * spacingMultiplier) + spacingAdd)
        );
        this.descent = Math.max(0, (int) Math.ceil(metrics[1]));
    }

    @Override
    public void draw(Canvas canvas) {
        int start = 0;
        for (int line = 0; line < lineEnds.length; line++) {
            int end = Math.min(value.length(), lineEnds[line]);
            int visibleEnd = end;
            while (
                visibleEnd > start &&
                (value.charAt(visibleEnd - 1) == '\n' ||
                    value.charAt(visibleEnd - 1) == '\r')
            ) {
                visibleEnd--;
            }
            if (visibleEnd > start) {
                float x = 0;
                if (alignment != Alignment.ALIGN_NORMAL) {
                    float lineWidth = paint.measureText(value, start, visibleEnd);
                    x = alignment == Alignment.ALIGN_CENTER
                        ? (getWidth() - lineWidth) / 2
                        : getWidth() - lineWidth;
                }
                canvas.drawText(
                    value,
                    start,
                    visibleEnd,
                    x,
                    getLineBaseline(line),
                    paint
                );
            }
            start = end;
        }
    }

    @Override
    public int getLineCount() {
        return lineEnds.length;
    }

    @Override
    public int getLineTop(int line) {
        return Math.max(0, Math.min(line, lineEnds.length)) * lineHeight;
    }

    @Override
    public int getLineDescent(int line) {
        return descent;
    }

    @Override
    public int getLineStart(int line) {
        if (line <= 0) {
            return 0;
        }
        if (line >= lineEnds.length) {
            return value.length();
        }
        return lineEnds[line - 1];
    }

    @Override
    public int getParagraphDirection(int line) {
        return DIR_LEFT_TO_RIGHT;
    }

    @Override
    public boolean getLineContainsTab(int line) {
        return false;
    }

    @Override
    public Directions getLineDirections(int line) {
        return DIRS_ALL_LEFT_TO_RIGHT;
    }

    @Override
    public int getTopPadding() {
        return 0;
    }

    @Override
    public int getBottomPadding() {
        return 0;
    }

    @Override
    public int getEllipsisStart(int line) {
        return 0;
    }

    @Override
    public int getEllipsisCount(int line) {
        return 0;
    }
}
