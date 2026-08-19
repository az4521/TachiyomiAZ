package android.app;

public class ActivityManager {
    public ActivityManager() {
    }

    public void getMemoryInfo(MemoryInfo output) {
        if (output == null) {
            throw new NullPointerException("output");
        }
        Runtime runtime = Runtime.getRuntime();
        output.totalMem = runtime.maxMemory();
        output.availMem = runtime.freeMemory() +
            (runtime.maxMemory() - runtime.totalMemory());
        output.threshold = Math.min(output.totalMem / 10, 256L * 1024 * 1024);
        output.lowMemory = output.availMem < output.threshold;
    }

    public boolean isLowRamDevice() {
        MemoryInfo info = new MemoryInfo();
        getMemoryInfo(info);
        return info.totalMem < 3L * 1024 * 1024 * 1024;
    }

    public static class MemoryInfo {
        public long availMem;
        public long totalMem;
        public long threshold;
        public boolean lowMemory;

        public MemoryInfo() {
        }
    }
}
