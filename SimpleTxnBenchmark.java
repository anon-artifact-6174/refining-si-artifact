// Copyright (c) 2011-present, Facebook, Inc.  All rights reserved.
//  This source code is licensed under both the GPLv2 (found in the
//  COPYING file in the root directory) and Apache 2.0 License
//  (found in the LICENSE.Apache file in the root directory).
package my.test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.rocksdb.InfoLogLevel;
import org.rocksdb.Logger;
import org.rocksdb.OptimisticTransactionDB;
import org.rocksdb.OptimisticTransactionOptions;
import org.rocksdb.Options;
import org.rocksdb.TransactionDBOptions;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDBException;
import org.rocksdb.Snapshot;
import org.rocksdb.Transaction;
import org.rocksdb.WriteOptions;

class RocksDbSlf4jLogger extends Logger {

    // private static final Logger logger = LoggerFactory.getLogger(RocksDbSlf4jLogger.class);
    public RocksDbSlf4jLogger(InfoLogLevel logLevel) {
        super(logLevel);
    }

    @Override
    protected void log(InfoLogLevel logLevel, String message) {
        System.out.println(message);
        // switch (logLevel) {
        //     case FATAL_LEVEL:
        //     case ERROR_LEVEL:
        //         logger.error(message);
        //         break;

        //     case WARN_LEVEL:
        //         logger.warn(message);
        //         break;
        //     case INFO_LEVEL:
        //         logger.info(message);
        //         break;
        //     case DEBUG_LEVEL:
        //         logger.debug(message);
        //         break;
        //     default:
        //         log4jLogger.trace(message);
        // }
    }

}

// https://github.com/ligurio/elle-cli?tab=readme-ov-file#supported-models
enum JepsenEventModelType {
    None,
    RWRegister,
    ListAppend,
    Bank
}

/**
 * Demonstrates using Transactions on an OptimisticTransactionDB with varying
 * isolation guarantees
 */
public class SimpleTxnBenchmark {

    private static final String dbPath = "dbdata";

    // Benchmark configuration
    private static final int DEFAULT_NUM_CLIENTS = 10;
    private static final int DEFAULT_KEYS_PER_TRANSACTION = 50;
    private static final double DEFAULT_READ_RATIO = 0.5;
    private static final int DEFAULT_KEYSPACE_SIZE = 1000000;
    private static int DURATION_SECONDS = 45;
    private static final String BENCHMARK_CSV_FILE = "benchmark_results.csv";
    private static double[] read_ratios = {0.2, 0.5, 0.8};
    // private static final double[] read_ratios = {0.5};
    private static boolean mixedWorkload = false;
    private static boolean logEvents = false;
//   private static boolean logEvents = false;
    private static ConcurrentLinkedQueue<String> currEventLog = new ConcurrentLinkedQueue<>();
    private static final AtomicLong globalTransactionCounter = new AtomicLong(0);
    private static JepsenEventModelType jepsenEventModelType = JepsenEventModelType.None;
    private static int keyspaceSize = DEFAULT_KEYSPACE_SIZE;
    private static boolean runWarmup = true;
    private static int maxTransactions = 100000000;
    private static int LIST_APPEND_CLIENT_COUNT = 16;
    private static int ABORT_MODE = 0;

    // Workload parameters, all overridable from the command line (see usage()).
    private static int keysPerTransaction = DEFAULT_KEYS_PER_TRANSACTION;
    private static int[] clientCounts = {1, 2, 4, 6, 12, 16, 24};
    private static double[] zipfSkews = {0.85};

    // Simulated client<->server network delay, applied once per operation
    // round trip (each read, each write, and the commit).
    private static long networkDelayMicros = 0;

    /**
     * Sleep for the configured per-operation network delay. parkNanos has
     * millisecond-ish granularity on Linux, which swamps the small delays we
     * care about here, so spin for short delays and park for longer ones.
     */
    private static void simulateNetworkDelay() {
        long delayNanos = networkDelayMicros * 1000L;
        if (delayNanos <= 0) {
            return;
        }
        if (delayNanos < 100_000L) { // < 100us: busy-wait for accuracy.
            long deadline = System.nanoTime() + delayNanos;
            while (System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            return;
        }
        java.util.concurrent.locks.LockSupport.parkNanos(delayNanos);
    }

    private static int[] parseIntList(String arg) {
        String[] parts = arg.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }

    private static double[] parseDoubleList(String arg) {
        String[] parts = arg.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Double.parseDouble(parts[i].trim());
        }
        return out;
    }

    private static void usage() {
        System.out.println("Usage: SimpleTxnBenchmark [options]");
        System.out.println("  --clients <n,n,...>       client counts to sweep (default: 1,2,4,6,12,16,24)");
        System.out.println("  --read_ratios <r,r,...>   read ratios to sweep (default: 0.2,0.5,0.8)");
        System.out.println("  --zipf_skews <s,s,...>    zipf skews to sweep, <=0.01 disables zipf (default: 0.85)");
        System.out.println("  --keysPerTxn <n>          keys touched per transaction (default: " + DEFAULT_KEYS_PER_TRANSACTION + ")");
        System.out.println("  --keyspaceSize <n>        total keyspace size (default: " + DEFAULT_KEYSPACE_SIZE + ")");
        System.out.println("  --duration <secs>         run duration per config (default: 45)");
        System.out.println("  --networkDelayMicros <n>  simulated client/server delay per op round trip (default: 0)");
        System.out.println("  --maxTransactions <n>     stop a run after n committed txns");
        System.out.println("  --mixedWorkload           sample read ratio per txn instead of sweeping");
        System.out.println("  --noWarmup                skip the warmup phase");
        System.out.println("  --jepsenEventModelType <t> None|Bank|ListAppend|RWRegister");
        System.out.println("  --help                    show this message");
    }

    public static final void main(final String args[]) throws RocksDBException {
        // Set up for writing CSV results
        boolean csvExists = new File(BENCHMARK_CSV_FILE).exists();
        if (csvExists) {
            new File(BENCHMARK_CSV_FILE).delete();
            csvExists = false;
        }

        // Parse boolean/flag-only command line options.
        for (String arg : args) {
            if (arg.equals("--mixedWorkload")) {
                mixedWorkload = true;
            }
            if (arg.equals("--noWarmup")) {
                runWarmup = false;
            }
            if (arg.equals("--help") || arg.equals("-h")) {
                usage();
                return;
            }
        }
        // Parse '--duration <duration_secs>' command line option
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--duration")) {
                try {
                    DURATION_SECONDS = Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid duration specified: " + args[i + 1]);
                }
                continue;
            }
            if (args[i].equals("--jepsenEventModelType")) {
                jepsenEventModelType = JepsenEventModelType.valueOf(args[i + 1]);
                continue;
            }
            if (args[i].equals("--maxTransactions")) {
                maxTransactions = Integer.parseInt(args[i + 1]);
                continue;
            }
            if (args[i].equals("--read_ratios")) {
                read_ratios = parseDoubleList(args[i + 1]);
                continue;
            }
            if (args[i].equals("--zipf_skews")) {
                zipfSkews = parseDoubleList(args[i + 1]);
                continue;
            }
            if (args[i].equals("--clients")) {
                clientCounts = parseIntList(args[i + 1]);
                continue;
            }
            if (args[i].equals("--keysPerTxn")) {
                keysPerTransaction = Integer.parseInt(args[i + 1]);
                continue;
            }
            if (args[i].equals("--keyspaceSize")) {
                keyspaceSize = Integer.parseInt(args[i + 1]);
                continue;
            }
            if (args[i].equals("--networkDelayMicros")) {
                networkDelayMicros = Long.parseLong(args[i + 1]);
                continue;
            }
        }


        String abortModeEnv = System.getenv("ABORT_MODE");
        if (abortModeEnv != null) {
            try {
                ABORT_MODE = Integer.parseInt(abortModeEnv);
            } catch (NumberFormatException e) {
                ABORT_MODE = 0;
            }
            System.out.println("Running in abort mode: " + ABORT_MODE);
        }

        // final Logger slf4jLogger = new RocksDbSlf4jLogger(InfoLogLevel.INFO_LEVEL);
        try (final Options options = new Options()
                .setCreateIfMissing(true).setAllowConcurrentMemtableWrite(true); final OptimisticTransactionDB txnDb
                = OptimisticTransactionDB.open(options, dbPath); final FileWriter csvWriter = new FileWriter(BENCHMARK_CSV_FILE, true)) {
            // Write header only if file is new
            if (!csvExists) {
                csvWriter.write("clients,keys_per_txn,read_ratio,zipf,zipf_skew,keyspace,duration_sec,actual_duration_sec,"
                        + "total_transactions,successful_transactions,failed_transactions,success_rate,"
                        + "transactions_per_sec,operations_per_sec,reads,writes,read_pct,write_pct,mean_latency_ms\n");
                csvWriter.flush();
            }

            try (final WriteOptions writeOptions = new WriteOptions(); final ReadOptions readOptions = new ReadOptions()) {

                //
                // Simple OptimisticTransaction Example ("Read Committed")
                //
                // readCommitted(txnDb, writeOptions, readOptions);
                //
                // "Repeatable Read" (Snapshot Isolation) Example
                //   -- Using a single Snapshot
                //
                // repeatableRead(txnDb, writeOptions, readOptions);
                //
                // "Read Committed" (Monotonic Atomic Views) Example
                //   --Using multiple Snapshots
                //
                // readCommitted_monotonicAtomicViews(txnDb, writeOptions, readOptions);
                //
                // Benchmark with concurrent clients
                //
                ////////////////////////////////////////////////////////
                // Sweep dimensions come from the command line (see usage()).
                if(jepsenEventModelType == JepsenEventModelType.ListAppend || jepsenEventModelType == JepsenEventModelType.RWRegister){
                    clientCounts = new int[]{LIST_APPEND_CLIENT_COUNT};
                }
                boolean useZipf = true;
                for (int numClients : clientCounts) {
                    for (double zipfSkew : zipfSkews) {

                        if(jepsenEventModelType == JepsenEventModelType.Bank || jepsenEventModelType == JepsenEventModelType.ListAppend || jepsenEventModelType == JepsenEventModelType.RWRegister){
                            logEvents = true;
                            System.out.println("\n Starting Jepsen event model test, Mode: " + jepsenEventModelType.toString() + "");
                            if(jepsenEventModelType == JepsenEventModelType.Bank){
                                keyspaceSize = 1000; // 10,000 accounts.
                            }
                        }

                        if (mixedWorkload) {
                            System.out.println("\n=== Starting Mixed Workload Benchmark with " + numClients + " client(s) (MIXED WORKLOAD) (maxTransactions: " + maxTransactions + ") ===");
                            useZipf = (zipfSkew > 0.01);
                            BenchmarkResult result = runConcurrentBenchmark(txnDb, writeOptions, readOptions, numClients, useZipf, zipfSkew, 0.0);
                            writeBenchmarkResultToCSV(csvWriter, result, useZipf, zipfSkew, 0.0);
                        } else {
                            for (double readRatio : read_ratios) {
                                System.out.println("\n=== Starting Concurrent Benchmark with " + numClients + " client(s) ===");
                                useZipf = (zipfSkew > 0.01);
                                BenchmarkResult result = runConcurrentBenchmark(txnDb, writeOptions, readOptions, numClients, useZipf, zipfSkew, readRatio);
                                writeBenchmarkResultToCSV(csvWriter, result, useZipf, zipfSkew, readRatio);
                            }
                        }
                    }
                }
            }
        } catch (IOException ioe) {
            System.err.println("Error writing benchmark results CSV: " + ioe);
        }
    }

    private static void writeBenchmarkResultToCSV(FileWriter csvWriter, BenchmarkResult result, boolean useZipf, double zipfSkew, double readRatio) throws IOException {
        csvWriter.write(
                result.clients + ","
                + result.keysPerTransaction + ","
                + String.format("%.2f", readRatio) + ","
                + // read ratio used by the run (from result)
                (useZipf ? "1" : "0") + ","
                + // Zipf parameter: 1 = true, 0 = false
                String.format("%.2f", zipfSkew) + ","
                + // zipf skew value
                result.keyspaceSize + ","
                + result.durationSeconds + ","
                + String.format("%.2f", result.actualDurationSeconds) + ","
                + result.totalTransactions + ","
                + result.successfulTransactions + ","
                + result.failedTransactions + ","
                + String.format("%.2f", result.successRate) + ","
                + String.format("%.2f", result.transactionsPerSecond) + ","
                + String.format("%.2f", result.operationsPerSecond) + ","
                + result.totalReads + ","
                + result.totalWrites + ","
                + String.format("%.2f", result.readPct) + ","
                + String.format("%.2f", result.writePct) + ","
                + String.format("%.2f", result.meanLatencyMs)
                + "\n"
        );
        csvWriter.flush();
    }

    // Representation for benchmark metrics for one run
    private static class BenchmarkResult {

        int clients;
        int keysPerTransaction;
        double readRatio;
        int keyspaceSize;
        int durationSeconds;
        double actualDurationSeconds;
        long totalTransactions;
        long successfulTransactions;
        long failedTransactions;
        double successRate;
        double transactionsPerSecond;
        double operationsPerSecond;
        long totalReads;
        long totalWrites;
        double readPct;
        double writePct;
        double meanLatencyMs;
    }

    /**
     * Demonstrates "Read Committed" isolation
     */
    private static void readCommitted(final OptimisticTransactionDB txnDb,
            final WriteOptions writeOptions, final ReadOptions readOptions)
            throws RocksDBException {
        final byte key1[] = "abc".getBytes(UTF_8);
        final byte value1[] = "def".getBytes(UTF_8);

        final byte key2[] = "xyz".getBytes(UTF_8);
        final byte value2[] = "zzz".getBytes(UTF_8);

        // Start a transaction
        try (final Transaction txn = txnDb.beginTransaction(writeOptions)) {
            // Read a key in this transaction
            byte[] value = txn.get(readOptions, key1);
            assert (value == null);

            // Write a key in this transaction
            System.out.println("Writing key1: " + new String(key1, UTF_8) + " with value: " + new String(value1, UTF_8));
            txn.put(key1, value1);

            // Read a key OUTSIDE this transaction. Does not affect txn.
            value = txnDb.get(readOptions, key1);
            System.out.println("Reading key1: " + new String(key1, UTF_8) + " with value: " + new String(value, UTF_8));
            assert (value == null);

            // Write a key OUTSIDE of this transaction.
            // Does not affect txn since this is an unrelated key.
            // If we wrote key 'abc' here, the transaction would fail to commit.
            txnDb.put(writeOptions, key2, value2);

            // Commit transaction
            txn.commit();
        }
    }

    /**
     * Demonstrates "Repeatable Read" (Snapshot Isolation) isolation
     */
    private static void repeatableRead(final OptimisticTransactionDB txnDb,
            final WriteOptions writeOptions, final ReadOptions readOptions)
            throws RocksDBException {

        final byte key1[] = "key1".getBytes(UTF_8);
        final byte key2[] = "key2".getBytes(UTF_8);
        final byte key3[] = "key3".getBytes(UTF_8);

        final byte value1[] = "jkl".getBytes(UTF_8);
        final byte value2[] = "435".getBytes(UTF_8);
        final byte value3[] = "789".getBytes(UTF_8);

        // Set a snapshot at start of transaction by setting setSnapshot(true)
        try (final OptimisticTransactionOptions txnOptions
                = new OptimisticTransactionOptions().setSetSnapshot(true); final Transaction txn
                = txnDb.beginTransaction(writeOptions, txnOptions); final Transaction txn2
                = txnDb.beginTransaction(writeOptions, txnOptions)) {

            //   final Snapshot snapshot = txn.getSnapshot();
            //   readOptions.setSnapshot(snapshot);
            txn.setSnapshot();
            readOptions.setSnapshot(txn.getSnapshot());

            // Txn1.
            txn.put(key1, value1);
            txn.put(key3, value1);
            // txn2.put(key1, value1); 
            // byte[] value = txn.get(readOptions, key2);
            byte[] value = txn.getForUpdate(readOptions, key2, true);

            // Txn2.
            System.out.println("Txn2 write(key2)");
            txn2.put(key2, value2);
            txn2.put(key3, value3);

            System.out.println("Txn2 commit");
            txn2.commit();

            try {
                System.out.println("Txn1 commit");
                txn.commit();
                System.out.println("Txn1 committed OK");
            } catch (RocksDBException e) {
                System.out.println("[ERROR]Transaction 1 failed: " + e.getStatus().getCode());
            }
            // txn2.commit();
        } finally {
            // Clear snapshot from read options since it is no longer valid
            readOptions.setSnapshot(null);
        }
    }

    /**
     * Demonstrates "Read Committed" (Monotonic Atomic Views) isolation
     *
     * In this example, we set the snapshot multiple times. This is probably
     * only necessary if you have very strict isolation requirements to
     * implement.
     */
    private static void readCommitted_monotonicAtomicViews(
            final OptimisticTransactionDB txnDb, final WriteOptions writeOptions,
            final ReadOptions readOptions) throws RocksDBException {

        final byte keyX[] = "x".getBytes(UTF_8);
        final byte valueX[] = "x".getBytes(UTF_8);

        final byte keyY[] = "y".getBytes(UTF_8);
        final byte valueY[] = "y".getBytes(UTF_8);

        try (final OptimisticTransactionOptions txnOptions
                = new OptimisticTransactionOptions().setSetSnapshot(true); final Transaction txn
                = txnDb.beginTransaction(writeOptions, txnOptions)) {

            // Do some reads and writes to key "x"
            Snapshot snapshot = txnDb.getSnapshot();
            readOptions.setSnapshot(snapshot);
            byte[] value = txn.get(readOptions, keyX);
            txn.put(valueX, valueX);

            // Do a write outside of the transaction to key "y"
            txnDb.put(writeOptions, keyY, valueY);

            // Set a new snapshot in the transaction
            txn.setSnapshot();
            snapshot = txnDb.getSnapshot();
            readOptions.setSnapshot(snapshot);

            // Do some reads and writes to key "y"
            // Since the snapshot was advanced, the write done outside of the
            // transaction does not conflict.
            value = txn.getForUpdate(readOptions, keyY, true);
            txn.put(keyY, valueY);

            // Commit.  Since the snapshot was advanced, the write done outside of the
            // transaction does not prevent this transaction from Committing.
            txn.commit();

        } finally {
            // Clear snapshot from read options since it is no longer valid
            readOptions.setSnapshot(null);
        }
    }

    /**
     * Runs a concurrent benchmark with N clients performing transactions with
     * configurable read/write ratios on a random keyspace
     *
     * Returns the metrics for CSV output.
     */
    private static BenchmarkResult runConcurrentBenchmark(final OptimisticTransactionDB txnDb,
            final WriteOptions writeOptions, final ReadOptions readOptions, int numClients, boolean useZipf, double zipfSkew, double readRatio)
            throws RocksDBException {

        // Benchmark parameters, configured from the command line.
        int keysPerTransaction = SimpleTxnBenchmark.keysPerTransaction;
        int keyspaceSize = SimpleTxnBenchmark.keyspaceSize;
        int durationSeconds = DURATION_SECONDS;

        System.out.println("Benchmark Configuration:");
        System.out.println("  Abort mode: " + ABORT_MODE);
        System.out.println("  Clients: " + numClients);
        System.out.println("  Keys per transaction: " + keysPerTransaction);
        System.out.println("  Read ratio: " + (readRatio * 100) + "%");
        System.out.println("  Keyspace size: " + keyspaceSize);
        System.out.println("  Duration: " + durationSeconds + " seconds");
        System.out.println("  Simulated network delay per op (us): " + networkDelayMicros);
        System.out.println();

        // Performance counters
        final AtomicLong totalTransactions = new AtomicLong(0);
        final AtomicLong successfulTransactions = new AtomicLong(0);
        final AtomicLong failedTransactions = new AtomicLong(0);
        final AtomicLong totalOperations = new AtomicLong(0);
        final AtomicLong totalReads = new AtomicLong(0);
        final AtomicLong totalWrites = new AtomicLong(0);
        final AtomicLong sumLatencyMs = new AtomicLong(0); // total latency of all transactions in ms

        // Create thread pool
        ExecutorService executor = Executors.newFixedThreadPool(numClients);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numClients);

        // Start time tracking
        long startTime = System.currentTimeMillis();
        long endTime = startTime + (durationSeconds * 1000);

        // ZipfGenerator zipfRandom = new ZipfGenerator(keyspaceSize, 0.99);
        //   ZipfGenerator zipfRandom = new ZipfGenerator(keyspaceSize, zipfSkew);
        Random zrandom = new Random(System.currentTimeMillis());
        ZipfianGenerator zipfianRandom = new ZipfianGenerator(keyspaceSize, zipfSkew);


        // If the jepsen event model is Bank, initialize all keys in the keyspace with value 100
        if (jepsenEventModelType == JepsenEventModelType.Bank) {
            System.out.println("Initializing Bank accounts (setting each key to 100)...");
            try {
                for (int i = 0; i < keyspaceSize; i++) {
                    String key = "k" + i;
                    byte[] keyBytes = key.getBytes(UTF_8);
                    byte[] valueBytes = Long.toString(100L).getBytes(UTF_8);
                    txnDb.put(writeOptions, keyBytes, valueBytes);
                }
                System.out.println("Bank key initialization complete.");
            } catch (RocksDBException e) {
                System.err.println("Error initializing bank keys: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }

        //
        // WARMUP PHASE
        //
        final int WARMUP_SECONDS = 10;
        if (runWarmup) {
            System.out.println("Running warmup for " + WARMUP_SECONDS + " seconds...");
            CountDownLatch warmupEndLatch = new CountDownLatch(numClients);
            long warmupStartTime = System.currentTimeMillis();
            long warmupEndTime = warmupStartTime + (WARMUP_SECONDS * 1000);

            for (int clientId = 0; clientId < numClients; clientId++) {
                final int finalClientId = clientId;
                executor.submit(() -> {
                    try {
                        // No startLatch for warmup, just start
                        Random random = new Random(finalClientId + warmupStartTime);
                        while (System.currentTimeMillis() < warmupEndTime) {
                            try {
                                long warmupIndex = globalTransactionCounter.incrementAndGet();
                                performBenchmarkTransaction(txnDb, writeOptions, readOptions,
                                        finalClientId, keysPerTransaction, readRatio,
                                        random, zipfianRandom, useZipf, null, null, null, warmupIndex); // Don't record stats
                            } catch (RocksDBException e) {
                                // Ignore failures in warmup
                            }
                        }
                    } finally {
                        warmupEndLatch.countDown();
                    }
                });
            }

            try {
                warmupEndLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.println("Warmup complete.");
        }


        //
        // Launch client threads for main workload measurement phase.
        //
        for (int clientId = 0; clientId < numClients; clientId++) {
            final int finalClientId = clientId;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all clients to be ready

                    Random random = new Random(finalClientId + System.currentTimeMillis());
                    while (System.currentTimeMillis() < endTime && successfulTransactions.get() < maxTransactions) {
                        long txnStart = System.nanoTime();
                        // Get the global transaction index for this transaction
                        long transactionIndex = globalTransactionCounter.incrementAndGet();
                        try {
                            // Perform one transaction
                            // INSERT_YOUR_CODE
                            // Randomly choose keysPerTransaction as either 10 or 40 with 50/50 probability
                            int sampledKeysPerTransaction = keysPerTransaction;

                            // For ListAppend, randomly choose a number of keys between 2 and 10.
                            if(jepsenEventModelType == JepsenEventModelType.ListAppend || jepsenEventModelType == JepsenEventModelType.RWRegister){
                                sampledKeysPerTransaction = random.nextInt(2, 10);
                            }

                            double chosenReadRatio = readRatio; // default read ratio
                            if (mixedWorkload) {
                                // Choose a read ratio from the list uniformly at random
                                chosenReadRatio = read_ratios[random.nextInt(read_ratios.length)];
                            }

                            performBenchmarkTransaction(txnDb, writeOptions, readOptions,
                                    finalClientId, sampledKeysPerTransaction, chosenReadRatio,
                                    random, zipfianRandom, useZipf, totalOperations, totalReads, totalWrites, transactionIndex);

                            // Only count latency of successful transactions.
                            long txnEnd = System.nanoTime();
                            long txnLatencyMs = (txnEnd - txnStart);// / 1_000_000L;
                            sumLatencyMs.addAndGet(txnLatencyMs);

                            successfulTransactions.incrementAndGet();
                        } catch (RocksDBException e) {
                            //   long txnEnd = System.nanoTime();
                            //   long txnLatencyMs = (txnEnd - txnStart);// / 1_000_000L;
                            //   sumLatencyMs.addAndGet(txnLatencyMs);

                            failedTransactions.incrementAndGet();
                            // Continue with next transaction
                        }
                        totalTransactions.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Start the benchmark
        startLatch.countDown();

        try {
            // Wait for all clients to finish
            endLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        executor.shutdown();

        // Calculate and report results
        long actualDuration = System.currentTimeMillis() - startTime;
        double actualDurationSeconds = actualDuration / 1000.0;

        long totalTrans = totalTransactions.get();
        long succTrans = successfulTransactions.get();
        long failTrans = failedTransactions.get();
        long ops = totalOperations.get();
        long reads = totalReads.get();
        long writes = totalWrites.get();
        double succRate = (totalTrans == 0) ? 0.0 : ((double) succTrans / totalTrans) * 100;
        double txPerSec = actualDurationSeconds <= 0 ? 0 : totalTrans / actualDurationSeconds;
        double opsPerSec = actualDurationSeconds <= 0 ? 0 : ops / actualDurationSeconds;
        double readPct = (ops == 0) ? 0.0 : ((double) reads / ops) * 100.0;
        double writePct = (ops == 0) ? 0.0 : ((double) writes / ops) * 100.0;
        double meanLatencyMs = (totalTrans == 0) ? 0.0 : ((double) sumLatencyMs.get() / totalTrans);

        try {
            Thread.sleep(1000);
            // Force flush of stdout
            System.out.flush();
            System.out.println("");
            System.out.println("Finished benchmark running.");
            System.out.println("");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("=== Benchmark Results for " + numClients + " client(s) ===");
        System.out.println("Duration: " + String.format("%.2f", actualDurationSeconds) + " seconds");
        System.out.println("Total transactions: " + totalTrans);
        System.out.println("Successful transactions: " + succTrans);
        System.out.println("Failed transactions: " + failTrans);
        System.out.println("Success rate: " + String.format("%.2f", succRate) + "%");
        System.out.println("Transactions/second: " + String.format("%.2f", txPerSec));
        System.out.println("Operations/second: " + String.format("%.2f", opsPerSec));
        System.out.println("Reads: " + reads + " (" + String.format("%.2f", readPct) + "%)");
        System.out.println("Writes: " + writes + " (" + String.format("%.2f", writePct) + "%)");
        System.out.println("Mean txn latency: " + String.format("%.2f", meanLatencyMs) + " ms");

        if (logEvents) {
            System.out.println("Captured " + currEventLog.size() + " total events");
            // Write event log string to json text file.
            File eventLogFile = new File("event_log.json");
            try {
                FileWriter eventLogWriter = new FileWriter(eventLogFile);
                eventLogWriter.write("[");
                eventLogWriter.write(String.join(",\n", currEventLog));
                eventLogWriter.write("]");
                eventLogWriter.close();
            } catch (IOException e) {
                System.out.println("[ERROR] Failed to write event log: " + e.getMessage());
            }
        }

        // Package results
        BenchmarkResult result = new BenchmarkResult();
        result.clients = numClients;
        result.keysPerTransaction = keysPerTransaction;
        result.readRatio = readRatio;
        result.keyspaceSize = keyspaceSize;
        result.durationSeconds = DURATION_SECONDS;
        result.actualDurationSeconds = actualDurationSeconds;
        result.totalTransactions = totalTrans;
        result.successfulTransactions = succTrans;
        result.failedTransactions = failTrans;
        result.successRate = succRate;
        result.transactionsPerSecond = txPerSec;
        result.operationsPerSecond = opsPerSec;
        result.totalReads = reads;
        result.totalWrites = writes;
        result.readPct = readPct;
        result.writePct = writePct;
        result.meanLatencyMs = meanLatencyMs;
        return result;
    }

    // Jepsen compatible invocation log string.
    // type is 'invoke' or 'ok'.
    private static String logInvocationEventStr(String type, int clientId, int keysPerTransaction, int[] selectedKeys, int[] opType, long[] valuesRead, String[] listValuesRead, long[] valuesToWrite, long index, JepsenEventModelType jepsenEventModelType, long version) {
        StringBuilder invokeLog = new StringBuilder();
        invokeLog.append("{\"type\":\"" + type + "\",\"f\":\"txn\",\"value\":[");
        for (int i = 0; i < keysPerTransaction; i++) {
            String opTypeStr = opType[i] == 0 ? "r" : "w";
            String keyName = "k" + selectedKeys[i];
            if (opType[i] == 0) {
                // read
                if (jepsenEventModelType == JepsenEventModelType.ListAppend) {
                    invokeLog.append("[\"r\",\"").append(keyName).append("\",").append(listValuesRead == null ? "null" : "[" + listValuesRead[i] + "]").append("]");
                } else {
                    invokeLog.append("[\"r\",\"").append(keyName).append("\",").append(valuesRead == null ? "null" : valuesRead[i]).append("]");
                }
            } else {
                // write
                if (jepsenEventModelType == JepsenEventModelType.ListAppend) {
                    invokeLog.append("[\"append\",\"").append(keyName).append("\",").append(valuesToWrite[i]).append("]");
                } else {
                    invokeLog.append("[\"w\",\"").append(keyName).append("\",").append(valuesToWrite[i]).append("]");
                }
            }
            if (i < keysPerTransaction - 1) {
                invokeLog.append(",");
            }
        }
        // invokeLog.append("],\"process\":").append(clientId).append(",\"index\":").append(index).append("}");
        invokeLog.append("],");
        invokeLog.append("\"version\":").append(version).append(",");
        invokeLog.append("\"process\":").append(clientId).append("}");
        return invokeLog.toString();
    }


    // Jepsen compatible invocation log string.
    // type is 'invoke' or 'ok'.
    // {:type :invoke, :f :transfer, :process 0, :time 12613722542, :index 34, :value {:from 1, :to 0, :amount 5}}
    // {:type :fail,   :f :transfer, :process 0, :time 12686176735, :index 35, :value {:from 1, :to 0, :amount 5}}
    // {:type :invoke, :f :read,     :process 0, :time 12686563291, :index 36}
    // {:type :ok,     :f :read,     :process 0, :time 12799165489, :index 37, :value {0 97, 1 0, 2 0, 3 0, 4 0, 5 3, 6 0, 7 0, 8 0, 9 0}}
    // {:type :invoke, :f :transfer, :process 0, :time 12799587097, :index 38, :value {:from 6, :to 5, :amount 3}}
    // {:type :fail,   :f :transfer, :process 0, :time 12903632203, :index 39, :value {:from 6, :to 5, :amount 3}}
    // {:type :invoke, :f :read,     :process 0, :time 12903998176, :index 40}
    private static String logInvocationBankEventStr(String type, int clientId, String f, long from, long to, long amount) {
        StringBuilder invokeLog = new StringBuilder();

        invokeLog.append("{\"type\":\"" + type + "\",\"f\":\"" + f + "\",\"process\":").append(clientId).append(",\"value\":[");    



            invokeLog.append("{\"type\":\"" + type + "\",\"f\":\"" + f + "\",\"process\":").append(clientId).append(",\"value\":[");
        invokeLog.append("{\"from\":").append(from).append(",\"to\":").append(to).append(",\"amount\":").append(amount).append("}");
        invokeLog.append("]");
        invokeLog.append("}");
        return invokeLog.toString();
    }

    /**
     * Performs a single benchmark transaction with the given parameters
     */
    private static void performBenchmarkTransaction(
            final OptimisticTransactionDB txnDb,
            final WriteOptions writeOptions,
            final ReadOptions readOptions,
            int clientId,
            int keysPerTransaction,
            double readRatio,
            Random random,
            ZipfianGenerator zipfianRandom,
            boolean useZipf,
            AtomicLong totalOperations,
            AtomicLong totalReads,
            AtomicLong totalWrites,
            long transactionIndex) throws RocksDBException {

        // Generate random keys for this transaction
        int[] selectedKeys = new int[keysPerTransaction];
        // 0: read, 1: write.
        int[] opType = new int[keysPerTransaction];
        long[] valuesToWrite = new long[keysPerTransaction];

        // Values actually read from the database.
        long[] valuesRead = new long[keysPerTransaction];
        String[] listValuesRead = new String[keysPerTransaction];

        for (int i = 0; i < keysPerTransaction; i++) {
            if (useZipf) {
                selectedKeys[i] = zipfianRandom.nextValue().intValue();

            } else {
                selectedKeys[i] = random.nextInt(keyspaceSize);
            }
            opType[i] = random.nextDouble() < readRatio ? 0 : 1;
            if (opType[i] == 1) {
                // Try to simply make all written values unique, derived from each transaction id.
                valuesToWrite[i] = transactionIndex * 1000 + i;
            }
        }

        if (logEvents) {
            String invokeLog = logInvocationEventStr("invoke", clientId, keysPerTransaction, selectedKeys, opType, null, null, valuesToWrite, transactionIndex, jepsenEventModelType, -1);
            if (invokeLog.length() > 10) {
                currEventLog.add(invokeLog);
            }
        }

        ReadOptions txnReadOptions = new ReadOptions();

        try (final OptimisticTransactionOptions txnOptions = new OptimisticTransactionOptions().setSetSnapshot(true); final Transaction txn = txnDb.beginTransaction(writeOptions, txnOptions)) {

            // https://github.com/facebook/rocksdb/wiki/Transactions#setting-a-snapshot
            txn.setSnapshot();
            txnReadOptions.setSnapshot(txn.getSnapshot());

            if(jepsenEventModelType.equals(JepsenEventModelType.Bank)){

                // 5 percent of the time, perform a read that reads the value/balance of all keys/accounts.
                if (random.nextDouble() < 0.05) {
                    // Read the value/balance of all keys/accounts.
                    String outRes = "";
                    for (int i = 0; i < keyspaceSize; i++) {
                        String key = "k" + i;
                        byte[] keyBytes = key.getBytes(UTF_8);
                        byte[] value = txn.get(txnReadOptions, keyBytes);
                        outRes += key + ": " + new String(value, UTF_8) + ", ";
                    }
                    System.out.println("Read all keys: " + outRes);
                    if (logEvents) {
                        // String invokeLog = logInvocationBankEventStr("invoke", "read", clientId, keysPerTransaction, selectedKeys, opType, valuesRead, listValuesRead, valuesToWrite, transactionIndex, jepsenEventModelType);
                        // if (invokeLog.length() > 10) {
                        //     currEventLog.add(invokeLog);
                        // }
                    }
                } else{
                    System.out.println("Performing Bank transaction...");
                    // Choose two random accounts (keys) to transfer money between.
                    int from = random.nextInt(keyspaceSize);
                    int to = random.nextInt(keyspaceSize);

                    System.out.println("From: " + from + ", To: " + to);

                    byte[] fromKeyBytes = ("k" + from).getBytes(UTF_8);
                    byte[] toKeyBytes = ("k" + to).getBytes(UTF_8);

                    // Transfer some amount of money that is larger than current balance 
                    // in 'from' over to account 'to'.

                    // Get the current balance of the 'from' account.
                    byte[] fromValue = txn.getForUpdate(txnReadOptions, fromKeyBytes, true);
                    simulateNetworkDelay();
                    long fromBalance = Long.parseLong(new String(fromValue, UTF_8));

                    // Get the current balance of the 'to' account.
                    byte[] toValue = txn.getForUpdate(txnReadOptions, toKeyBytes, true);
                    simulateNetworkDelay();
                    long toBalance = Long.parseLong(new String(toValue, UTF_8));

                    long transferAmount = random.nextLong(1, fromBalance);

                    // Update the from and to account balances.
                    txn.put(fromKeyBytes, Long.toString(fromBalance - transferAmount).getBytes(UTF_8));
                    simulateNetworkDelay();
                    txn.put(toKeyBytes, Long.toString(toBalance + transferAmount).getBytes(UTF_8));
                    simulateNetworkDelay();
                    totalOperations.incrementAndGet();
                    if (logEvents) {
                        // String invokeLog = logInvocationBankEventStr("invoke", "transfer", clientId, keysPerTransaction, selectedKeys, opType, valuesRead, listValuesRead, valuesToWrite, transactionIndex, jepsenEventModelType);
                        // if (invokeLog.length() > 10) {
                        //     currEventLog.add(invokeLog);
                        // }
                    }
                }
                txn.commit();
                simulateNetworkDelay();

                return;

            }

            // Perform operations on selected keys
            int keyIndex = 0;
            for (int keyId : selectedKeys) {
                // System.out.println("keyId: " + keyId + ", thread: " + clientId);
                String key = "k" + keyId;
                byte[] keyBytes = key.getBytes(UTF_8);

                // Determine if this should be a read or write operation
                boolean isRead = opType[keyIndex] == 0;

                // final byte[] value = txn.getForUpdate(readOptions, key1, true);
                // assert (value == null);
                if (isRead) {
                    // Perform read operation (get vs. getForUpdate?)
                    byte[] value = null;
                    if(ABORT_MODE == 1 || ABORT_MODE == 0){
                        value = txn.get(txnReadOptions, keyBytes);
                        // value = txn.getForUpdate(txnReadOptions, keyBytes, true);
                    } else {
                        value = txn.getForUpdate(txnReadOptions, keyBytes, true);
                    }
                    // System.out.println("Read value: " + new String(value, UTF_8));
                    // Lists stored as comma separated string values.
                    if (jepsenEventModelType == JepsenEventModelType.ListAppend) {
                        listValuesRead[keyIndex] = "";
                        if (value != null) {
                            // System.out.println("Read value: " + new String(value, UTF_8));
                            listValuesRead[keyIndex] = new String(value, UTF_8);
                        }
                    } 
                    else if (jepsenEventModelType == JepsenEventModelType.RWRegister) {
                        valuesRead[keyIndex] = -1;
                        if (value != null) {
                            valuesRead[keyIndex] = Long.parseLong(new String(value, UTF_8));
                        }
                    }

                    totalReads.incrementAndGet();
                } else {
                    // System.out.println("Write value");
                    // Perform write operation
                    if (jepsenEventModelType == JepsenEventModelType.ListAppend) {

                        // Lists stored as comma separated string values.
                        //    byte[] oldListValue = txn.get(txnReadOptions, keyBytes);
                        byte[] oldListValue = txn.getForUpdate(txnReadOptions, keyBytes, true);
                        String oldListValueStr = oldListValue == null ? "" : new String(oldListValue, UTF_8);
                        String newListValueStr = "";
                        if (oldListValueStr.length() == 0) {
                            newListValueStr = Long.toString(valuesToWrite[keyIndex]);
                        } else {
                            newListValueStr = oldListValueStr + "," + valuesToWrite[keyIndex];
                        }
                        byte[] newListValueBytes = newListValueStr.getBytes(UTF_8);
                        txn.put(keyBytes, newListValueBytes);
                        //    System.out.println("List updated: [" + oldListValueStr + "] -> [" + newListValueStr + "]");

                    } else {
                        // Convert the int to a string, then to bytes (to write the integer as bytes in the put)
                        byte[] valueBytes = Long.toString(valuesToWrite[keyIndex]).getBytes(UTF_8);
                        // INSERT_YOUR_CODE
                        // Generate a random byte array with 8 kilobytes (8192 bytes) of data
                        byte[] random8kBytes = new byte[1 * 512];
                        new Random().nextBytes(random8kBytes);
                        txn.put(keyBytes, random8kBytes);
                        // txn.put(keyBytes, valueBytes);
                    }

                    totalWrites.incrementAndGet();
                }

                totalOperations.incrementAndGet();
                keyIndex += 1;

                // Each operation is a round trip to the server.
                simulateNetworkDelay();
            }

            // Simulate a bit of extra transaction work with simulated counting computation.
            try {
                Thread.sleep(3);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }



            // Commit the transaction (one more round trip).
            txn.commit();
            simulateNetworkDelay();
            // System.out.println("commit: "  + ", thread: " + clientId);


            // TODO: We should in theory be able to use this as the version number to pass into Jepsen for rw-register checker.
            // long txnSeqNo = txnDb.getLatestSequenceNumber();
            // txn.getSnapshot().getSequenceNumber()
            // System.out.println("txnseqno: " + txn.getId());
            long version = -1;

            if (logEvents) {
                String invokeLog = logInvocationEventStr("ok", clientId, keysPerTransaction, selectedKeys, opType, valuesRead, listValuesRead, valuesToWrite, transactionIndex, jepsenEventModelType, version);
                if (invokeLog.length() > 10) {
                    currEventLog.add(invokeLog);
                }
            }
        } catch (RocksDBException e) {
            // System.out.println("[ERROR] Transaction failed: " + e.getStatus().getCode() + "_" + transactionIndex);
            if(logEvents){  
                String invokeLog = logInvocationEventStr("fail", clientId, keysPerTransaction, selectedKeys, opType, valuesRead, listValuesRead, valuesToWrite, transactionIndex, jepsenEventModelType, -1);
                if (invokeLog.length() > 10) {
                    currEventLog.add(invokeLog);
                }
            }
            throw e;
        }
    }
}
