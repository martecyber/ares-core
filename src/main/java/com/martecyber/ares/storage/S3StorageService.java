package com.martecyber.ares.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@ConditionalOnProperty(name = "ares.storage.type", havingValue = "s3", matchIfMissing = true)
public class S3StorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);

    private final S3Client s3;

    public S3StorageService(S3Client s3) {
        this.s3 = s3;
    }

    @Override
    public void ensureBucketExists(String bucket) {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            log.info("S3 bucket '{}' not found, creating it…", bucket);
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (Exception e) {
            log.warn("Could not verify/create S3 bucket '{}': {}", bucket, e.getMessage());
        }
    }

    @Override
    public boolean exists(String bucket, String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public void put(String bucket, String key, String contentType, byte[] content) {
        s3.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
            RequestBody.fromBytes(content)
        );
    }

    @Override
    public byte[] get(String bucket, String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    @Override
    public void delete(String bucket, String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
