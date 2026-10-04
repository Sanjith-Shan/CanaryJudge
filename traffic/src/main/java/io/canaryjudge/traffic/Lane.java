package io.canaryjudge.traffic;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * One traffic lane: a primary, a baseline and a canary, and the share of users each gets. Users are
 * assigned by a hash of their id into 10,000 buckets, so a user stays on the same side for the whole
 * rollout and raising the canary's share only adds users (buckets [0, canary) go to the canary,
 * [canary, canary + baseline) to the baseline, the rest to the primary). A lane in request mode instead
 * draws a fresh bucket for every request, which splits requests evenly however skewed users are; the trial
 * lanes use it, the rollout lane uses sticky users.
 */
public final class Lane {
    public static final String[] ROLES = {"primary", "baseline", "canary"};
    static final int BUCKETS = 10_000;

    final String name;
    final boolean sticky;
    private volatile int canaryBuckets;
    private volatile int baselineBuckets;
    private final Map<String, LongAdder> requests = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> errors = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> upstreamFailures = new ConcurrentHashMap<>();
    private volatile Map<String, Set<Long>> users = freshUsers();
    private volatile Set<Long> allUsers = ConcurrentHashMap.newKeySet();
    private volatile long epochMillis = System.currentTimeMillis();

    Lane(String name, double canary, double baseline, boolean sticky) {
        this.name = name;
        this.sticky = sticky;
        setWeights(canary, baseline);
        for (String r : ROLES) {
            requests.put(r, new LongAdder());
            errors.put(r, new LongAdder());
            upstreamFailures.put(r, new LongAdder());
        }
    }

    private static Map<String, Set<Long>> freshUsers() {
        Map<String, Set<Long>> m = new ConcurrentHashMap<>();
        for (String r : ROLES) m.put(r, ConcurrentHashMap.newKeySet());
        return m;
    }

    public synchronized void setWeights(double canary, double baseline) {
        if (canary < 0 || baseline < 0 || canary + baseline > 1.0 + 1e-9)
            throw new IllegalArgumentException("weights must be non-negative and sum to at most 1");
        canaryBuckets = (int) Math.round(canary * BUCKETS);
        baselineBuckets = (int) Math.round(baseline * BUCKETS);
    }

    public double canaryWeight() { return canaryBuckets / (double) BUCKETS; }

    public double baselineWeight() { return baselineBuckets / (double) BUCKETS; }

    /** Bucket of a user, from a 64-bit mix of the id (SplitMix64 finalizer). */
    static int bucket(long user) {
        long z = user + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (int) Long.remainderUnsigned(z, BUCKETS);
    }

    public String route(long user) {
        int b = sticky ? bucket(user) : java.util.concurrent.ThreadLocalRandom.current().nextInt(BUCKETS);
        if (b < canaryBuckets) return "canary";
        if (b < canaryBuckets + baselineBuckets) return "baseline";
        return "primary";
    }

    void record(String role, long user, int status) {
        requests.get(role).increment();
        if (status >= 500) errors.get(role).increment();
        if (status == 599) upstreamFailures.get(role).increment();
        users.get(role).add(user);
        allUsers.add(user);
    }

    /** Starts a new counting epoch for distinct users (counters stay cumulative). */
    public synchronized void resetUsers() {
        users = freshUsers();
        allUsers = ConcurrentHashMap.newKeySet();
        epochMillis = System.currentTimeMillis();
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("lane", name);
        m.put("sticky", sticky);
        m.put("canaryWeight", canaryWeight());
        m.put("baselineWeight", baselineWeight());
        m.put("epochMillis", epochMillis);
        m.put("usersSinceEpoch", allUsers.size());
        for (String r : ROLES) {
            m.put(r + "Requests", requests.get(r).sum());
            m.put(r + "Errors", errors.get(r).sum());
            m.put(r + "UpstreamFailures", upstreamFailures.get(r).sum());
            m.put(r + "UsersSinceEpoch", users.get(r).size());
        }
        return m;
    }

    void prometheus(StringBuilder sb) {
        for (String r : ROLES) {
            String l = "{lane=\"" + name + "\",role=\"" + r + "\"}";
            sb.append("cj_splitter_requests_total").append(l).append(' ').append(requests.get(r).sum()).append('\n');
            sb.append("cj_splitter_errors_total").append(l).append(' ').append(errors.get(r).sum()).append('\n');
            sb.append("cj_splitter_users").append(l).append(' ').append(users.get(r).size()).append('\n');
        }
        sb.append("cj_splitter_weight{lane=\"").append(name).append("\",role=\"canary\"} ").append(canaryWeight()).append('\n');
        sb.append("cj_splitter_weight{lane=\"").append(name).append("\",role=\"baseline\"} ").append(baselineWeight()).append('\n');
        sb.append("cj_splitter_users_all{lane=\"").append(name).append("\"} ").append(allUsers.size()).append('\n');
    }
}
