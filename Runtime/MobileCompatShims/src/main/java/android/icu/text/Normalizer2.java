package android.icu.text;

import java.text.Normalizer;
import java.util.Locale;

/** JVM-backed subset of Android's Normalizer2 used by extension search helpers. */
public class Normalizer2 {
    private static final Normalizer2 NFKC_CASEFOLD = new Normalizer2();

    public static Normalizer2 getNFKCCasefoldInstance() {
        return NFKC_CASEFOLD;
    }

    public String normalize(CharSequence source) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        String normalized = Normalizer.normalize(source, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
        // Java's lower-casing is not full Unicode case folding. Cover the
        // common multi-character folds used by title matching.
        return normalized
            .replace("ß", "ss")
            .replace("ς", "σ");
    }
}
