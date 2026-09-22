package com.rivell.workbench.foundation;

import java.io.IOException;
import java.io.InputStream;

public interface DocumentStorage {

    void store(StorageKey key, InputStream content) throws IOException;

    InputStream open(StorageKey key) throws IOException;

    long size(StorageKey key) throws IOException;

    void delete(StorageKey key) throws IOException;
}
