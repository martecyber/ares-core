package com.martecyber.ares.storage;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

@Service
@ConditionalOnProperty(name = "ares.storage.type", havingValue = "azure")
public class AzureBlobStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(AzureBlobStorageService.class);

    private final BlobServiceClient serviceClient;

    public AzureBlobStorageService(@Value("${ares.storage.azure.connection-string}") String connectionString) {
        this.serviceClient = new BlobServiceClientBuilder()
            .connectionString(connectionString)
            .buildClient();
    }

    @Override
    public void ensureBucketExists(String bucket) {
        try {
            var container = serviceClient.getBlobContainerClient(bucket);
            if (!container.exists()) {
                log.info("Azure container '{}' not found, creating it…", bucket);
                container.create();
            }
        } catch (Exception e) {
            log.warn("Could not verify/create Azure container '{}': {}", bucket, e.getMessage());
        }
    }

    @Override
    public boolean exists(String bucket, String key) {
        return serviceClient.getBlobContainerClient(bucket).getBlobClient(key).exists();
    }

    @Override
    public void put(String bucket, String key, String contentType, byte[] content) {
        BlobClient blob = serviceClient.getBlobContainerClient(bucket).getBlobClient(key);
        blob.upload(new ByteArrayInputStream(content), content.length, true);
        if (contentType != null) {
            blob.setHttpHeaders(new BlobHttpHeaders().setContentType(contentType));
        }
    }

    @Override
    public byte[] get(String bucket, String key) {
        BlobClient blob = serviceClient.getBlobContainerClient(bucket).getBlobClient(key);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        blob.downloadStream(out);
        return out.toByteArray();
    }

    @Override
    public void delete(String bucket, String key) {
        serviceClient.getBlobContainerClient(bucket).getBlobClient(key).deleteIfExists();
    }
}
