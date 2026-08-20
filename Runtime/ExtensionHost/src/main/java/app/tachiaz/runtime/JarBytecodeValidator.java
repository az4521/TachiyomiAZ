package app.tachiaz.runtime;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

final class JarBytecodeValidator {
    private JarBytecodeValidator() {
    }

    static Report inspect(File jar) throws IOException {
        if (!jar.isFile()) {
            throw new IllegalArgumentException(
                "Extension JAR does not exist: " + jar
            );
        }

        boolean foundClass = false;
        int classCount = 0;
        int maximumMajorVersion = 0;
        try (
            JarInputStream input = new JarInputStream(
                new BufferedInputStream(new FileInputStream(jar))
            )
        ) {
            JarEntry entry;
            byte[] header = new byte[8];
            while ((entry = input.getNextJarEntry()) != null) {
                if (
                    entry.isDirectory() ||
                    !entry.getName().endsWith(".class")
                ) {
                    continue;
                }
                foundClass = true;
                classCount++;
                readFully(input, header);
                if (
                    (header[0] & 0xff) != 0xca ||
                    (header[1] & 0xff) != 0xfe ||
                    (header[2] & 0xff) != 0xba ||
                    (header[3] & 0xff) != 0xbe
                ) {
                    throw new IllegalArgumentException(
                        "Invalid class header in " + entry.getName()
                    );
                }
                int major = ((header[6] & 0xff) << 8) | (header[7] & 0xff);
                maximumMajorVersion = Math.max(maximumMajorVersion, major);
            }
        }

        if (!foundClass) {
            throw new IllegalArgumentException(
                "Extension JAR contains no classes"
            );
        }
        return new Report(classCount, maximumMajorVersion);
    }

    static Report validate(File jar) throws IOException {
        Report report = inspect(jar);
        int runtimeMajorVersion = runtimeClassVersion();
        if (report.maximumMajorVersion > runtimeMajorVersion) {
            throw new IllegalArgumentException(
                "Extension uses class-file version " +
                    report.maximumMajorVersion +
                    " (Java " +
                    javaVersionForClassVersion(report.maximumMajorVersion) +
                    "); this runtime supports at most " +
                    runtimeMajorVersion +
                    " (Java " +
                    javaVersionForClassVersion(runtimeMajorVersion) +
                    ")"
            );
        }
        return report;
    }

    static int runtimeClassVersion() {
        String value = System.getProperty("java.class.version", "52");
        int dot = value.indexOf('.');
        if (dot >= 0) {
            value = value.substring(0, dot);
        }
        return Integer.parseInt(value);
    }

    static int javaVersionForClassVersion(int classVersion) {
        return classVersion - 44;
    }

    private static void readFully(JarInputStream input, byte[] buffer)
        throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int count = input.read(buffer, offset, buffer.length - offset);
            if (count < 0) {
                throw new IOException("Truncated class file");
            }
            offset += count;
        }
    }

    static final class Report {
        final int classCount;
        final int maximumMajorVersion;

        Report(int classCount, int maximumMajorVersion) {
            this.classCount = classCount;
            this.maximumMajorVersion = maximumMajorVersion;
        }

        boolean isCompatibleWithCurrentRuntime() {
            return maximumMajorVersion <= runtimeClassVersion();
        }
    }
}
