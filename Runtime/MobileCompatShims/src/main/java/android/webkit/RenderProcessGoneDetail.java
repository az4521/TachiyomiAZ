package android.webkit;

/** Android API base class without the android.jar throwing stub constructor. */
public abstract class RenderProcessGoneDetail {
    protected RenderProcessGoneDetail() {
    }

    public abstract boolean didCrash();
    public abstract int rendererPriorityAtExit();
}
