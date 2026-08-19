package android.icu.text;

import java.util.Locale;

/** JVM-backed subset of Android's ICU Collator. */
public class Collator implements java.util.Comparator<Object>, Cloneable {
    public static final int PRIMARY = 0;
    public static final int SECONDARY = 1;
    public static final int TERTIARY = 2;
    public static final int QUATERNARY = 3;
    public static final int IDENTICAL = 15;
    public static final int NO_DECOMPOSITION = 16;
    public static final int CANONICAL_DECOMPOSITION = 17;
    public static final int FULL_DECOMPOSITION = 18;

    protected java.text.Collator delegate;

    protected Collator(java.text.Collator delegate) {
        this.delegate = delegate;
    }

    public static Collator getInstance() {
        return new RuleBasedCollator(java.text.Collator.getInstance());
    }

    public static Collator getInstance(Locale locale) {
        return new RuleBasedCollator(java.text.Collator.getInstance(locale));
    }

    public void setStrength(int strength) {
        int mapped = strength >= IDENTICAL ? java.text.Collator.IDENTICAL
            : Math.max(java.text.Collator.PRIMARY, Math.min(java.text.Collator.TERTIARY, strength));
        delegate.setStrength(mapped);
    }

    public int getStrength() {
        return delegate.getStrength();
    }

    public void setDecomposition(int decomposition) {
        delegate.setDecomposition(decomposition == NO_DECOMPOSITION
            ? java.text.Collator.NO_DECOMPOSITION
            : java.text.Collator.CANONICAL_DECOMPOSITION);
    }

    public int getDecomposition() {
        return delegate.getDecomposition() == java.text.Collator.NO_DECOMPOSITION
            ? NO_DECOMPOSITION : CANONICAL_DECOMPOSITION;
    }

    public int compare(String left, String right) {
        return delegate.compare(left, right);
    }

    @Override
    public int compare(Object left, Object right) {
        return compare(String.valueOf(left), String.valueOf(right));
    }
}
