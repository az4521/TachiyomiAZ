package android.graphics;

/** Integer rectangle compatible with the Android graphics API. */
public final class Rect {
    public int left;
    public int top;
    public int right;
    public int bottom;

    public Rect() {
    }

    public Rect(int left, int top, int right, int bottom) {
        set(left, top, right, bottom);
    }

    public Rect(Rect source) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        set(source);
    }

    public void set(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public void set(Rect source) {
        if (source == null) {
            setEmpty();
        } else {
            set(source.left, source.top, source.right, source.bottom);
        }
    }

    public void setEmpty() {
        left = 0;
        top = 0;
        right = 0;
        bottom = 0;
    }

    public int width() {
        return right - left;
    }

    public int height() {
        return bottom - top;
    }

    // Suwayomi's decoder compatibility surface exposes JavaBean aliases.
    public int getWidth() {
        return width();
    }

    public int getHeight() {
        return height();
    }

    public int centerX() {
        return (left + right) >> 1;
    }

    public int centerY() {
        return (top + bottom) >> 1;
    }

    public float exactCenterX() {
        return (left + right) * 0.5f;
    }

    public float exactCenterY() {
        return (top + bottom) * 0.5f;
    }

    public boolean isEmpty() {
        return left >= right || top >= bottom;
    }

    public boolean contains(int x, int y) {
        return left < right && top < bottom &&
            x >= left && x < right && y >= top && y < bottom;
    }

    public boolean contains(int left, int top, int right, int bottom) {
        return this.left < this.right && this.top < this.bottom &&
            this.left <= left && this.top <= top &&
            this.right >= right && this.bottom >= bottom;
    }

    public boolean contains(Rect rectangle) {
        return rectangle != null && contains(
            rectangle.left,
            rectangle.top,
            rectangle.right,
            rectangle.bottom
        );
    }

    public void offset(int dx, int dy) {
        left += dx;
        right += dx;
        top += dy;
        bottom += dy;
    }

    public void offsetTo(int newLeft, int newTop) {
        right += newLeft - left;
        bottom += newTop - top;
        left = newLeft;
        top = newTop;
    }

    public void inset(int dx, int dy) {
        left += dx;
        right -= dx;
        top += dy;
        bottom -= dy;
    }

    public boolean intersect(int left, int top, int right, int bottom) {
        if (
            this.left < right && left < this.right &&
            this.top < bottom && top < this.bottom
        ) {
            this.left = Math.max(this.left, left);
            this.top = Math.max(this.top, top);
            this.right = Math.min(this.right, right);
            this.bottom = Math.min(this.bottom, bottom);
            return true;
        }
        return false;
    }

    public boolean intersect(Rect rectangle) {
        return rectangle != null && intersect(
            rectangle.left,
            rectangle.top,
            rectangle.right,
            rectangle.bottom
        );
    }

    public void union(int left, int top, int right, int bottom) {
        if (left >= right || top >= bottom) {
            return;
        }
        if (isEmpty()) {
            set(left, top, right, bottom);
            return;
        }
        this.left = Math.min(this.left, left);
        this.top = Math.min(this.top, top);
        this.right = Math.max(this.right, right);
        this.bottom = Math.max(this.bottom, bottom);
    }

    public void union(Rect rectangle) {
        if (rectangle != null) {
            union(
                rectangle.left,
                rectangle.top,
                rectangle.right,
                rectangle.bottom
            );
        }
    }

    public String flattenToString() {
        return left + " " + top + " " + right + " " + bottom;
    }

    public static Rect unflattenFromString(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.trim().split("\\s+");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new Rect(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3])
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof Rect)) return false;
        Rect other = (Rect) value;
        return left == other.left && top == other.top &&
            right == other.right && bottom == other.bottom;
    }

    @Override
    public int hashCode() {
        int result = left;
        result = 31 * result + top;
        result = 31 * result + right;
        result = 31 * result + bottom;
        return result;
    }

    @Override
    public String toString() {
        return "Rect(" + left + ", " + top + " - " +
            right + ", " + bottom + ")";
    }
}
