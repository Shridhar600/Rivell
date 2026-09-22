package com.rivell.workbench.platform.storage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.rivell.workbench.foundation.DocumentStorage;
import com.rivell.workbench.foundation.StorageKey;

class LocalDocumentStorageTest {

    @TempDir
    Path root;

    @Test
    void storesAndReadsExactBytes() throws Exception {
        DocumentStorage storage = new LocalDocumentStorage(root);
        var key = new StorageKey("organizations/acme/contracts/source.docx");
        var expected = new byte[] {0, 1, 2, -1};

        storage.store(key, new ByteArrayInputStream(expected));

        try (var input = storage.open(key)) {
            assertThat(input.readAllBytes()).isEqualTo(expected);
        }
    }

    @Test
    void refusesToOverwriteExistingContent() throws Exception {
        DocumentStorage storage = new LocalDocumentStorage(root);
        var key = new StorageKey("one/source.docx");
        storage.store(key, new ByteArrayInputStream("first".getBytes(UTF_8)));

        assertThatThrownBy(() -> storage.store(key, new ByteArrayInputStream("second".getBytes(UTF_8))))
                .isInstanceOf(FileAlreadyExistsException.class);

        try (var input = storage.open(key)) {
            assertThat(input.readAllBytes()).isEqualTo("first".getBytes(UTF_8));
        }
    }

    @Test
    void reportsStoredContentSizeAndDeletesCompletely() throws Exception {
        DocumentStorage storage = new LocalDocumentStorage(root);
        var key = new StorageKey("one/source.docx");
        var expected = new byte[] {0, 1, 2, 3, 4};

        storage.store(key, new ByteArrayInputStream(expected));
        assertThat(storage.size(key)).isEqualTo(expected.length);

        storage.delete(key);
        assertThatThrownBy(() -> storage.open(key)).isInstanceOf(IOException.class);

        storage.delete(key); // deleting a missing key is not an error
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "../secret", "a/../secret", "/absolute", "C:/windows", "C:relative", "a\\windows"
    })
    void rejectsUnsafeLogicalKeys(String value) {
        assertThatThrownBy(() -> new StorageKey(value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsReadsAndWritesThroughSymlinkedDirectories(@TempDir Path outside) throws Exception {
        Files.writeString(outside.resolve("existing.docx"), "outside", UTF_8);
        Files.createSymbolicLink(root.resolve("linked"), outside);
        DocumentStorage storage = new LocalDocumentStorage(root);

        assertThatThrownBy(() -> storage.open(new StorageKey("linked/existing.docx")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> storage.store(
                new StorageKey("linked/new.docx"),
                new ByteArrayInputStream("new".getBytes(UTF_8))))
                .isInstanceOf(IOException.class);
        assertThat(outside.resolve("new.docx")).doesNotExist();
    }
}
