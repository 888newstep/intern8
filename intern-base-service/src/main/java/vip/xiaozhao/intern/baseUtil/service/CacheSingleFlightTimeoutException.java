package vip.xiaozhao.intern.baseUtil.service;

/**
 * 表示请求等待本地 single-flight leader 超过配置的时间上限。
 *
 * <p>该异常不取消共享 future，避免一个 waiter 的超时影响 leader 和其他请求。</p>
 */
public class CacheSingleFlightTimeoutException extends RuntimeException {

    public CacheSingleFlightTimeoutException(long waitTimeoutMs) {
        super("Cache loading did not complete within " + waitTimeoutMs + " ms");
    }

    public CacheSingleFlightTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
