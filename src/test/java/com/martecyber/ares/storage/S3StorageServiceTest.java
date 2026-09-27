package com.martecyber.ares.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pure Mockito unit test for {@link S3StorageService} — the bucket-creation retry logic here
 *  used to live directly in each caller (e.g. ReferenceService) before the StorageService
 *  abstraction was introduced; this preserves that original coverage in its new home. */
class S3StorageServiceTest {

    private S3Client s3;
    private S3StorageService storage;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        storage = new S3StorageService(s3);
    }

    @Test
    void ensureBucketExistsDoesNothingWhenTheBucketAlreadyExists() {
        storage.ensureBucketExists("ares-favicons");
        verify(s3).headBucket(any(HeadBucketRequest.class));
        verify(s3, never()).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void ensureBucketExistsCreatesTheBucketWhenMissing() {
        when(s3.headBucket(any(HeadBucketRequest.class))).thenThrow(NoSuchBucketException.builder().build());
        storage.ensureBucketExists("ares-favicons");
        verify(s3).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void ensureBucketExistsSwallowsOtherFailures() {
        when(s3.headBucket(any(HeadBucketRequest.class))).thenThrow(new RuntimeException("boom"));
        assertDoesNotThrow(() -> storage.ensureBucketExists("ares-favicons"));
        verify(s3, never()).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void getReturnsTheObjectBytes() {
        byte[] bytes = {1, 2, 3};
        @SuppressWarnings("unchecked")
        ResponseBytes<GetObjectResponse> respBytes = mock(ResponseBytes.class);
        when(respBytes.asByteArray()).thenReturn(bytes);
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(respBytes);

        assertArrayEquals(bytes, storage.get("bucket", "key"));
    }

    @Test
    void putSendsBytesAndContentType() {
        storage.put("bucket", "key", "image/png", new byte[]{1, 2, 3});
        verify(s3).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test
    void deleteRemovesTheObject() {
        storage.delete("bucket", "key");
        verify(s3).deleteObject(any(DeleteObjectRequest.class));
    }
}
