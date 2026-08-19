package android.webkit;

/** Concrete render-process failure detail supplied by WKWebView. */
public final class IOSRenderProcessGoneDetail extends RenderProcessGoneDetail {
    private final boolean crashed;
    private final int priority;

    public IOSRenderProcessGoneDetail(boolean crashed, int priority) {
        this.crashed = crashed;
        this.priority = priority;
    }

    @Override public boolean didCrash() { return crashed; }
    @Override public int rendererPriorityAtExit() { return priority; }
}
