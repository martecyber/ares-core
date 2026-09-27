package com.martecyber.ares.storage;

/**
 * Blob storage abstraction — every call site only ever needs put/get/delete by bucket+key plus
 * on-demand bucket provisioning, so this stays deliberately narrow rather than mirroring the
 * full S3 API. Content type is tracked by callers in their own DB rows, not by the storage
 * backend, so it's accepted on write but never returned on read.
 */
public interface StorageService {

    void ensureBucketExists(String bucket);

    void put(String bucket, String key, String contentType, byte[] content);

    byte[] get(String bucket, String key);

    void delete(String bucket, String key);
}
