package android.os;

public final class Build {
    public static final String BOARD = "iOS";
    public static final String BRAND = "Apple";
    public static final String DEVICE = "iPhone";
    public static final String DISPLAY = "TachiyomiAZ";
    public static final String ID = "TachiyomiAZ-iOS";
    public static final String MANUFACTURER = "Apple";
    public static final String MODEL = "iPhone";
    public static final String PRODUCT = "TachiyomiAZ";

    private Build() {
    }

    public static final class VERSION {
        public static final String RELEASE = "15";
        public static final int SDK_INT = 35;

        private VERSION() {
        }
    }

    public static final class VERSION_CODES {
        public static final int O = 26;
        public static final int P = 28;
        public static final int Q = 29;
        public static final int R = 30;
        public static final int S = 31;
        public static final int TIRAMISU = 33;
        public static final int UPSIDE_DOWN_CAKE = 34;
        public static final int VANILLA_ICE_CREAM = 35;

        private VERSION_CODES() {
        }
    }
}
