package vip.xiaozhao.intern.baseUtil.intf.constant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class COSConstant {

    public static String accessKeyId;
    public static String accessKeySecret;
    public static String mainBucket;
    public static String COS_HOST;
    public static String region;
    public static int connectionTimeoutMs;
    public static int socketTimeoutMs;
    public static int connectionRequestTimeoutMs;
    public static int maxConnections;
    public static int maxErrorRetry;


    @Value("${cos.accessKeyId}")
    public void setAccessKeyId(String accessKeyId) {
        COSConstant.accessKeyId = accessKeyId;
    }

    @Value("${cos.accessKeySecret}")
    public void setAccessKeySecret(String accessKeySecret) {
        COSConstant.accessKeySecret = accessKeySecret;
    }

    @Value("${cos.mainBucket}")
    public void setMainBucket(String mainBucket) {
        COSConstant.mainBucket = mainBucket;
    }

    @Value("${cos.HostName}")
    public void setCosHost(String cosHost) {
        COS_HOST = cosHost;
    }

    @Value("${cos.region}")
    public void setRegion(String region) {
        COSConstant.region = region;
    }

    @Value("${cos.connection-timeout-ms:5000}")
    public void setConnectionTimeoutMs(int connectionTimeoutMs) {
        COSConstant.connectionTimeoutMs = connectionTimeoutMs;
    }

    @Value("${cos.socket-timeout-ms:10000}")
    public void setSocketTimeoutMs(int socketTimeoutMs) {
        COSConstant.socketTimeoutMs = socketTimeoutMs;
    }

    @Value("${cos.connection-request-timeout-ms:3000}")
    public void setConnectionRequestTimeoutMs(int connectionRequestTimeoutMs) {
        COSConstant.connectionRequestTimeoutMs = connectionRequestTimeoutMs;
    }

    @Value("${cos.max-connections:64}")
    public void setMaxConnections(int maxConnections) {
        COSConstant.maxConnections = maxConnections;
    }

    @Value("${cos.max-error-retry:2}")
    public void setMaxErrorRetry(int maxErrorRetry) {
        COSConstant.maxErrorRetry = maxErrorRetry;
    }
}
