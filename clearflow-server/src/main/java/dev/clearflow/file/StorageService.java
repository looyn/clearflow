package dev.clearflow.file;

import dev.clearflow.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * RustFS 对象存储（S3 协议兼容）：上传 / 预签名读取 / 删除
 * 连接信息读取自 application.yml 的 clearflow.rustfs 配置
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;
    private final long presignExpiryMinutes;
    private final long maxUploadBytes;

    public StorageService(@Value("${clearflow.rustfs.endpoint}") String endpoint,
                          @Value("${clearflow.rustfs.access-key}") String accessKey,
                          @Value("${clearflow.rustfs.secret-key}") String secretKey,
                          @Value("${clearflow.rustfs.bucket}") String bucket,
                          @Value("${clearflow.rustfs.region:us-east-1}") String region,
                          @Value("${clearflow.rustfs.presign-expiry-minutes:60}") long presignExpiryMinutes,
                          @Value("${clearflow.rustfs.max-upload-mb:20}") long maxUploadMb) {
        this.bucket = bucket;
        this.presignExpiryMinutes = presignExpiryMinutes;
        this.maxUploadBytes = maxUploadMb * 1024 * 1024;

        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
        StaticCredentialsProvider provider = StaticCredentialsProvider.create(credentials);
        // chunkedEncodingEnabled=false：关闭 aws-chunked 传输编码（RustFS 对归档对象默认拒绝该编码，上传报 400）
        S3Configuration conf = S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build();

        this.client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(provider)
                .serviceConfiguration(conf)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(provider)
                .serviceConfiguration(conf)
                .build();
    }

    /** 上传文件，返回存储 key（附件记录保存该 key，读取时动态预签名） */
    public StoredFile upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.bad("请选择要上传的文件");
        }
        if (file.getSize() > maxUploadBytes) {
            throw BizException.bad("文件超过大小限制");
        }
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String key = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8)
                + "-" + original.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        try {
            client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(file.getContentType() == null ? "application/octet-stream" : file.getContentType())
                            .build(),
                    RequestBody.fromBytes(file.getBytes()));
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
        return new StoredFile(key, original, file.getSize());
    }

    /** 预签名临时读取 URL（私有桶）；失败返回 null 由调用方回退 */
    public String presign(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(presignExpiryMinutes))
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .build();
            return presigner.presignGetObject(request).url().toString();
        } catch (Exception e) {
            log.warn("预签名失败 key={}：{}", key, e.getMessage());
            return null;
        }
    }

    /** 读取对象字节与类型（图片直出用）；失败返回 null 由调用方回 404 */
    public StoredObject read(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            ResponseBytes<GetObjectResponse> bytes = client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return new StoredObject(bytes.asByteArray(), bytes.response().contentType());
        } catch (Exception e) {
            log.warn("读取对象失败 key={}：{}", key, e.getMessage());
            return null;
        }
    }

    /** 删除对象（失败仅记日志，不阻断业务） */
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception e) {
            log.warn("删除对象失败 key={}：{}", key, e.getMessage());
        }
    }

    public record StoredFile(String key, String name, long size) {
    }

    public record StoredObject(byte[] bytes, String contentType) {
    }
}
