package app.tachiaz.runtime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Proves that Android's application looper exists and processes callbacks. */
public final class AndroidMainLooperTest {
    private AndroidMainLooperTest() {
    }

    public static void main(String[] arguments) throws Exception {
        Method startMainLooper = ExtensionHost.class.getDeclaredMethod(
            "startAndroidMainLooper",
            ClassLoader.class
        );
        startMainLooper.setAccessible(true);
        startMainLooper.invoke(null, ExtensionHost.class.getClassLoader());

        Looper mainLooper = Looper.getMainLooper();
        if (mainLooper == null) {
            throw new AssertionError("Android main looper is null");
        }

        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Object contextObject = unsafeType
            .getMethod("allocateInstance", Class.class)
            .invoke(
                unsafe,
                Class.forName(
                    "xyz.nulldev.androidcompat.androidimpl.CustomContext"
                )
            );
        Context context = (Context) contextObject;
        if (context.getMainLooper() != mainLooper) {
            throw new AssertionError(
                "Application context did not expose the Android main looper"
            );
        }

        CountDownLatch callback = new CountDownLatch(1);
        if (!new Handler(mainLooper).post(callback::countDown)) {
            throw new AssertionError("Android main looper rejected a callback");
        }
        if (!callback.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError(
                "Android main looper did not process a callback"
            );
        }

        System.out.println("Android main looper compatibility test passed");
    }
}
