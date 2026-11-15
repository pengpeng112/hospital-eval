package com.hospital.eval.system.service;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败锁定（TASK-05 步骤 4）：同一用户名 5 分钟内连续失败 5 次 → 锁定 10 分钟（内存实现）。
 */
@Component
public class LoginAttemptLimiter {

    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_MS = 5 * 60 * 1000L;
    private static final long LOCK_MS = 10 * 60 * 1000L;

    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> lockUntil = new ConcurrentHashMap<>();

    /** 是否处于锁定中。 */
    public boolean isLocked(String username) {
        Long until = lockUntil.get(username);
        if (until == null) {
            return false;
        }
        if (until < System.currentTimeMillis()) {
            lockUntil.remove(username);
            return false;
        }
        return true;
    }

    /** 记录一次失败；达到阈值则锁定。 */
    public void recordFailure(String username) {
        long now = System.currentTimeMillis();
        Deque<Long> deque = failures.computeIfAbsent(username, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(now);
            while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
                deque.removeFirst();
            }
            if (deque.size() >= MAX_FAILURES) {
                lockUntil.put(username, now + LOCK_MS);
                deque.clear();
            }
        }
    }

    /** 登录成功后清零。 */
    public void reset(String username) {
        failures.remove(username);
        lockUntil.remove(username);
    }
}
