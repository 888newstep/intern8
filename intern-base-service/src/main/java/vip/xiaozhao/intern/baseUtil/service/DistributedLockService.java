package vip.xiaozhao.intern.baseUtil.service;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 閸掑棗绔峰蹇涙敚閺堝秴濮熼敍鍫濈唨娴滃冻edisson閿? * 鐟欙絽鍠呴敍姘樋娴滃搫鎮撻弮璺哄絺鐢啫濮╅幀?閻愮绂?鐠囧嫯顔戠粵澶婃簚閺咁垯绗呴惃鍕嫙閸欐垵鍟跨粣? * 閸樼喓鎮婇敍姝奺disson RLock 閸╄桨绨?Redis 閸濄劌鍙?闂嗗棛鍏㈤惃?Lua 閼存碍婀扮€圭偟骞囬敍宀冨殰閸斻劎鐢婚張鐔兼Щ濮濆鏀? */
@Service
public class DistributedLockService {

    private static final Logger logger = LoggerFactory.getLogger(DistributedLockService.class);

    /** 姒涙顓婚柨浣虹搼瀵板懏妞傞梻杈剧窗3缁?*/
    private static final long DEFAULT_WAIT_TIME = 3L;

    /** 姒涙顓婚柨浣瑰瘮閺堝妞傞梻杈剧窗10缁夋帪绱橰edisson閼奉亜濮╃紒顓熸埂閿涘苯鐤勯梽鍛瑝娴兼俺绉撮弮璁圭礆 */
    private static final long DEFAULT_LEASE_TIME = 10L;

    // ======================= 闁夸礁澧犵紓鈧?=======================
    public static final String LOCK_PREFIX = "lock:";
    /** 閸斻劍鈧礁褰傜敮鍐敚 */
    public static final String LOCK_DYNAMIC_PUBLISH = LOCK_PREFIX + "dynamic:publish:";
    /** 閸斻劍鈧胶鍋ｇ挧鐐烘敚 */
    public static final String LOCK_DYNAMIC_LIKE = LOCK_PREFIX + "dynamic:like:";
    /** 閸斻劍鈧浇鐦庣拋娲敚 */
    public static final String LOCK_DYNAMIC_COMMENT = LOCK_PREFIX + "dynamic:comment:";
    /** 閸忚櫕鏁為幙宥勭稊闁?*/
    public static final String LOCK_FOLLOW = LOCK_PREFIX + "follow:";

    private final RedissonClient redissonClient;

    public DistributedLockService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 鐏忔繆鐦懢宄板絿閸掑棗绔峰蹇涙敚閿涘牆鐢妯款吇鐡掑懏妞傞敍?     *
     * @param lockKey 闁夸胶娈慘ey
     * @return 闁夸礁鐤勬笟瀣剁礉閼惧嘲褰囨稉宥呭煂鏉╂柨娲杗ull
     */
    public RLock tryLock(String lockKey) {
        return tryLock(lockKey, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME);
    }

    /**
     * 鐏忔繆鐦懢宄板絿閸掑棗绔峰蹇涙敚
     *
     * @param lockKey   闁夸胶娈慘ey
     * @param waitTime  缁涘绶熼柨浣烘畱閺堚偓闂€鎸庢闂傝揪绱欑粔鎺炵礆
     * @param leaseTime 闁夸焦瀵旈張澶嬫闂傝揪绱欑粔鎺炵礉Redisson閼奉亜濮╃紒顓熸埂閿?     * @return 闁夸礁鐤勬笟瀣剁礉閼惧嘲褰囨稉宥呭煂鏉╂柨娲杗ull
     */
    public RLock tryLock(String lockKey, long waitTime, long leaseTime) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            boolean acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
            if (acquired) {
                logger.debug("Distributed lock acquired: {}", lockKey);
                return lock;
            } else {
                logger.warn("Failed to acquire distributed lock: {}", lockKey);
                return null;
            }
        } catch (InterruptedException e) {
            logger.error("Distributed lock interrupted: {}", lockKey, e);
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 闁插﹥鏂侀崚鍡楃瀵繘鏀?     */
    public void unlock(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            try {
                lock.unlock();
                logger.debug("Distributed lock released");
            } catch (Exception e) {
                logger.error("Failed to release distributed lock", e);
            }
        }
    }

    /**
     * 鐢箓鏀ｉ幍褑顢戞禒璇插
     *
     * @param lockKey  闁夸胶娈慘ey
     * @param task     闂団偓鐟曚焦澧界悰宀€娈戞禒璇插
     * @param <T>      鏉╂柨娲栭崐鑲╄閸?     * @return 娴犺濮熼幍褑顢戠紒鎾寸亯閿涘矁骞忛崣鏍敚婢惰精瑙︽潻鏂挎礀null
     */
    public <T> T executeWithLock(String lockKey, LockTask<T> task) {
        RLock lock = tryLock(lockKey);
        if (lock == null) {
            return null;
        }
        try {
            return task.execute();
        } finally {
            unlock(lock);
        }
    }

    /**
     * 鐢箓鏀ｉ幍褑顢戞禒璇插閿涘牊妫ゆ潻鏂挎礀閸婄》绱?     */
    public boolean executeWithLockVoid(String lockKey, Runnable task) {
        return executeWithLockVoid(lockKey, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, task);
    }

    public boolean executeWithLockVoid(String lockKey, long waitTime, long leaseTime, Runnable task) {
        RLock lock = tryLock(lockKey, waitTime, leaseTime);
        if (lock == null) {
            return false;
        }
        try {
            task.run();
            return true;
        } finally {
            unlock(lock);
        }
    }

    @FunctionalInterface
    public interface LockTask<T> {
        T execute();
    }
}
