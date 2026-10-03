package com.martecyber.ares.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@ConditionalOnProperty(name = "ares.storage.type", havingValue = "local")
public class LocalFilesystemStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalFilesystemStorageService.class);

    @Value("${ares.storage.local.root-dir}")
    private String rootDir;

    @Override
    public void ensureBucketExists(String bucket) {
        try {
            Files.createDirectories(bucketDir(bucket));
        } catch (IOException e) {
            log.warn("Could not create local storage directory for bucket '{}': {}", bucket, e.getMessage());
        }
    }

    @Override
    public boolean exists(String bucket, String key) {
        return Files.exists(objectPath(bucket, key));
    }

    @Override
    public void put(String bucket, String key, String contentType, byte[] content) {
        try {
            Path path = objectPath(bucket, key);
            Files.createDirectories(path.getParent());
            Files.write(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] get(String bucket, String key) {
        try {
            return Files.readAllBytes(objectPath(bucket, key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String bucket, String key) {
        try {
            Files.deleteIfExists(objectPath(bucket, key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path bucketDir(String bucket) {
        return Paths.get(rootDir, bucket).normalize();
    }

    // Object keys are always server-generated (UUIDs, timestamps, ids) at every call site, never
    // raw user input — this normalization/containment check is defense in depth, not a response
    // to a known attacker-controlled path.
    private Path objectPath(String bucket, String key) {
        Path base = bucketDir(bucket);
        Path resolved = base.resolve(key).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Invalid object key: " + key);
        }
        return resolved;
    }
}
