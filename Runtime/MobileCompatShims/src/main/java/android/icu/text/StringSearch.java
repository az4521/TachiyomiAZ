package android.icu.text;

import java.text.CharacterIterator;

/** Collation-aware substring search for extension fuzzy-search helpers. */
public final class StringSearch {
    public static final int DONE = -1;

    private String pattern;
    private String target;
    private RuleBasedCollator collator;
    private boolean overlapping;
    private int matchStart = DONE;
    private int matchLength;

    public StringSearch(String pattern, CharacterIterator target, RuleBasedCollator collator) {
        if (pattern == null || target == null || collator == null) {
            throw new NullPointerException();
        }
        this.pattern = pattern;
        this.collator = collator;
        this.target = iteratorText(target);
    }

    public void setPattern(String pattern) {
        if (pattern == null) throw new NullPointerException("pattern");
        this.pattern = pattern;
        reset();
    }

    public void setTarget(CharacterIterator target) {
        if (target == null) throw new NullPointerException("target");
        this.target = iteratorText(target);
        reset();
    }

    public void setOverlapping(boolean overlapping) {
        this.overlapping = overlapping;
    }

    public int first() {
        matchStart = DONE;
        return findFrom(0);
    }

    public int next() {
        if (matchStart == DONE) return findFrom(0);
        return findFrom(matchStart + (overlapping ? 1 : Math.max(1, matchLength)));
    }

    public String getMatchedText() {
        return matchStart == DONE ? null : target.substring(matchStart, matchStart + matchLength);
    }

    public void reset() {
        matchStart = DONE;
        matchLength = 0;
    }

    private int findFrom(int start) {
        if (pattern.isEmpty()) {
            matchStart = Math.min(start, target.length());
            matchLength = 0;
            return matchStart;
        }
        int maximumLength = Math.min(target.length(), pattern.length() * 3 + 8);
        for (int index = Math.max(0, start); index < target.length(); index++) {
            int maximumEnd = Math.min(target.length(), index + maximumLength);
            for (int end = index + 1; end <= maximumEnd; end++) {
                String candidate = target.substring(index, end);
                // java.text.Collator may treat surrounding whitespace as a
                // primary-strength ignorable. Android StringSearch reports
                // the actual matching collation elements, excluding it.
                if (hasExtraBoundaryWhitespace(candidate, pattern)) continue;
                int comparison = collator.compare(candidate, pattern);
                if (comparison == 0) {
                    matchStart = index;
                    matchLength = end - index;
                    return index;
                }
            }
        }
        matchStart = DONE;
        matchLength = 0;
        return DONE;
    }

    private static boolean hasExtraBoundaryWhitespace(String candidate, String pattern) {
        return (!candidate.isEmpty() && !pattern.isEmpty()) &&
            ((Character.isWhitespace(candidate.charAt(0)) &&
                !Character.isWhitespace(pattern.charAt(0))) ||
             (Character.isWhitespace(candidate.charAt(candidate.length() - 1)) &&
                !Character.isWhitespace(pattern.charAt(pattern.length() - 1))));
    }

    private static String iteratorText(CharacterIterator iterator) {
        StringBuilder result = new StringBuilder();
        for (char value = iterator.first(); value != CharacterIterator.DONE; value = iterator.next()) {
            result.append(value);
        }
        return result.toString();
    }
}
