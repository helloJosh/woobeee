package com.woobeee.mvc.blog.support;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

public class ProgressInputStream extends FilterInputStream {

    private final long totalSize;
    private long bytesRead = 0;
    private final Consumer<Double> progressCallback;

    public ProgressInputStream(InputStream in, long totalSize, Consumer<Double> progressCallback) {
        super(in);
        this.totalSize = totalSize;
        this.progressCallback = progressCallback;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int read = super.read(b, off, len);
        if (read > 0) {
            bytesRead += read;
            double percent = (double) bytesRead / totalSize * 100;
            progressCallback.accept(percent);
        }
        return read;
    }

    @Override
    public int read() throws IOException {
        int byteRead = super.read();
        if (byteRead != -1) {
            bytesRead++;
            double percent = (double) bytesRead / totalSize * 100;
            progressCallback.accept(percent);
        }


        return byteRead;
    }
}
