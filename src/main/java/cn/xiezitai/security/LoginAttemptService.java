package cn.xiezitai.security;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败锁定策略：
 *  连续 3 次失败 -> 锁 5 分钟
 *  连续 5 次失败 -> 锁 10 分钟
 *  连续 10 次失败 -> 锁 1 小时
 */
@Component
public class LoginAttemptService {

    private static class State {
        int fails;
        Instant lockUntil;
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();

    private static final int[] THRESHOLDS = {3, 5, 10};
    private static final long[] LOCK_MINUTES = {5, 10, 60};

    public boolean isLocked(String username) {
        State s = states.get(username);
        if (s == null) return false;
        if (s.lockUntil != null && Instant.now().isBefore(s.lockUntil)) return true;
        if (s.lockUntil != null && Instant.now().isAfter(s.lockUntil)) {
            s.lockUntil = null;
            s.fails = 0;
        }
        return false;
    }

    public long remainingLockSeconds(String username) {
        State s = states.get(username);
        if (s == null || s.lockUntil == null) return 0;
        return Math.max(0, s.lockUntil.getEpochSecond() - Instant.now().getEpochSecond());
    }

    public void onFailure(String username) {
        State s = states.computeIfAbsent(username, k -> new State());
        s.fails++;
        for (int i = THRESHOLDS.length - 1; i >= 0; i--) {
            if (s.fails >= THRESHOLDS[i]) {
                s.lockUntil = Instant.now().plusSeconds(LOCK_MINUTES[i] * 60);
                return;
            }
        }
    }

    public void onSuccess(String username) {
        states.remove(username);
    }
}
