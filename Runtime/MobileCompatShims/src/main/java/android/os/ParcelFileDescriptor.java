package android.os;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

/** File-backed subset used by extensions that pass PDFs to PdfRenderer. */
public class ParcelFileDescriptor implements Parcelable, Closeable {
    public static final int MODE_READ_ONLY = 0x10000000;
    public static final int MODE_WRITE_ONLY = 0x20000000;
    public static final int MODE_READ_WRITE = 0x30000000;
    public static final int MODE_CREATE = 0x08000000;
    public static final int MODE_TRUNCATE = 0x04000000;
    public static final int MODE_APPEND = 0x02000000;

    public static final Parcelable.Creator<ParcelFileDescriptor> CREATOR = null;

    private final File file;
    private final RandomAccessFile access;

    private ParcelFileDescriptor(File file, int mode) throws FileNotFoundException {
        this.file = file.getAbsoluteFile();
        boolean writable = (mode & MODE_WRITE_ONLY) != 0;
        if (writable && (mode & MODE_CREATE) != 0) {
            File parent = this.file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
        }
        this.access = new RandomAccessFile(this.file, writable ? "rw" : "r");
        try {
            if (writable && (mode & MODE_TRUNCATE) != 0) {
                access.setLength(0);
            }
            if (writable && (mode & MODE_APPEND) != 0) {
                access.seek(access.length());
            }
        } catch (IOException error) {
            try {
                access.close();
            } catch (IOException ignored) {
            }
            FileNotFoundException failure = new FileNotFoundException(error.getMessage());
            failure.initCause(error);
            throw failure;
        }
    }

    public ParcelFileDescriptor(ParcelFileDescriptor source) throws FileNotFoundException {
        this(source.file, MODE_READ_ONLY);
    }

    public static ParcelFileDescriptor open(File file, int mode)
        throws FileNotFoundException {
        if (file == null) {
            throw new NullPointerException("file");
        }
        return new ParcelFileDescriptor(file, mode);
    }

    public FileDescriptor getFileDescriptor() {
        try {
            return access.getFD();
        } catch (IOException error) {
            throw new IllegalStateException("File descriptor is closed", error);
        }
    }

    public long getStatSize() {
        return file.length();
    }

    public File getFile() {
        return file;
    }

    @Override
    public void close() throws IOException {
        access.close();
    }

    @Override
    public int describeContents() {
        return CONTENTS_FILE_DESCRIPTOR;
    }

    @Override
    public void writeToParcel(Parcel destination, int flags) {
        throw new UnsupportedOperationException("Parcel transport is not available on iOS");
    }

    @Override
    public String toString() {
        return "ParcelFileDescriptor{" + file + "}";
    }
}
