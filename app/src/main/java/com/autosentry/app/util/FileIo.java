package com.autosentry.app.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Plain stream file helpers. java.nio.file.Files needs Android 8 (API 26), and this app
 * still installs on Android 7, where calling it crashes instead of throwing an IOException.
 */
public final class FileIo {
    private FileIo() {}

    public static void copy(File from, OutputStream to) throws IOException {
        try (InputStream in = new FileInputStream(from)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) to.write(buffer, 0, n);
        }
    }

    public static void writeText(File file, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static String readText(File file) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        copy(file, bytes);
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
