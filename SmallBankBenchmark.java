package my.test;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.OptimisticTransactionDB;
import org.rocksdb.OptimisticTransactionOptions;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.Status;
import org.rocksdb.Transaction;
import org.rocksdb.TransactionOptions;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

public class SmallBankBenchmark {

    // Uniform distribution: 5 transaction types, each 20%
    private static final int OP_BALANCE = 0;
    private static final int OP_WRITECHECK = 1;
    private static final int OP_DEPOSIT_CHECKING = 2;
    private static final int OP_TRANSACT_SAVING = 3;
    private static final int OP_AMALGAMATE = 4;
    // Sub-type used for putWithMeta tracking.
    private static final int OP_AMALGAMATE_ZERO_BALANCE = 5;

    // Balance every account starts with, in both column families.
    private static final int INITIAL_BALANCE = 1000;

    private static final int DEFAULT_NUM_ACCOUNTS = 20;
    private static final int DEFAULT_NUM_THREADS = 4;
    private static final int DEFAULT_DURATION_SECONDS = 5;
    private static final String DEFAULT_DB_PATH = "./rocksdb-smallbank";
    // Optional simulated client-server network delay, applied to each round trip
    // (every read, write, and commit). 0 = disabled.
    private static final long DEFAULT_NETWORK_DELAY_MICROS = 0;

    // Retry backoff tuning. The backoff base is the duration of the aborted
    // attempt; these bound it so a very fast attempt still yields, and a long
    // one (or a long abort streak) cannot park a worker for the whole run.
    private static final long BACKOFF_MIN_BASE_NANOS = 1000L;   // 200 us
    private static final long BACKOFF_CAP_NANOS = 250_000_000L;    // 250 ms
    private static final int BACKOFF_MAX_SHIFT = 5;                // <= 32x base

    // Whether a conflict abort backs off before retrying. Off by default;
    // enable with the 6th positional arg (1) or BACKOFF=1 in the environment.
    private static final boolean DEFAULT_USE_BACKOFF = false;
    private static boolean useBackoff = DEFAULT_USE_BACKOFF;

    // A logical transaction is retried (as the same transaction) up to this many
    // attempts before being abandoned.
    private static final int MAX_TXN_ATTEMPTS = 20;

    // Per-operation caps on how many logical transactions of that type the whole
    // workload runs. 0 means unlimited. Only TransactSaving and WriteCheck are
    // capped; every other type is always unlimited. Set with the 7th/8th
    // positional args or MAX_TRANSACT_SAVING / MAX_WRITECHECK in the environment.
    // Indexed by op type, so an uncapped op is just a 0 entry.
    private static final long[] maxTxnsPerOp = new long[6];
    // Logical transactions of each type claimed by workers. Claimed before
    // execution so a cap bounds the transactions actually started, and no two
    // workers can claim the same slot.
    private static final java.util.concurrent.atomic.AtomicLong[] claimedPerOp =
            new java.util.concurrent.atomic.AtomicLong[6];
    // Ops whose cap is used up. Read by TxnSpec.random to stop drawing them, so a
    // worker doesn't spin redrawing an exhausted type.
    private static final boolean[] opExhausted = new boolean[6];

    static {
        for (int i = 0; i < claimedPerOp.length; i++) {
            claimedPerOp[i] = new java.util.concurrent.atomic.AtomicLong();
        }
    }

    private static boolean isCapped(int op) {
        return maxTxnsPerOp[op] > 0;
    }

    // Claim a slot for one logical transaction of this type, or return false if
    // the type's cap is already used up.
    private static boolean claimTxnSlot(int op) {
        if (!isCapped(op)) {
            return true;
        }
        if (claimedPerOp[op].incrementAndGet() <= maxTxnsPerOp[op]) {
            return true;
        }
        opExhausted[op] = true;
        return false;
    }

    // True once every capped op is exhausted and no uncapped op remains in the
    // mix: there is nothing left to run, so the workload can stop early.
    private static boolean allOpsExhausted(int[] possibleOps) {
        for (int op : possibleOps) {
            if (!isCapped(op) || !opExhausted[op]) {
                return false;
            }
        }
        return true;
    }

    // Marker that commit-time repair stashes in the transaction name when it
    // suppressed a transaction's update as an overdraft. Must match
    // kOverdraftMarker in rocksdb/utilities/transactions/optimistic_transaction.cc.
    private static final String OVERDRAFT_MARKER = "overdraft";
    private static final String REPAIR_MARKER = "repair";

    // DepositChecking transactions perform this many deposits each.
    private static final int DEPOSITS_PER_TXN = 1;

    private static OptimisticTransactionDB db;
    private static ColumnFamilyHandle cfDefault;
    private static ColumnFamilyHandle cfChecking;
    private static ColumnFamilyHandle cfSavings;

    // Isolation mode, parsed from the ABORT_MODE environment variable at startup.
    // 0: no conflicts (Atomic Snapshot), 1: write-write conflicts (Classic SI),
    // 2: refined SI, 3: SI with repair.
    private static int abortMode = 0;

    private static volatile boolean running = true;

    // Invariant-checking mode (INVARIANT_MODE=1). Restricts the workload to
    // TransactSaving + WriteCheck (the two operations whose guards depend on
    // checking+savings of a single account) and periodically snapshots every
    // account to evaluate invariants over the run.
    private static boolean invariantMode = false;
    private static long invariantSampleIntervalMillis = 100;
    private static String invariantCsvPath = "invariant_results.csv";

    // Quiescence barrier used by the invariant sampler. Workers park at this
    // barrier between logical transactions, so a sample is taken against a
    // fully idle database rather than concurrently with in-flight writers.
    private static final Object pauseLock = new Object();
    private static volatile boolean paused = false;
    private static int pausedWorkers = 0;
    private static int activeWorkers = 0;

    // End the run and release anyone parked at the quiescence barrier so every
    // worker (and the sampler) can exit.
    private static void stopRun() {
        running = false;
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
    }

    // Called by a worker between logical transactions: block here for as long
    // as the sampler has the workload paused.
    private static void awaitIfPaused() {
        if (!paused) {
            return;
        }
        synchronized (pauseLock) {
            pausedWorkers++;
            pauseLock.notifyAll();
            while (paused) {
                try {
                    pauseLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            pausedWorkers--;
        }
    }

    private static final LongAdder totalTxns = new LongAdder();
    private static final LongAdder totalLatencyNanos = new LongAdder();
    private static final LongAdder totalAborts = new LongAdder();
    private static final LongAdder totalErrors = new LongAdder();
    // Logical-transaction stats: attempts summed over resolved logical txns, and
    // logical txns given up on after MAX_TXN_ATTEMPTS conflict aborts.
    private static final LongAdder totalAttempts = new LongAdder();
    private static final LongAdder totalAbandoned = new LongAdder();
    // End-to-end latency of committed logical txns: from the start of the first
    // attempt to the commit, so it includes every retry and any backoff waits.
    private static final LongAdder totalLogicalLatencyNanos = new LongAdder();

    // Sum, over committed transactions only, of the balance deltas each one
    // actually applied (per column family). Accumulated as we go so the expected
    // totals below reflect the real amounts written -- including the data
    // -dependent branches (insufficient funds, clamped deltas) that make a write
    // a no-op -- rather than assuming a fixed amount per operation type.
    private static final LongAdder committedCheckingDelta = new LongAdder();
    private static final LongAdder committedSavingsDelta = new LongAdder();
    // Commits whose update commit-time repair suppressed as an overdraft.
    private static final LongAdder totalOverdrafts = new LongAdder();

    private static final LongAdder balanceCount = new LongAdder();
    private static final LongAdder balanceLatency = new LongAdder();
    private static final LongAdder balanceAborts = new LongAdder();
    private static final LongAdder writeCheckCount = new LongAdder();
    private static final LongAdder writeCheckLatency = new LongAdder();
    private static final LongAdder writeCheckAborts = new LongAdder();
    private static final LongAdder depositCheckingCount = new LongAdder();
    private static final LongAdder depositCheckingLatency = new LongAdder();
    private static final LongAdder depositCheckingAborts = new LongAdder();
    private static final LongAdder transactSavingCount = new LongAdder();
    private static final LongAdder transactSavingLatency = new LongAdder();
    private static final LongAdder transactSavingAborts = new LongAdder();
    private static final LongAdder amalgamateCount = new LongAdder();
    private static final LongAdder amalgamateLatency = new LongAdder();
    private static final LongAdder amalgamateAborts = new LongAdder();

    private static LongAdder abortCounter(int op) {
        switch (op) {
            case OP_BALANCE: return balanceAborts;
            case OP_WRITECHECK: return writeCheckAborts;
            case OP_DEPOSIT_CHECKING: return depositCheckingAborts;
            case OP_TRANSACT_SAVING: return transactSavingAborts;
            default: return amalgamateAborts;
        }
    }

    private static LongAdder countCounter(int op) {
        switch (op) {
            case OP_BALANCE: return balanceCount;
            case OP_WRITECHECK: return writeCheckCount;
            case OP_DEPOSIT_CHECKING: return depositCheckingCount;
            case OP_TRANSACT_SAVING: return transactSavingCount;
            default: return amalgamateCount;
        }
    }

    private static LongAdder latencyCounter(int op) {
        switch (op) {
            case OP_BALANCE: return balanceLatency;
            case OP_WRITECHECK: return writeCheckLatency;
            case OP_DEPOSIT_CHECKING: return depositCheckingLatency;
            case OP_TRANSACT_SAVING: return transactSavingLatency;
            default: return amalgamateLatency;
        }
    }

    static {
        RocksDB.loadLibrary();
    }

    private static boolean parseBool(String s) {
        String v = s.trim();
        return v.equals("1") || v.equalsIgnoreCase("true")
                || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("on");
    }

    private static String capLabel(int op) {
        return maxTxnsPerOp[op] > 0 ? String.valueOf(maxTxnsPerOp[op]) : "unlimited";
    }

    // Read a transaction cap from the given positional arg if present, else from
    // the named env var, else 0 (unlimited).
    private static long parseCap(String[] args, int argIndex, String envName) {
        if (args.length > argIndex && !args[argIndex].trim().isEmpty()) {
            return Long.parseLong(args[argIndex].trim());
        }
        String env = System.getenv(envName);
        if (env != null && !env.trim().isEmpty()) {
            return Long.parseLong(env.trim());
        }
        return 0;
    }

    public static void main(String[] args) throws Exception {
        String dbPath = args.length > 0 ? args[0] : DEFAULT_DB_PATH;
        int numAccounts = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_NUM_ACCOUNTS;
        int numThreads = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_NUM_THREADS;
        int durationSeconds = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_DURATION_SECONDS;
        long networkDelayMicros = args.length > 4 ? Long.parseLong(args[4]) : DEFAULT_NETWORK_DELAY_MICROS;

        String abortModeEnv = System.getenv("ABORT_MODE");
        abortMode = abortModeEnv != null ? Integer.parseInt(abortModeEnv.trim()) : 0;

        // Retry backoff: 6th positional arg wins, else BACKOFF env var.
        String backoffEnv = System.getenv("BACKOFF");
        if (args.length > 5) {
            useBackoff = parseBool(args[5]);
        } else if (backoffEnv != null) {
            useBackoff = parseBool(backoffEnv);
        }

        // Per-type transaction caps: positional arg wins, else the env var.
        maxTxnsPerOp[OP_TRANSACT_SAVING] =
                parseCap(args, 6, "MAX_TRANSACT_SAVING");
        maxTxnsPerOp[OP_WRITECHECK] =
                parseCap(args, 7, "MAX_WRITECHECK");

        System.out.println("SmallBank RocksDB Benchmark (TransactionDB, uniform mix)");
        System.out.println("DB path: " + dbPath);
        System.out.println("Accounts: " + numAccounts);
        System.out.println("Threads: " + numThreads);
        System.out.println("Duration (s): " + durationSeconds);
        System.out.println("Simulated network delay per round trip (us): " + networkDelayMicros);
        System.out.println("Retry exponential backoff: " + (useBackoff ? "enabled" : "disabled"));
        System.out.println("Max TransactSaving txns: " + capLabel(OP_TRANSACT_SAVING));
        System.out.println("Max WriteCheck txns: " + capLabel(OP_WRITECHECK));

        String invEnv = System.getenv("INVARIANT_MODE");
        invariantMode = invEnv != null && parseBool(invEnv);
        String invIntervalEnv = System.getenv("INVARIANT_SAMPLE_MS");
        if (invIntervalEnv != null) {
            invariantSampleIntervalMillis = Long.parseLong(invIntervalEnv.trim());
        }
        String invCsvEnv = System.getenv("INVARIANT_CSV");
        if (invCsvEnv != null) {
            invariantCsvPath = invCsvEnv.trim();
        }
        if (invariantMode) {
            System.out.println("Invariant checking mode: enabled (ops: TransactSaving, WriteCheck)");
            System.out.println("Invariant sample interval (ms): " + invariantSampleIntervalMillis);
            System.out.println("Invariant CSV: " + invariantCsvPath);
        }

        // Destroy and recreate DB for a clean run
        Options destroyOptions = new Options().setCreateIfMissing(true);
        RocksDB.destroyDB(dbPath, destroyOptions);
        destroyOptions.close();

        List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();
        ColumnFamilyOptions cfOpts = new ColumnFamilyOptions();
        cfDescriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts));
        cfDescriptors.add(new ColumnFamilyDescriptor("checking".getBytes(StandardCharsets.UTF_8), cfOpts));
        cfDescriptors.add(new ColumnFamilyDescriptor("savings".getBytes(StandardCharsets.UTF_8), cfOpts));
        // Set RocksDB write buffer size to 1GB for all column family options
        cfOpts.setWriteBufferSize(1L * 1024 * 1024 * 1024);


        List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
        DBOptions dbOptions = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true);
        DBOptions txnDbOptions = new DBOptions();

        try {
            db = OptimisticTransactionDB.open(dbOptions, dbPath, cfDescriptors, cfHandles);
            cfDefault = cfHandles.get(0);
            cfChecking = cfHandles.get(1);
            cfSavings = cfHandles.get(2);

            System.out.println("Populating database...");
            populateDatabase(numAccounts);

            printAccountBalances("Balances BEFORE workload", numAccounts);

            System.out.println("Starting workload...");
            runWorkload(numAccounts, numThreads, durationSeconds, networkDelayMicros);

            printAccountBalances("Balances AFTER workload", numAccounts);

        } finally {
            if (cfDefault != null) cfDefault.close();
            if (cfChecking != null) cfChecking.close();
            if (cfSavings != null) cfSavings.close();
            if (db != null) db.close();
            cfOpts.close();
            dbOptions.close();
            txnDbOptions.close();
        }
    }

    private static void populateDatabase(int numAccounts) throws RocksDBException {
        Random rnd = new Random(42);
        WriteOptions wo = new WriteOptions();
        WriteBatch batch = new WriteBatch();
        int batchSize = 1000;

        for (int id = 0; id < numAccounts; id++) {
            int checkingBalance = 1 + rnd.nextInt(10_000);
            int savingsBalance = 1 + rnd.nextInt(10_000);

            checkingBalance = INITIAL_BALANCE;
            savingsBalance = INITIAL_BALANCE;

            byte[] idKey = intToBytes(id);
            batch.put(cfChecking, idKey, intToBytes(checkingBalance));
            batch.put(cfSavings, idKey, intToBytes(savingsBalance));

            if ((id + 1) % batchSize == 0) {
                db.write(wo, batch);
                batch.clear();
            }
        }

        if (batch.count() > 0) {
            db.write(wo, batch);
        }

        batch.close();
        wo.close();
        System.out.println("Database populated.");
    }

    private static void printAccountBalances(String label, int numAccounts) throws RocksDBException {
        System.out.println("=== " + label + " ===");
        System.out.printf("%-26s %14s %14s %14s%n", "Account", "Checking", "Savings", "Total");
        long grandTotal = 0;
        long grandChecking = 0;
        long grandSavings = 0;
        for (int id = 0; id < numAccounts; id++) {
            byte[] idKey = intToBytes(id);
            byte[] checkingVal = db.get(cfChecking, idKey);
            byte[] savingsVal = db.get(cfSavings, idKey);
            int checking = checkingVal == null ? 0 : bytesToInt(checkingVal);
            int savings = savingsVal == null ? 0 : bytesToInt(savingsVal);
            int total = checking + savings;
            grandChecking += checking;
            grandSavings += savings;
            grandTotal += total;
            System.out.printf("%-26s %14d %14d %14d%n", "Account" + id, checking, savings, total);
        }
        System.out.printf("%-26s %14d %14d %14d%n", "TOTAL", grandChecking, grandSavings, grandTotal);
        System.out.printf("Sum of all account balances: %d%n", grandTotal);
    }

    private static void runWorkload(int numAccounts, int numThreads, int durationSeconds, long networkDelayMicros) throws InterruptedException, RocksDBException {
        CountDownLatch latch = new CountDownLatch(numThreads);
        Thread[] threads = new Thread[numThreads];

        for (int i = 0; i < numThreads; i++) {
            threads[i] = new Thread(new Worker(numAccounts, latch, networkDelayMicros, i), "worker-" + i);
            threads[i].start();
        }

        Thread sampler = null;
        if (invariantMode) {
            sampler = new Thread(new InvariantSampler(numAccounts), "invariant-sampler");
            sampler.start();
        }

        // Whichever comes first: the duration elapses, or the workers exhaust the
        // logical-transaction cap and finish on their own.
        long startNanos = System.nanoTime();
        latch.await(durationSeconds, TimeUnit.SECONDS);
        running = false;
        // The sampler may have workers parked; wake them so they can exit.
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }

        latch.await();
        double elapsedSeconds = (System.nanoTime() - startNanos) / 1e9;
        if (sampler != null) {
            sampler.join();
        }

        long total = totalTxns.sum();
        long totalNanos = totalLatencyNanos.sum();
        long aborts = totalAborts.sum();
        long attempts = total + aborts;
        // Actual elapsed time, not the nominal duration: with a txn cap the run
        // can finish early, and throughput must reflect the time really spent.
        double seconds = elapsedSeconds;

        double throughput = total / seconds;
        double avgLatencyMs = total > 0 ? (totalNanos / (double) total) / 1_000_000.0 : 0.0;
        double abortRate = attempts > 0 ? (aborts / (double) attempts) * 100.0 : 0.0;

        System.out.println("=== Benchmark results ===");
        System.out.printf("Total transactions (committed): %d%n", total);
        System.out.printf("Total aborts (conflicts): %d%n", aborts);
        System.out.printf("Total errors (non-conflict): %d%n", totalErrors.sum());
        System.out.printf("Abort rate: %.2f%% (%d / %d attempts)%n", abortRate, aborts, attempts);
        System.out.printf("Elapsed: %.3f s%n", seconds);
        System.out.printf("Throughput: %.2f txns/sec%n", throughput);
        System.out.printf("Average latency: %.3f ms%n", avgLatencyMs);

        long abandoned = totalAbandoned.sum();
        long attemptsSum = totalAttempts.sum();
        long resolved = total + abandoned;
        double avgAttempts = resolved > 0 ? attemptsSum / (double) resolved : 0.0;
        System.out.printf("Abandoned logical txns (>= %d attempts): %d%n", MAX_TXN_ATTEMPTS, abandoned);
        System.out.printf("Average attempts per logical txn: %.2f%n", avgAttempts);

        // Logical latency: end-to-end per committed logical txn, including all
        // retried attempts and backoff waits (vs. "Average latency" above,
        // which measures only the single attempt that committed).
        double avgLogicalLatencyMs = total > 0
                ? (totalLogicalLatencyNanos.sum() / (double) total) / 1_000_000.0 : 0.0;
        System.out.printf("Average logical latency: %.3f ms%n", avgLogicalLatencyMs);

        // Expected totals from the amounts committed transactions actually wrote:
        // each committing attempt contributed (value written - value read) per
        // column family, summed here. This is exact even when the per-operation
        // amount is randomized or a guard (insufficient funds, clamped delta)
        // turns a write into a no-op.
        long checkingDelta = committedCheckingDelta.sum();
        long savingsDelta = committedSavingsDelta.sum();
        // Repair logs the amount it actually applied into these files (same
        // format as sum_diffs.py). Unrepaired commits are already in the
        // accumulators above; repaired ones are not, so add the file sums.
        long repairSavingsDelta = sumDiffsFromFile("savings_diffs.txt", true);
        long repairCheckingDelta = sumDiffsFromFile("checking_diffs.txt", true);
        long expectedCheckingTotal = (long) INITIAL_BALANCE * numAccounts
                + checkingDelta + repairCheckingDelta;
        long expectedSavingsTotal = (long) INITIAL_BALANCE * numAccounts
                + savingsDelta + repairSavingsDelta;
        long expectedTotal = expectedCheckingTotal + expectedSavingsTotal;
        System.out.printf(" >> Committed deltas: checking=%+d, savings=%+d%n", checkingDelta, savingsDelta);
        System.out.printf(" >> Repair diffs from disk: checking=%+d, savings=%+d%n",
                repairCheckingDelta, repairSavingsDelta);
        System.out.printf(" >> Repair-suppressed (overdraft) commits: %d%n", totalOverdrafts.sum());
        // System.out.printf(" >> [CHECKING INVARIANT] Expected checking total: %d%n", expectedCheckingTotal);
        // System.out.printf(" >> [SAVINGS INVARIANT] Expected savings total: %d%n", expectedSavingsTotal);
        // System.out.printf(" >> [TOTAL INVARIANT] Expected total: %d%n", expectedTotal);

        // Compare against what is actually in the DB.
        long actualChecking = actualTotal(cfChecking, numAccounts);
        long actualSavings = actualTotal(cfSavings, numAccounts);
        printInvariantCheck("CHECKING", expectedCheckingTotal, actualChecking);
        printInvariantCheck("SAVINGS", expectedSavingsTotal, actualSavings);
        printInvariantCheck("TOTAL", expectedTotal, actualChecking + actualSavings);


        System.out.println("----------------------------------------");
   

        printPerType("Balance", balanceCount, balanceLatency, balanceAborts);
        printPerType("WriteCheck", writeCheckCount, writeCheckLatency, writeCheckAborts);
        printPerType("DepositChecking", depositCheckingCount, depositCheckingLatency, depositCheckingAborts);
        printPerType("TransactSaving", transactSavingCount, transactSavingLatency, transactSavingAborts);
        printPerType("Amalgamate", amalgamateCount, amalgamateLatency, amalgamateAborts);
    }

    // Periodically reads every account under a consistent DB snapshot and
    // records the invariant predicates to CSV, one row per sample.
    private static class InvariantSampler implements Runnable {
        private final int numAccounts;

        InvariantSampler(int numAccounts) {
            this.numAccounts = numAccounts;
        }

        // Signal the pause and wait until every still-live worker has parked at
        // the barrier (or has exited because the run ended).
        private void quiesceWorkload() {
            synchronized (pauseLock) {
                paused = true;
                while (running && pausedWorkers < activeWorkers) {
                    try {
                        pauseLock.wait(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        private void resumeWorkload() {
            synchronized (pauseLock) {
                paused = false;
                pauseLock.notifyAll();
            }
        }

        @Override
        public void run() {
            long startNanos = System.nanoTime();
            long maxViolations = 0;
            long samples = 0;
            long samplesWithViolation = 0;
            long samplesWithLostUpdate = 0;
            long maxLostUpdate = 0;
            try (java.io.PrintWriter out = new java.io.PrintWriter(new java.io.FileWriter(invariantCsvPath))) {
                out.println("t_ms,committed_txns,neg_total_accounts,neg_checking_accounts,"
                        + "min_total,min_checking,min_savings,sum_checking,sum_savings,sum_total,"
                        + "expected_checking,expected_savings,expected_total,"
                        + "lost_update_checking,lost_update_savings,lost_update_total,pause_ms");
                while (running) {
                    long tMs = (System.nanoTime() - startNanos) / 1_000_000L;

                    long negTotal = 0, negChecking = 0;
                    long minTotal = Long.MAX_VALUE, minChecking = Long.MAX_VALUE, minSavings = Long.MAX_VALUE;
                    long sumChecking = 0, sumSavings = 0;
                    long committed;
                    long expectedChecking, expectedSavings;
                    long pauseStart = System.nanoTime();

                    // Quiesce the workload: every worker parks between logical
                    // transactions, so nothing is in flight while we read.
                    try {
                        quiesceWorkload();
                        committed = totalTxns.sum();
                        // Read the committed-delta accumulators inside the pause:
                        // a worker folds its deltas in right after commit and
                        // before it can reach the barrier, so these describe
                        // exactly the set of transactions durable in the DB now.
                        // Repair diffs are on disk (written at commit, before
                        // the worker can park), so re-sum the files here too.
                        expectedChecking = (long) INITIAL_BALANCE * numAccounts
                                + committedCheckingDelta.sum()
                                + sumDiffsFromFile("checking_diffs.txt");
                        expectedSavings = (long) INITIAL_BALANCE * numAccounts
                                + committedSavingsDelta.sum()
                                + sumDiffsFromFile("savings_diffs.txt");
                        for (int id = 0; id < numAccounts; id++) {
                            byte[] idKey = intToBytes(id);
                            byte[] cv = db.get(cfChecking, idKey);
                            byte[] sv = db.get(cfSavings, idKey);
                            long checking = cv == null ? 0 : bytesToInt(cv);
                            long savings = sv == null ? 0 : bytesToInt(sv);
                            long total = checking + savings;
                            if (total < 0) negTotal++;
                            if (checking < 0) negChecking++;
                            minTotal = Math.min(minTotal, total);
                            minChecking = Math.min(minChecking, checking);
                            minSavings = Math.min(minSavings, savings);
                            sumChecking += checking;
                            sumSavings += savings;
                        }
                    } catch (RocksDBException e) {
                        System.out.println("[INVARIANT] read failed: " + e.getMessage());
                        continue;
                    } finally {
                        // Always resume, even if the read above blew up, so the
                        // workload can never be left parked.
                        resumeWorkload();
                    }
                    long pauseMs = (System.nanoTime() - pauseStart) / 1_000_000L;

                    // Lost update: the actual total minus what the committed
                    // transactions between them said they wrote. Nonzero means
                    // some committed transaction's write was overwritten by
                    // another that never saw it.
                    long lostChecking = sumChecking - expectedChecking;
                    long lostSavings = sumSavings - expectedSavings;
                    long lostTotal = lostChecking + lostSavings;

                    samples++;
                    if (negTotal > 0) samplesWithViolation++;
                    maxViolations = Math.max(maxViolations, negTotal);
                    if (lostTotal != 0) samplesWithLostUpdate++;
                    maxLostUpdate = Math.max(maxLostUpdate, Math.abs(lostTotal));

                    out.printf("%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                            tMs, committed, negTotal, negChecking,
                            minTotal, minChecking, minSavings,
                            sumChecking, sumSavings, sumChecking + sumSavings,
                            expectedChecking, expectedSavings, expectedChecking + expectedSavings,
                            lostChecking, lostSavings, lostTotal, pauseMs);
                    out.flush();

                    try {
                        Thread.sleep(invariantSampleIntervalMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } catch (java.io.IOException e) {
                System.out.println("[INVARIANT] failed to write " + invariantCsvPath + ": " + e.getMessage());
                return;
            }
            System.out.printf(" >> [INVARIANT SAMPLES] samples=%d, samplesWithNegativeTotal=%d, maxAccountsNegative=%d %s%n",
                    samples, samplesWithViolation, maxViolations,
                    samplesWithViolation == 0 ? "OK" : "VIOLATED");
            System.out.printf(" >> [INVARIANT SAMPLES] samplesWithLostUpdate=%d, maxAbsLostUpdate=%d %s%n",
                    samplesWithLostUpdate, maxLostUpdate,
                    samplesWithLostUpdate == 0 ? "OK" : "VIOLATED");
            System.out.println(" >> [INVARIANT SAMPLES] wrote " + invariantCsvPath);
        }
    }

    private static long actualTotal(ColumnFamilyHandle cf, int numAccounts) throws RocksDBException {
        long sum = 0;
        for (int id = 0; id < numAccounts; id++) {
            byte[] val = db.get(cf, intToBytes(id));
            if (val != null) {
                sum += bytesToInt(val);
            }
        }
        return sum;
    }

    private static void printInvariantCheck(String name, long expected, long actual) {
        long diff = actual - expected;
        System.out.printf(" >> [%s INVARIANT] expected=%d actual=%d diff=%+d %s%n",
                name, expected, actual, diff, diff == 0 ? "OK" : "VIOLATED");
    }

    // Sum every `diff_applied=N` recorded by commit-time repair, matching
    // sum_diffs.py (PAT = diff_applied=(-?\d+)). Missing files count as 0.
    private static final Pattern DIFF_APPLIED = Pattern.compile("diff_applied=(-?\\d+)");

    private static long sumDiffsFromFile(String path) {
        return sumDiffsFromFile(path, false);
    }

    private static long sumDiffsFromFile(String path, boolean log) {
        File file = new File(path);
        if (!file.isFile()) {
            if (log) {
                System.out.printf("path: %s, sum: 0%n", path);
            }
            return 0;
        }
        long total = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = DIFF_APPLIED.matcher(line);
                if (m.find()) {
                    total += Long.parseLong(m.group(1));
                }
            }
        } catch (IOException e) {
            System.out.println("[DIFFS] failed to read " + path + ": " + e.getMessage());
        }
        if (log) {
            System.out.printf("path: %s, sum: %d%n", path, total);
        }
        return total;
    }

    private static void printPerType(String name, LongAdder count, LongAdder latency, LongAdder aborts) {
        long c = count.sum();
        long n = latency.sum();
        long a = aborts.sum();
        long attempts = c + a;
        double avgMs = c > 0 ? (n / (double) c) / 1_000_000.0 : 0.0;
        double abortRate = attempts > 0 ? (a / (double) attempts) * 100.0 : 0.0;
        System.out.printf("%s: count=%d, avgLatency=%.3f ms, aborts=%d, abortRate=%.2f%%%n",
                name, c, avgMs, a, abortRate);
    }

    // All the random choices a single logical transaction makes, drawn once when
    // the transaction is created so that retries re-execute the identical
    // transaction (same operation, same accounts, same amounts).
    private static class TxnSpec {
        final int op;
        final int id1;
        final int id2;
        final int amount;
        final int delta;
        final int[] depositIds;
        final int depositAmount;

        private TxnSpec(int op, int id1, int id2, int amount, int delta, int[] depositIds, int depositAmount) {
            this.op = op;
            this.id1 = id1;
            this.id2 = id2;
            this.amount = amount;
            this.delta = delta;
            this.depositIds = depositIds;
            this.depositAmount = depositAmount;
        }

        static TxnSpec random(Random rnd, int numAccounts, int[] customOpTypes) {
            // int op = rnd.nextInt(5); // 0..4, uniform
            // int op = rnd.nextInt(2); // 0..3, uniform
            // int op = OP_DEPOSIT_CHECKING;
            // int op = OP_TRANSACT_SAVING;
            // Randomly choose between DepositChecking and Balance
            // int op = rnd.nextBoolean() ? OP_DEPOSIT_CHECKING : OP_BALANCE;

            // Define set of possible random op types.
            int[] possibleOps = {  OP_DEPOSIT_CHECKING, OP_TRANSACT_SAVING, OP_WRITECHECK, OP_BALANCE, OP_AMALGAMATE };
            // int[] possibleOps = { OP_DEPOSIT_CHECKING, OP_WRITECHECK, OP_BALANCE, OP_TRANSACT_SAVING, OP_AMALGAMATE };
            // int[] possibleOps = { OP_DEPOSIT_CHECKING, OP_WRITECHECK, OP_BALANCE };
            // int[] possibleOps = { OP_DEPOSIT_CHECKING, OP_WRITECHECK, OP_TRANSACT_SAVING };
            // int[] possibleOps = { OP_DEPOSIT_CHECKING, OP_WRITECHECK, OP_TRANSACT_SAVING };
            // int[] possibleOps = {  OP_TRANSACT_SAVING, OP_WRITECHECK };
            // int[] possibleOps = {  OP_WRITECHECK };
            // int[] possibleOps = {  OP_AMALGAMATE, OP_DEPOSIT_CHECKING, OP_TRANSACT_SAVING };
            // In invariant-checking mode only the two guarded, single-account
            // operations run, so any negative checking+savings total is a real
            // invariant violation rather than a workload artifact.
            if (invariantMode) {
                possibleOps = new int[] { OP_TRANSACT_SAVING, OP_WRITECHECK };
                // possibleOps = new int[] { OP_WRITECHECK };
                // possibleOps = new int[] { OP_TRANSACT_SAVING };
            }

            if (customOpTypes != null) {
                possibleOps = customOpTypes;
            }
            // Every capped type has used up its budget: nothing left to draw.
            if (allOpsExhausted(possibleOps)) {
                return null;
            }
            // Redraw past types whose cap is exhausted, so the remaining types
            // keep running (and keep their relative mix) until the run ends.
            int op;
            do {
                op = possibleOps[rnd.nextInt(possibleOps.length)];
            } while (opExhausted[op]);

            int id1 = rnd.nextInt(numAccounts);
            int id2 = rnd.nextInt(numAccounts);
            while (id2 == id1) {
                id2 = rnd.nextInt(numAccounts);
            }

            int[] depositIds = new int[DEPOSITS_PER_TXN];
            for (int i = 0; i < depositIds.length; i++) {
                depositIds[i] = rnd.nextInt(numAccounts);
            }

            return new TxnSpec(
                    op,
                    id1,
                    id2,
                    40 + rnd.nextInt(34),      // WriteCheck amount
                    // 80,      // WriteCheck amount (TODO: give this a fixed, but unique value amount?)
                    rnd.nextInt(101) - 25,    // TransactSaving delta, -100..+100
                    // 30,    // TransactSaving delta, -100..+100
                    depositIds,
                    10);                       // DepositChecking amount
        }
    }

    private static class Worker implements Runnable {
        private final int workerId;
        private final int numAccounts;
        private final CountDownLatch latch;
        private final Random rnd;
        private final WriteOptions writeOptions;
        private final TransactionOptions txnOptions;
        private final ReadOptions readOptions;
        private final long networkDelayNanos;

        // Balance deltas written by the attempt currently in flight, per column
        // family. Reset at the start of each attempt and folded into the global
        // committed totals only if that attempt commits, so aborted (and
        // abandoned) attempts contribute nothing.
        private long attemptCheckingDelta;
        private long attemptSavingsDelta;

        Worker(int numAccounts, CountDownLatch latch, long networkDelayMicros, int id) {
            this.workerId = id;
            this.numAccounts = numAccounts;
            this.latch = latch;
            this.rnd = new Random(Thread.currentThread().getId() ^ System.nanoTime());
            this.writeOptions = new WriteOptions();
            this.txnOptions = new TransactionOptions();
            this.readOptions = new ReadOptions();
            this.networkDelayNanos = networkDelayMicros * 1000L;
        }

        // Simulate a single client-server round trip. No-op when disabled.
        private void simulateNetworkDelay() {
            if (networkDelayNanos <= 0) {
                return;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(networkDelayNanos);
        }

        @Override
        public void run() {
            synchronized (pauseLock) {
                activeWorkers++;
            }
            try {
                while (running) {
                    // Park here if the invariant sampler wants the database
                    // quiescent. Always between logical transactions, so no
                    // transaction is ever in flight while a sample is taken.
                    awaitIfPaused();
                    if (!running) {
                        break;
                    }
                    // One logical transaction: its operation type and every random
                    // choice it makes (account ids, amounts) are drawn once here and
                    // reused by every attempt below, so a retry re-executes the same
                    // transaction rather than a fresh random one.
                    // Draw a transaction, then claim a slot against its type's
                    // cap. A lost claim means that type just ran out: redraw, so
                    // the uncapped types keep running.

                    int[] possibleOps = null;
                    // if(workerId == 0 || workerId == 1) {
                    //     possibleOps = new int[] { OP_WRITECHECK };
                    // }
                    // else if(workerId >= 2) {
                    //     possibleOps = new int[] { OP_TRANSACT_SAVING };
                    // }
                    // else{
                    //     // INSERT_YOUR_CODE
                    //     try {
                    //         Thread.sleep(100);
                    //     } catch (InterruptedException e) {
                    //         Thread.currentThread().interrupt();
                    //     }
                    //     continue;

                    // }

                    TxnSpec spec = TxnSpec.random(rnd, numAccounts, possibleOps);

                    if (spec == null) {
                        // Every runnable type is exhausted; end the run.
                        stopRun();
                        break;
                    }
                    if (!claimTxnSlot(spec.op)) {
                        continue;
                    }
                    runLogicalTxn(spec);
                }
            } finally {
                synchronized (pauseLock) {
                    activeWorkers--;
                    // Unblock a sampler waiting for this worker to quiesce.
                    pauseLock.notifyAll();
                }
                writeOptions.close();
                txnOptions.close();
                readOptions.close();
                latch.countDown();
            }
        }

        // Execute one logical transaction, retrying the *same* transaction after a
        // conflict abort until it commits, until we choose to abandon it, or until
        // the run ends. Returns when the logical transaction is resolved either way.
        private void runLogicalTxn(TxnSpec spec) {
            int attempts = 0;
            long logicalStart = System.nanoTime();

            while (running) {
                attempts++;
                long start = System.nanoTime();
                boolean conflict = false;
                boolean overdraft = false;
                boolean repair = false;
                attemptCheckingDelta = 0;
                attemptSavingsDelta = 0;

                try (final OptimisticTransactionOptions txnOptions = new OptimisticTransactionOptions().setSetSnapshot(true);
                    Transaction txn = db.beginTransaction(writeOptions, txnOptions)) {
                        // txn.setSnapshot();
                    execute(txn, spec);

                    // Status.SubCode subCode = e.getStatus().getSubCode();
                    // System.out.println("Transaction failed with subcode: " + subCode);

                    // simulateNetworkDelay();
                    if(spec.op == OP_DEPOSIT_CHECKING) {
                        // txn.rollback();
                    }
                    txn.commit();
                    // Commit-time repair (ABORT_MODE=3) suppressed this
                    // transaction's update as an overdraft: the commit succeeded
                    // but wrote back the value it read, so the delta this
                    // attempt recorded was not applied. Repair signals that by
                    // stashing a marker in the transaction name.
                    overdraft = OVERDRAFT_MARKER.equals(txn.getName());
                    repair = REPAIR_MARKER.equals(txn.getName());
                } catch (RocksDBException e) {
                    // Only count optimistic-concurrency conflicts as aborts.
                    // Commit conflicts surface as Busy (and occasionally TryAgain);
                    // anything else is a genuine error, not a conflict abort.
                    Status.Code code = e.getStatus() != null ? e.getStatus().getCode() : null;
                    // System.out.println("Transaction failed with status code: " + code);

                    

                    if (code == Status.Code.Busy || code == Status.Code.TryAgain) {
                        conflict = true;
                        totalAborts.increment();
                        abortCounter(spec.op).increment();
                    } else {
                        totalErrors.increment();
                        // System.out.println("[ERROR]Transaction failed: " + code);
                        // A genuine error is not retryable; give up on this
                        // logical transaction.
                        return;
                    }
                }

                if (!conflict) {
                    long dur = System.nanoTime() - start;
                    totalTxns.increment();
                    totalLatencyNanos.add(dur);
                    countCounter(spec.op).increment();
                    latencyCounter(spec.op).add(dur);

                    // This attempt's writes are now durable; fold the amounts it
                    // actually applied into the expected-total accumulators.
                    // A repair-suppressed (overdraft) commit applied nothing.
                    if (overdraft || repair) {
                        if(overdraft){
                            totalOverdrafts.increment();
                        }
                        // System.out.println("Overdraft committed, op: " + spec.op);
                    } else {
                        committedCheckingDelta.add(attemptCheckingDelta);
                        committedSavingsDelta.add(attemptSavingsDelta);
                    }

                    // Per-logical-transaction stats: includes every retried attempt.
                    totalAttempts.add(attempts);
                    totalLogicalLatencyNanos.add(System.nanoTime() - logicalStart);
                    return;
                }

                // Abandon a logical transaction that keeps losing rather than
                // retrying it forever; a real client would surface the failure.
                if (attempts >= MAX_TXN_ATTEMPTS) {
                    totalAbandoned.increment();
                    totalAttempts.add(attempts);
                    return;
                }

                // Back off before retrying, scaled to how long the failed attempt
                // itself took. Retrying immediately just puts the loser back at the
                // commit point in lockstep with whoever beat it, so the same pair
                // collides again.
                if (useBackoff) {
                    backoff(attempts, System.nanoTime() - start);
                }
            }
        }

        private void execute(Transaction txn, TxnSpec spec) throws RocksDBException {
            switch (spec.op) {
                case OP_BALANCE:
                    for(int i = 0; i < spec.depositIds.length; i++) {
                        doBalance(txn, spec.depositIds[i]);
                    }
                    // doBalance(txn, spec);
                    break;
                case OP_WRITECHECK:
                    doWriteCheck(txn, spec);
                    break;
                case OP_DEPOSIT_CHECKING:
                    for (int i = 0; i < spec.depositIds.length; i++) {
                        doDepositChecking(txn, spec.depositIds[i], spec.depositAmount);
                    }
                    break;
                case OP_TRANSACT_SAVING:
                    doTransactSaving(txn, spec);
                    break;
                case OP_AMALGAMATE:
                    doAmalgamate(txn, spec);
                    break;
            }
        }

        // Randomized exponential backoff after a conflict abort.
        //
        // The base delay is the duration of the attempt that just failed, since
        // that is the natural timescale of the conflict window: under classic SI
        // (ABORT_MODE=1) the conflict is not detected until commit, so a loser
        // has already burned a full transaction's worth of work and will re-race
        // the winner immediately unless it waits on that same scale. Full jitter
        // (uniform over [0, cap)) decorrelates threads that aborted together.
        private void backoff(int consecutiveAborts, long lastAttemptNanos) {
            long base = Math.max(lastAttemptNanos, BACKOFF_MIN_BASE_NANOS);
            int shift = Math.min(consecutiveAborts - 1, BACKOFF_MAX_SHIFT);
            long cap = Math.min(base << shift, BACKOFF_CAP_NANOS);
            long delay = (long) (cap * rnd.nextDouble());
            if (delay > 0) {
                java.util.concurrent.locks.LockSupport.parkNanos(delay);
            }
        }

        private void doBalance(Transaction txn, int id) throws RocksDBException {
            int checking = getBalance(txn, cfChecking, id);
            int savings = getBalance(txn, cfSavings, id);
            int total = checking + savings;
            // read-only txn; result not used
        }

        private void doDepositChecking(Transaction txn, int id, int amount) throws RocksDBException {
            // Can we simulate the reading of 200 other account values here.
            // For each account, we need to read the value.
            int maxAccounts = numAccounts; // assuming numAccounts is visible here and means the actual number of accounts in the benchmark
            // int numReads = Math.min(5, maxAccounts);
            // for (int i = 0; i < numReads; i++) {
            //     int accountId = i;
            //     int balance = getBalance(txn, cfChecking, accountId);
            // }

            int checking = getBalance(txn, cfChecking, id);
            int newBal = checking + amount;

            byte[][] keyDeps = new byte[][] { intToBytes(id) };
            int[] depColumnFamilyIds = new int[] { cfChecking.getID() };

            recordAppliedDelta(cfChecking, newBal - checking);
            putBalanceWithMeta(txn, cfChecking, id, newBal, OP_DEPOSIT_CHECKING, keyDeps, depColumnFamilyIds, amount);
        }

        private void doTransactSaving(Transaction txn, TxnSpec spec) throws RocksDBException {
            int id = spec.id1;
            int savings = getBalance(txn, cfSavings, id);
            int newBal = savings + spec.delta;


            int checking = getBalance(txn, cfChecking, id);
            int total = checking + savings;

            // GUARD: don't allow overdrafts on total account balance.
            if(newBal + checking < 0){
                newBal = savings;
            }

            // INSERT_YOUR_CODE
            // try {
            //     // INSERT_YOUR_CODE
            //     int randomAmount = 1 + (int)(Math.random() * 5);
            //     Thread.sleep(randomAmount);
            // } catch (InterruptedException e) {
            //     Thread.currentThread().interrupt();
            // }
    

            // Tag the delta actually applied: zero when the clamp above turned
            // this into a no-op, so repair reproduces the branch we took here.
            long appliedDelta = newBal - savings;

            recordAppliedDelta(cfSavings, appliedDelta);

            byte[][] keyDeps = new byte[][] { intToBytes(id), intToBytes(id) };
            int[] depColumnFamilyIds = new int[] { cfSavings.getID(), cfChecking.getID() };
            putBalanceWithMeta(txn, cfSavings, id, newBal, OP_TRANSACT_SAVING, keyDeps, depColumnFamilyIds, spec.delta);
        }

        private void doAmalgamate(Transaction txn, TxnSpec spec) throws RocksDBException {
            int id1 = spec.id1;
            int id2 = spec.id2;

            int checking1 = getBalance(txn, cfChecking, id1);
            int savings1 = getBalance(txn, cfSavings, id1);
            int checking2 = getBalance(txn, cfChecking, id2);

            int total = checking1 + savings1;


            // Stores column family ids for checking and savings.
            int[] emptyCfids = new int[0];
            int[] cfids = new int[] { cfChecking.getID(), cfSavings.getID(), cfChecking.getID() };

            // zero out first customer's accounts
            // INSERT_YOUR_CODE
            // An empty byte array for use as a dependency placeholder
            byte[][] emptyDeps = new byte[0][];

            recordAppliedDelta(cfChecking, -checking1);
            recordAppliedDelta(cfSavings, -savings1);
            putBalanceWithMeta(txn, cfChecking, id1, 0, OP_AMALGAMATE_ZERO_BALANCE, emptyDeps, emptyCfids);
            putBalanceWithMeta(txn, cfSavings, id1, 0, OP_AMALGAMATE_ZERO_BALANCE, emptyDeps, emptyCfids);


            recordAppliedDelta(cfChecking, total);

            byte[][] keyDeps = new byte[][] { intToBytes(id1), intToBytes(id1), intToBytes(id2) };
            putBalanceWithMeta(txn, cfChecking, id2, checking2 + total, OP_AMALGAMATE, keyDeps, cfids);
        }

        private void doWriteCheck(Transaction txn, TxnSpec spec) throws RocksDBException {
            int id = spec.id1;
            int amount = spec.amount;

            int checking = getBalance(txn, cfChecking, id);
            int savings = getBalance(txn, cfSavings, id);
            int total = checking + savings;

            // Plain update without check for overdraft.
            int newChecking = checking - amount;

            // Stricter version of the benchmark i.e. don't allow overdrafts at all if total account couldn't cover it.
            // GUARD: don't allow overdrafts on total account balance.
            if(amount > total){
                newChecking = checking;
            }

            // try {
            //     // INSERT_YOUR_CODE
            //     int randomAmount = 1 + (int)(Math.random() * 5);
            //     Thread.sleep(randomAmount);
            // } catch (InterruptedException e) {
            //     Thread.currentThread().interrupt();
            // }
    
            
            // // if (total < amount) {
            // if (false) {
            //     // overdraft: subtract amount + 1 from checking
            //     newChecking = checking - (amount + 0);
            // } else {
            //     newChecking = checking - amount;
            // }

            // Include meta deps, plus the amount actually withdrawn (0 when the
            // insufficient-funds branch above made this a no-op), so repair
            // recomputes newChecking as (repaired checking - appliedAmount).
            long appliedAmount = checking - newChecking;

            recordAppliedDelta(cfChecking, -appliedAmount);

            // INSERT_YOUR_CODE
            // System.out.printf("cfChecking id: %d (cfChecking)%n", cfChecking.getID());
            // System.out.printf("cfSavings id: %d (cfSavings)%n", cfSavings.getID());
            // System.out.printf("cfDefault id: %d (cfDefault)%n", cfDefault.getID());
            // System.out.printf("\n");
    

            byte[][] keyDeps = new byte[][] { intToBytes(id), intToBytes(id) };
            int[] depColumnFamilyIds = new int[] { cfChecking.getID(), cfSavings.getID() };
            putBalanceWithMeta(txn, cfChecking, id, newChecking, OP_WRITECHECK, keyDeps, depColumnFamilyIds, amount);
        }

        private int getBalance(Transaction txn, ColumnFamilyHandle cf, int customerId) throws RocksDBException {
            byte[] key = intToBytes(customerId);
            // Atomic snapshot (0) and classic SI (1) don't need to track read conflicts.
            byte[] val = (abortMode == 0 || abortMode == 1)
                ? txn.get(readOptions, cf, key)
                : txn.getForUpdate(readOptions, cf, key, true);
       
            simulateNetworkDelay();
            if (val == null) {
                return 0;
            }
            return bytesToInt(val);
        }

        // Record the balance change a write in the current attempt applies, so
        // committed attempts can be summed into the expected totals. `delta` is
        // (value written - value read), i.e. zero for a write that a guard turned
        // into a no-op.
        private void recordAppliedDelta(ColumnFamilyHandle cf, long delta) {
            if (cf == cfChecking) {
                attemptCheckingDelta += delta;
            } else if (cf == cfSavings) {
                attemptSavingsDelta += delta;
            }
        }

        private void putBalance(Transaction txn, ColumnFamilyHandle cf, int customerId, int balance) throws RocksDBException {
            byte[] key = intToBytes(customerId);
            byte[] val = intToBytes(balance);
            txn.put(cf, key, val);
            simulateNetworkDelay();
        }

        private void putBalanceWithMeta(Transaction txn, ColumnFamilyHandle cf, int customerId, int balance, int opType, byte[][] deps, int[] depColumnFamilyIds) throws RocksDBException {
            putBalanceWithMeta(txn, cf, customerId, balance, opType, deps, depColumnFamilyIds, 0);
        }

        // As above, but also tags the write with the constant operand of its
        // update expression (the WriteCheck amount, the TransactSaving delta,
        // ...) so commit-time repair can recompute the value without having to
        // hard-code the constant natively.
        private void putBalanceWithMeta(Transaction txn, ColumnFamilyHandle cf, int customerId, int balance, int opType, byte[][] deps, int[] depColumnFamilyIds, long amount) throws RocksDBException {
            byte[] key = intToBytes(customerId);
            byte[] val = intToBytes(balance);
            txn.putWithMeta(cf, key, val, opType, deps, depColumnFamilyIds, amount);
            simulateNetworkDelay();
        }
    }

    private static byte[] intToBytes(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array();
    }

    private static int bytesToInt(byte[] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getInt();
    }
}