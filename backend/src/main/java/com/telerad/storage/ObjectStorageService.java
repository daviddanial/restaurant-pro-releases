package com.telerad.storage;

import com.telerad.config.TeleradProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.InputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class ObjectStorageService {
    private final S3Client s3Client;
    private final TeleradProperties properties;

    public void put(String key, byte[] data, String contentType) {
        ensureBucket();
        s3Client.putObject(PutObjectRequest.builder()
                        .bucket(properties.getS3().getBucket())
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(data));
    }

    public void put(String key, InputStream stream, long size, String contentType) {
        ensureBucket();
        s3Client.putObject(PutObjectRequest.builder()
                        .bucket(properties.getS3().getBucket())
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromInputStream(stream, size));
    }

    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.getS3().getBucket())
                .key(key)
                .build());
    }

    public byte[] getBytes(String key) {
        var response = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(properties.getS3().getBucket())
                .key(key)
                .build());
        return response.asByteArray();
    }

    /** Open the object for streaming; the response carries its length. Caller must close it. */
    public ResponseInputStream<GetObjectResponse> getStream(String key) {
        return s3Client.getObject(GetObjectRequest.builder()
                .bucket(properties.getS3().getBucket())
                .key(key)
                .build());
    }

    public boolean exists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getS3().getBucket())
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }

    private void ensureBucket() {
        String bucket = properties.getS3().getBucket();
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            if (e.statusCode() == 404 || e.statusCode() == 403) {
                try {
                    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                    log.info("Created S3 bucket {}", bucket);
                } catch (Exception ignored) {
                    // race with another instance
                }
            }
        }
    }
}
