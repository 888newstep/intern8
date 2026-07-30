package vip.xiaozhao.intern.baseUtil.intf.utils.cos;


import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.*;
import com.qcloud.cos.region.Region;
import com.tencent.cloud.CosStsClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import vip.xiaozhao.intern.baseUtil.intf.constant.COSConstant;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Date;
import java.util.TreeMap;

/**
 * @author allen
 * 腾讯云COS服务封装
 */
@Slf4j
public class COSUtils {

    static COSClient cosClient;//外网client
    static COSCredentials cred;

    static {

        cred = new BasicCOSCredentials(COSConstant.accessKeyId, COSConstant.accessKeySecret);
        Region region = new Region("ap-shanghai");
        ClientConfig clientConfig = new ClientConfig(region);
        clientConfig.setHttpProtocol(HttpProtocol.https);

        cosClient = new COSClient(cred, clientConfig);
    }

    public static void ensureBucket(COSClient client, String bucketName) {
        if (client.doesBucketExist(bucketName)) {
            return;
        }
        CreateBucketRequest createBucketRequest = new CreateBucketRequest(bucketName);
        createBucketRequest.setCannedAcl(CannedAccessControlList.Private);
        cosClient.createBucket(createBucketRequest);
    }

    /**
     * 内网上传文件到oss
     *
     * @param file
     * @param bucketName
     * @param fileName
     * @return boolean true:上传成功  false:上传失败
     */
    public static boolean uploadFile(File file, String bucketName, String fileName) {
        if (StringUtils.isEmpty(bucketName) || StringUtils.isEmpty(fileName)) {
            log.info("empty bucketName or fileName");
            return false;
        }
        try {
            ensureBucket(cosClient, bucketName);
            log.info("now begin upload");
            PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, fileName, file);
            cosClient.putObject(putObjectRequest);
            log.info("upload end");
        } catch (Exception e) {
            log.error("upload Error", e);
            return false;
        }
        return true;
    }


    public static String accessFile(String bucketName, String fileName) {
        if (StringUtils.isEmpty(bucketName) || StringUtils.isEmpty(fileName)) {
            log.info("empty bucketName or fileName");
            return null;
        }
        try {
            Date expiration = new Date(new Date().getTime() + 60 * 15 * 1000);
            // 生成以GET方法访问的签名URL，访客可以直接通过浏览器访问相关内容。
            URL url = cosClient.generatePresignedUrl(bucketName, fileName, expiration);
            log.info(url.toString());
            // 关闭OSSClient。
            cosClient.shutdown();
            String[] s = url.toString().split("\\?");
            return s[1];
        } catch (Exception e) {
            log.error("access Error", e);
            return null;
        }


    }


    /**
     * 删除文件
     *
     * @param bucketName
     * @param fileName
     */
    public static void deleteObject(String bucketName, String fileName) {
        try {
            cosClient.deleteObject(bucketName, fileName);
        } catch (Exception e) {
            log.error("Object delete failed", e);
        }
        log.info("Object delete success");
    }

    /**
     * 直接从cos读取object 到文件
     *
     * @param bucket
     * @param object
     * @param fileName 应用服务器的存储文件
     * @return
     */
    public static File getObject(String bucket, String object, String fileName) {
        File file = new File(fileName);
        GetObjectRequest getObjectRequest = new GetObjectRequest(bucket, object);
        cosClient.getObject(getObjectRequest, file);
        return file;
    }


    public static String geneSignedUrl(String bucketName, String objectName) {
        // 设置URL过期时间为5分钟
        Date expiration = new Date(new Date().getTime() + 60 * 5 * 1000);
        return geneSignedUrl(bucketName, objectName, expiration);

    }


    /**
     * 生成Plubload直接上传cos的参数信息
     *
     * @return
     */
    public static JSONObject genCOSPlubParams() throws IOException {
        TreeMap<String, Object> config = new TreeMap<String, Object>();

        config.put("secretId", COSConstant.accessKeyId);
        config.put("secretKey", COSConstant.accessKeySecret);
        // 临时密钥有效时长，单位是秒，默认 1800 秒，目前主账号最长 2 小时（即 7200 秒），子账号最长 36 小时（即 129600）秒
        config.put("durationSeconds", 600);
        // 换成您的 bucket
        config.put("bucket", COSConstant.mainBucket);
        // 换成 bucket 所在地区
        config.put("region", "ap-shanghai");
        config.put("allowPrefix", "*");
        // 密钥的权限列表。必须在这里指定本次临时密钥所需要的权限。
        // 简单上传、表单上传和分块上传需要以下的权限，其他权限列表请看 https://cloud.tencent.com/document/product/436/31923
        String[] allowActions = new String[]{
                // 简单上传
                "name/cos:PutObject",
                // 表单上传、小程序上传
                "name/cos:PostObject",
//                // 分块上传
//                "name/cos:InitiateMultipartUpload",
//                "name/cos:ListMultipartUploads",
//                "name/cos:ListParts",
//                "name/cos:UploadPart",
//                "name/cos:CompleteMultipartUpload"
        };
        config.put("allowActions", allowActions);

        JSONObject credential = CosStsClient.getCredential(config);
        log.info(credential.toString());
       return  credential;
    }

    public static String geneSignedUrl(String bucketName, String objectName, Date expiration) {
        return cosClient.generatePresignedUrl(bucketName, objectName, expiration).toString();
    }





    public static void main(String[] args) {

    }
}
