package vip.xiaozhao.intern.baseUtil.intf.constant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class COSConstant {

    public static String accessKeyId;
    public static String accessKeySecret;
    public static String mainBucket;
    public static String COS_HOST;


    @Value("${cos.accessKeyId}")
    public  void setAccessKeyId(String accessKeyId) {
        COSConstant.accessKeyId = accessKeyId;
    }
    @Value("${cos.accessKeySecret}")
    public  void setAccessKeySecret(String accessKeySecret) {
        COSConstant.accessKeySecret = accessKeySecret;
    }
    @Value("${cos.mainBucket}")
    public  void setMainBucket(String mainBucket) {
        COSConstant.mainBucket = mainBucket;
    }
    @Value("${cos.HostName}")
    public  void setCosHost(String cosHost) {
        COS_HOST = cosHost;
    }



}
