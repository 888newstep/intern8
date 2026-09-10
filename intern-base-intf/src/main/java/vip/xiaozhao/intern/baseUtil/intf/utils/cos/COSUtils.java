package vip.xiaozhao.intern.baseUtil.intf.utils.cos;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.CreateBucketRequest;
import com.qcloud.cos.model.DeleteObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import com.tencent.cloud.CosStsClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import vip.xiaozhao.intern.baseUtil.intf.constant.COSConstant;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Date;
import java.util.TreeMap;

/** 腾讯云 COS 客户端封装。客户端采用延迟初始化，避免静态类加载早于 Spring 配置注入。 */
@Slf4j
public final class COSUtils {

    private static volatile COSClient cosClient;
    private static volatile COSCredentials credentials;

    private COSUtils() {
    }

    public static void ensureBucket(COSClient client, String bucketName) {
        if (client == null || StringUtils.isBlank(bucketName)) {
            throw new IllegalArgumentException("COS client and bucket name are required");
        }
        if (client.doesBucketExist(bucketName)) {
            return;
        }
        CreateBucketRequest request = new CreateBucketRequest(bucketName);
        request.setCannedAcl(CannedAccessControlList.Private);
        client.createBucket(request);
    }

    /** 直接上传请求流，避免把用户输入映射为本地文件路径。 */
    public static void uploadStreamOrThrow(InputStream inputStream, long contentLength,
                                           String contentType, String bucketName,
                                           String objectName) {
        if (inputStream == null || contentLength <= 0) {
            throw new IllegalArgumentException("COS upload stream is empty");
        }
        requireObjectPath(bucketName, objectName);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(contentLength);
        if (StringUtils.isNotBlank(contentType)) {
            metadata.setContentType(contentType);
        }
        COSClient client = client();
        ensureBucket(client, bucketName);
        client.putObject(new PutObjectRequest(bucketName, objectName, inputStream, metadata));
    }

    public static String accessFile(String bucketName, String fileName) {
        requireObjectPath(bucketName, fileName);
        Date expiration = new Date(System.currentTimeMillis() + 15 * 60 * 1000L);
        URL url = client().generatePresignedUrl(bucketName, fileName, expiration);
        String query = url.getQuery();
        return query == null ? url.toString() : query;
    }

    /** 保留旧的 void API，失败时抛出异常，避免控制器误返回成功。 */
    public static void deleteObject(String bucketName, String fileName) {
        deleteObjectOrThrow(bucketName, fileName);
    }

    public static void deleteObjectOrThrow(String bucketName, String fileName) {
        requireObjectPath(bucketName, fileName);
        client().deleteObject(new DeleteObjectRequest(bucketName, fileName));
    }

    public static String geneSignedUrl(String bucketName, String objectName) {
        Date expiration = new Date(System.currentTimeMillis() + 5 * 60 * 1000L);
        return geneSignedUrl(bucketName, objectName, expiration);
    }

    public static JSONObject genCOSPlubParams(Long userId) throws IOException {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("COS upload user is required");
        }
        requireCredentials();
        TreeMap<String, Object> config = new TreeMap<>();
        config.put("secretId", COSConstant.accessKeyId);
        config.put("secretKey", COSConstant.accessKeySecret);
        config.put("durationSeconds", 600);
        config.put("bucket", COSConstant.mainBucket);
        config.put("region", region());
        config.put("allowPrefix", "uploads/" + userId + "/*");
        config.put("allowActions", new String[]{
                "name/cos:PutObject",
                "name/cos:PostObject"
        });
        return CosStsClient.getCredential(config);
    }

    public static String geneSignedUrl(String bucketName, String objectName, Date expiration) {
        requireObjectPath(bucketName, objectName);
        if (expiration == null || !expiration.after(new Date())) {
            throw new IllegalArgumentException("COS URL expiration must be in the future");
        }
        return client().generatePresignedUrl(bucketName, objectName, expiration).toString();
    }

    /** 测试和优雅停机使用，生产业务不应在单次请求中关闭共享客户端。 */
    public static void shutdown() {
        COSClient client = cosClient;
        if (client != null) {
            synchronized (COSUtils.class) {
                client = cosClient;
                if (client != null) {
                    client.shutdown();
                    cosClient = null;
                    credentials = null;
                }
            }
        }
    }

    private static COSClient client() {
        COSClient current = cosClient;
        if (current != null) {
            return current;
        }
        synchronized (COSUtils.class) {
            current = cosClient;
            if (current == null) {
                requireCredentials();
                ClientConfig config = new ClientConfig(new Region(region()));
                config.setHttpProtocol(HttpProtocol.https);
                config.setConnectionTimeout(positiveOrDefault(COSConstant.connectionTimeoutMs, 5_000));
                config.setSocketTimeout(positiveOrDefault(COSConstant.socketTimeoutMs, 10_000));
                config.setConnectionRequestTimeout(
                        positiveOrDefault(COSConstant.connectionRequestTimeoutMs, 3_000));
                config.setMaxConnectionsCount(positiveOrDefault(COSConstant.maxConnections, 64));
                config.setMaxErrorRetry(nonNegativeOrDefault(COSConstant.maxErrorRetry, 2));
                credentials = new BasicCOSCredentials(
                        COSConstant.accessKeyId, COSConstant.accessKeySecret);
                current = new COSClient(credentials, config);
                cosClient = current;
            }
            return current;
        }
    }

    private static void requireObjectPath(String bucketName, String objectName) {
        if (StringUtils.isBlank(bucketName) || StringUtils.isBlank(objectName)) {
            throw new IllegalArgumentException("COS bucket and object name are required");
        }
    }

    private static void requireCredentials() {
        if (StringUtils.isBlank(COSConstant.accessKeyId)
                || StringUtils.isBlank(COSConstant.accessKeySecret)) {
            throw new IllegalStateException("COS credentials are not configured");
        }
    }

    private static String region() {
        return StringUtils.defaultIfBlank(COSConstant.region, "ap-guangzhou");
    }

    private static int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private static int nonNegativeOrDefault(int value, int defaultValue) {
        return value >= 0 ? value : defaultValue;
    }
}
