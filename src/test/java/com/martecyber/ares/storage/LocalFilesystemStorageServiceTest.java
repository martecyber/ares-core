package com.martecyber.ares.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFilesystemStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalFilesystemStorageService storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFilesystemStorageService();
        ReflectionTestUtils.setField(storage, "rootDir", tempDir.toString());
    }

    @Test
    void putThenGetRoundTripsTheBytes() {
        storage.put("evidence", "org/1/file.txt", "text/plain", "hello".getBytes());
        assertArrayEquals("hello".getBytes(), storage.get("evidence", "org/1/file.txt"));
    }

    @Test
    void putCreatesParentDirectoriesAsNeeded() {
        storage.put("evidence", "a/b/c/file.txt", "text/plain", "x".getBytes());
        assertTrue(Files.exists(tempDir.resolve("evidence/a/b/c/file.txt")));
    }

    @Test
    void deleteRemovesTheFile() {
        storage.put("evidence", "file.txt", "text/plain", "x".getBytes());
        storage.delete("evidence", "file.txt");
        assertFalse(Files.exists(tempDir.resolve("evidence/file.txt")));
    }

    @Test
    void deleteOfAMissingFileIsANoOp() {
        storage.delete("evidence", "never-existed.txt");
    }

    @Test
    void ensureBucketExistsCreatesTheDirectory() {
        storage.ensureBucketExists("evidence");
        assertTrue(Files.isDirectory(tempDir.resolve("evidence")));
    }

    @Test
    void objectKeyCannotEscapeTheBucketDirectory() {
        assertThrows(IllegalArgumentException.class,
            () -> storage.put("evidence", "../../etc/passwd", "text/plain", "x".getBytes()));
    }
}
