package com.martecyber.ares.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

// Only needed when ares.storage.type=s3 (the default) — see com.martecyber.ares.storage for the
// StorageService abstraction this backs, and LocalFilesystemStorageService for the alternative
// that needs no external service at all.
@Configuration
@ConditionalOnProperty(name = "ares.storage.type", havingValue = "s3", matchIfMissing = true)
public class S3Config {

    @Value("${ares.storage.s3.endpoint}") private String endpoint;
    @Value("${ares.storage.s3.region}")   private String region;
    @Value("${ares.storage.s3.access-key}") private String accessKey;
    @Value("${ares.storage.s3.secret-key}") private String secretKey;
    @Value("${ares.storage.s3.path-style:true}") private boolean pathStyle;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey)))
            .forcePathStyle(pathStyle)
            .build();
    }
}
