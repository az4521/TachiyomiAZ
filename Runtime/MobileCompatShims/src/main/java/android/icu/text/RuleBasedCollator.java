package android.icu.text;

/** Configurable collator compatible with the Android ICU surface extensions use. */
public final class RuleBasedCollator extends Collator {
    private boolean caseLevel;

    RuleBasedCollator(java.text.Collator delegate) {
        super(delegate);
    }

    public RuleBasedCollator(String rules) throws Exception {
        super(new java.text.RuleBasedCollator(rules));
    }

    public void setCaseLevel(boolean enabled) {
        caseLevel = enabled;
    }

    public boolean isCaseLevel() {
        return caseLevel;
    }
}
