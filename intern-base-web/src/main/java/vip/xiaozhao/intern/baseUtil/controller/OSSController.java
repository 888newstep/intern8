package vip.xiaozhao.intern.baseUtil.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vip.xiaozhao.intern.baseUtil.intf.constant.COSConstant;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;
import vip.xiaozhao.intern.baseUtil.intf.utils.cos.COSUtils;
import vip.xiaozhao.intern.baseUtil.service.CircuitBreakerService;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Tag(name = "OSS")
@RestController
@RequestMapping("/api/oss")
public class OSSController extends BaseController {

    private static final Logger logger = LoggerFactory.getLogger(OSSController.class);
    private static final Pattern SAFE_EXTENSION = Pattern.compile("[A-Za-z0-9]{1,10}");

    private final CircuitBreakerService circuitBreakerService;

    @Autowired
    public OSSController(CircuitBreakerService circuitBreakerService) {
        this.circuitBreakerService = circuitBreakerService;
    }

    @Operation(summary = "Get upload token", description = "Create a temporary COS upload token")
    @PostMapping("/getUploadToken")
    public ResponseDO getUploadToken(@Parameter(description = "Token request", required = true) @RequestBody UploadTokenRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        try {
            JSONObject credential = circuitBreakerService.executeWithCosBreaker(
                    () -> {
                        try {
                            return COSUtils.genCOSPlubParams(currentUserId);
                        } catch (IOException exception) {
                            throw new IllegalStateException("COS STS request failed", exception);
                        }
                    },
                    () -> null);
            if (credential == null) {
                return fail("COS temporarily unavailable");
            }
            Map<String, Object> result = new HashMap<>();
            result.put("tmpSecretId", credential.getJSONObject("credentials").getString("tmpSecretId"));
            result.put("tmpSecretKey", credential.getJSONObject("credentials").getString("tmpSecretKey"));
            result.put("sessionToken", credential.getJSONObject("credentials").getString("sessionToken"));
            result.put("startTime", credential.getLong("startTime"));
            result.put("expiredTime", credential.getLong("expiredTime"));
            result.put("bucket", COSConstant.mainBucket);
            result.put("region", COSConstant.region);
            result.put("allowPrefix", userUploadPrefix(currentUserId) + "*");
            return success(result);
        } catch (Exception e) {
            logger.warn("Failed to generate COS upload token", e);
            return fail("failed to generate upload token");
        }
    }

    @Operation(summary = "Upload file", description = "Upload a file to COS through the server")
    @PostMapping("/upload")
    public ResponseDO uploadFile(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "dir", required = false) String ignoredDir) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        if (file.isEmpty()) {
            return fail("file is empty");
        }
        try (InputStream inputStream = file.getInputStream()) {
            String fileName = buildObjectName(currentUserId, file.getOriginalFilename());

            boolean uploaded = circuitBreakerService.executeWithCosBreaker(
                    () -> {
                        COSUtils.uploadStreamOrThrow(inputStream, file.getSize(), file.getContentType(),
                                COSConstant.mainBucket, fileName);
                        return true;
                    },
                    () -> false);

            if (uploaded) {
                Map<String, Object> result = new HashMap<>();
                result.put("fileName", fileName);
                result.put("url", COSConstant.COS_HOST + "/" + fileName);
                return success(result);
            }
            return fail("COS temporarily unavailable or upload failed");
        } catch (IOException e) {
            logger.warn("Failed to read COS upload stream", e);
            return fail("upload error");
        }
    }

    @Operation(summary = "Get access URL", description = "Generate a temporary signed URL")
    @GetMapping("/access/{fileName}")
    public ResponseDO getAccessUrl(@Parameter(description = "File path", required = true) @PathVariable String fileName) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        try {
            String objectName = requireOwnedObjectName(currentUserId, fileName);
            String url = circuitBreakerService.executeWithCosBreaker(
                    () -> COSUtils.geneSignedUrl(COSConstant.mainBucket, objectName),
                    () -> null);
            if (url == null) {
                return fail("COS temporarily unavailable");
            }
            Map<String, Object> result = new HashMap<>();
            result.put("url", url);
            return success(result);
        } catch (Exception e) {
            logger.warn("Failed to generate COS access URL, fileName={}", fileName, e);
            return fail("failed to generate access url");
        }
    }

    @Operation(summary = "Delete file", description = "Delete a file from COS")
    @PostMapping("/delete")
    public ResponseDO deleteFile(@Parameter(description = "Delete request", required = true) @RequestBody DeleteFileRequest request) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) {
            return unauthorized();
        }
        if (request.getFileName() == null || request.getFileName().isEmpty()) {
            return fail("file name is required");
        }
        try {
            String objectName = requireOwnedObjectName(currentUserId, request.getFileName());
            boolean deleted = circuitBreakerService.executeWithCosBreaker(
                    () -> {
                        COSUtils.deleteObjectOrThrow(COSConstant.mainBucket, objectName);
                        return true;
                    },
                    () -> false);
            if (deleted) {
                return success("deleted");
            }
            return fail("COS temporarily unavailable or delete failed");
        } catch (Exception e) {
            logger.warn("Failed to delete COS object, fileName={}", request.getFileName(), e);
            return fail("delete failed");
        }
    }

    static String userUploadPrefix(Long userId) {
        return "uploads/" + userId + "/";
    }

    static String buildObjectName(Long userId, String originalFilename) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("valid user id is required");
        }
        String extensionSuffix = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            String extension = originalFilename.substring(originalFilename.lastIndexOf('.') + 1);
            if (SAFE_EXTENSION.matcher(extension).matches()) {
                extensionSuffix = "." + extension.toLowerCase(java.util.Locale.ROOT);
            }
        }
        return userUploadPrefix(userId)
                + UUID.randomUUID().toString().replace("-", "")
                + extensionSuffix;
    }

    static String requireOwnedObjectName(Long userId, String fileName) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("valid user id is required");
        }
        String prefix = userUploadPrefix(userId);
        String objectName = fileName == null ? "" : fileName.trim().replace('\\', '/');
        if (!objectName.startsWith(prefix)) {
            objectName = prefix + objectName;
        }
        String leaf = objectName.substring(prefix.length());
        if (!leaf.matches("[A-Fa-f0-9]{32}(\\.[A-Za-z0-9]{1,10})?")) {
            throw new IllegalArgumentException("invalid or unauthorized COS object name");
        }
        return prefix + leaf;
    }

    public static class UploadTokenRequest {
    }

    public static class DeleteFileRequest {
        private String fileName;

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
    }
}
