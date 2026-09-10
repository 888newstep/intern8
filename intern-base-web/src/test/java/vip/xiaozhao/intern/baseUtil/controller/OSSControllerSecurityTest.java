package vip.xiaozhao.intern.baseUtil.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;
import vip.xiaozhao.intern.baseUtil.intf.utils.cos.COSUtils;
import vip.xiaozhao.intern.baseUtil.service.CircuitBreakerService;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class OSSControllerSecurityTest {

    private static final long USER_ID = 42L;
    private static final String UUID = "0123456789abcdef0123456789abcdef";

    @Mock
    private CircuitBreakerService circuitBreakerService;

    private OSSController controller;

    @BeforeEach
    void setUp() {
        controller = new OSSController(circuitBreakerService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void generatedObjectNameUsesServerControlledUserPrefix() {
        String objectName = OSSController.buildObjectName(USER_ID, "../../avatar.JPG");

        assertTrue(objectName.matches("uploads/42/[a-f0-9]{32}\\.jpg"));
    }

    @Test
    void ownedObjectAcceptsOnlyFlatUuidBasedKeys() {
        assertEquals("uploads/42/" + UUID + ".png",
                OSSController.requireOwnedObjectName(USER_ID, UUID + ".png"));
        assertEquals("uploads/42/" + UUID,
                OSSController.requireOwnedObjectName(USER_ID, "uploads/42/" + UUID));

        assertThrows(IllegalArgumentException.class,
                () -> OSSController.requireOwnedObjectName(USER_ID, "uploads/7/" + UUID));
        assertThrows(IllegalArgumentException.class,
                () -> OSSController.requireOwnedObjectName(USER_ID, "../" + UUID));
        assertThrows(IllegalArgumentException.class,
                () -> OSSController.requireOwnedObjectName(USER_ID, "nested/" + UUID));
        assertThrows(IllegalArgumentException.class,
                () -> OSSController.requireOwnedObjectName(USER_ID, "avatar.png"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void uploadStreamsMultipartContentWithoutUsingClientProvidedDirectory() throws Exception {
        doAnswer(invocation -> ((Supplier<Boolean>) invocation.getArgument(0)).get())
                .when(circuitBreakerService)
                .executeWithCosBreaker(any(Supplier.class), any(Supplier.class));
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", new byte[]{1, 2, 3});
        try (MockedStatic<COSUtils> cosUtils = mockStatic(COSUtils.class)) {
            ResponseDO response = controller.uploadFile(file, "../../outside");

            assertTrue(response.isSuccess());
            Map<?, ?> data = (Map<?, ?>) response.getData();
            String uploadedObjectName = (String) data.get("fileName");
            assertTrue(uploadedObjectName.matches("uploads/42/[a-f0-9]{32}\\.png"));
            cosUtils.verify(() -> COSUtils.uploadStreamOrThrow(
                    any(InputStream.class), eq(3L), eq("image/png"),
                    nullable(String.class), eq(uploadedObjectName)));
        }
    }
}
