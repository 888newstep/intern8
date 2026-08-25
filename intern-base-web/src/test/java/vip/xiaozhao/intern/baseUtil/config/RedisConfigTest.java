package vip.xiaozhao.intern.baseUtil.config;

import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import vip.xiaozhao.intern.baseUtil.utils.RedisCommandClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisConfigTest {

    @SuppressWarnings("unchecked")
    @Test
    void commandClientShouldUseStringCodecForLuaCompatibleValuesAndHashes() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBucket<String> bucket = mock(RBucket.class);
        RMap<String, String> map = mock(RMap.class);
        when(redissonClient.<String>getBucket("plain-key", StringCodec.INSTANCE)).thenReturn(bucket);
        when(redissonClient.<String, String>getMap("hash-key", StringCodec.INSTANCE)).thenReturn(map);
        when(bucket.get()).thenReturn("plain-value");
        when(map.get("response")).thenReturn("cached-response");

        RedisCommandClient client = new RedisConfig().redisCommandClient(redissonClient);

        assertEquals("plain-value", client.get("plain-key"));
        assertEquals("cached-response", client.hget("hash-key", "response"));
        verify(redissonClient).getBucket("plain-key", StringCodec.INSTANCE);
        verify(redissonClient).getMap("hash-key", StringCodec.INSTANCE);
    }
}
