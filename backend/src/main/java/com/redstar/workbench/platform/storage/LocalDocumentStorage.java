package com.redstar.workbench.platform.storage;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import com.redstar.workbench.foundation.DocumentStorage;
import com.redstar.workbench.foundation.StorageKey;

public final class LocalDocumentStorage implements DocumentStorage {

    private final Path root;

    public LocalDocumentStorage(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    @Override
    public void store(StorageKey key, InputStream content) throws IOException {
        Objects.requireNonNull(content, "content");
        var target = resolve(key);
        createDirectoriesWithoutFollowingLinks(target.getParent());
        rejectSymbolicLinks(target);
        try (var output = Files.newOutputStream(target, CREATE_NEW, WRITE)) {
            content.transferTo(output);
        }
    }

    @Override
    public InputStream open(StorageKey key) throws IOException {
        var target = resolve(key);
        rejectSymbolicLinks(target);
        return Files.newInputStream(target);
    }

    private Path resolve(StorageKey key) {
        var target = root.resolve(Objects.requireNonNull(key, "key").value()).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Storage key escapes the configured root");
        }
        return target;
    }

    private void createDirectoriesWithoutFollowingLinks(Path parent) throws IOException {
        Files.createDirectories(root);
        rejectSymbolicLink(root);

        var current = root;
        for (var segment : root.relativize(parent)) {
            current = current.resolve(segment);
            if (Files.notExists(current, NOFOLLOW_LINKS)) {
                Files.createDirectory(current);
            }
            rejectSymbolicLink(current);
            if (!Files.isDirectory(current, NOFOLLOW_LINKS)) {
                throw new IOException("Storage path component is not a directory");
            }
        }
    }

    private void rejectSymbolicLinks(Path target) throws IOException {
        var current = root;
        rejectSymbolicLink(current);
        for (var segment : root.relativize(target)) {
            current = current.resolve(segment);
            rejectSymbolicLink(current);
        }
    }

    private void rejectSymbolicLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Storage path must not contain symbolic links");
        }
    }
}
