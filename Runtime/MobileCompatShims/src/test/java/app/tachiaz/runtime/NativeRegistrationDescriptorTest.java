package app.tachiaz.runtime;

import app.tachiaz.compat.NativeBridge;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps the Java native declarations and RegisterNatives table identical. */
public final class NativeRegistrationDescriptorTest {
    private static final Pattern REGISTRATION = Pattern.compile(
        "\\{const_cast<char \\*>\\(\"([^\"]+)\"\\), " +
        "const_cast<char \\*>\\(\"([^\"]+)\"\\)"
    );

    private NativeRegistrationDescriptorTest() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected native bridge source path");
        }
        String source = new String(
            Files.readAllBytes(Paths.get(arguments[0])),
            StandardCharsets.UTF_8
        );
        Map<String, String> registered = new LinkedHashMap<String, String>();
        Matcher matcher = REGISTRATION.matcher(source);
        while (matcher.find()) {
            registered.put(matcher.group(1), matcher.group(2));
        }

        Map<String, String> declared = new LinkedHashMap<String, String>();
        for (Method method : NativeBridge.class.getDeclaredMethods()) {
            if (Modifier.isNative(method.getModifiers())) {
                declared.put(method.getName(), descriptor(method));
            }
        }
        if (!declared.equals(registered)) {
            throw new AssertionError(
                "Native bridge descriptors differ. Java=" + declared +
                ", C++=" + registered
            );
        }
        System.out.println("Native registration descriptor test passed");
    }

    private static String descriptor(Method method) {
        StringBuilder result = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) {
            result.append(descriptor(parameter));
        }
        return result.append(')')
            .append(descriptor(method.getReturnType()))
            .toString();
    }

    private static String descriptor(Class<?> type) {
        if (type.isArray()) {
            return type.getName().replace('.', '/');
        }
        if (!type.isPrimitive()) {
            return "L" + type.getName().replace('.', '/') + ";";
        }
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        throw new AssertionError("Unsupported primitive " + type);
    }
}
