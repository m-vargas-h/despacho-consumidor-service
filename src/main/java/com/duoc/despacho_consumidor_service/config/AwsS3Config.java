package com.duoc.despacho_consumidor_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class AwsS3Config {

    @Value("${aws.accessKeyId:dummy-access-key}")
    private String accessKeyId;

    @Value("${aws.secretAccessKey:dummy-secret-key}")
    private String secretAccessKey;

    @Value("${aws.sessionToken:}")
    private String sessionToken;

    @Value("${aws.region:us-east-1}")
    private String region;

    @Bean
    public S3Client s3Client() {
        StaticCredentialsProvider credentialsProvider;

        if (sessionToken != null && !sessionToken.isBlank()) {
            credentialsProvider = StaticCredentialsProvider.create(
                    AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken));
        } else {
            credentialsProvider = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey));
        }

        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider)
                .build();
    }
}
