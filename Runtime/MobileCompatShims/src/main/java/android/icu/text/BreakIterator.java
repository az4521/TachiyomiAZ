package android.icu.text;

import java.text.CharacterIterator;
import java.util.Locale;

/** Adapter from Android ICU break iteration to java.text.BreakIterator. */
public class BreakIterator implements Cloneable {
    public static final int DONE = -1;
    public static final int WORD_NONE = 0;
    public static final int WORD_NUMBER = 100;
    public static final int WORD_LETTER = 200;
    public static final int WORD_KANA = 300;
    public static final int WORD_IDEO = 400;

    private final java.text.BreakIterator delegate;
    private final boolean wordIterator;

    private BreakIterator(java.text.BreakIterator delegate, boolean wordIterator) {
        this.delegate = delegate;
        this.wordIterator = wordIterator;
    }

    public static BreakIterator getWordInstance() {
        return getWordInstance(Locale.getDefault());
    }

    public static BreakIterator getWordInstance(Locale locale) {
        return new BreakIterator(java.text.BreakIterator.getWordInstance(locale), true);
    }

    public static BreakIterator getCharacterInstance() {
        return new BreakIterator(java.text.BreakIterator.getCharacterInstance(), false);
    }

    public int first() { return delegate.first(); }
    public int next() { return delegate.next(); }
    public int current() { return delegate.current(); }
    public void setText(CharacterIterator text) { delegate.setText(text); }

    public int getRuleStatus() {
        if (!wordIterator || delegate.current() == DONE) {
            return WORD_NONE;
        }
        CharacterIterator text = delegate.getText();
        int end = delegate.current();
        int start = delegate.previous();
        delegate.following(start);
        if (start == DONE || end <= start) {
            return WORD_NONE;
        }
        for (int index = start; index < end; index++) {
            char value = text.setIndex(index);
            if (Character.isDigit(value)) return WORD_NUMBER;
            if (Character.UnicodeBlock.of(value) == Character.UnicodeBlock.HIRAGANA ||
                Character.UnicodeBlock.of(value) == Character.UnicodeBlock.KATAKANA) return WORD_KANA;
            if (Character.isLetter(value)) return WORD_LETTER;
        }
        return WORD_NONE;
    }
}
