// Copyright (c) 2011-present, Facebook, Inc.  All rights reserved.
//  This source code is licensed under both the GPLv2 (found in the
//  COPYING file in the root directory) and Apache 2.0 License
//  (found in the LICENSE.Apache file in the root directory).
package my.test;

import org.rocksdb.*;
import org.rocksdb.InfoLogLevel;
import org.rocksdb.Logger;

import static java.nio.charset.StandardCharsets.UTF_8;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.Random;
import java.io.*;
import my.test.ZipfGenerator;
import my.test.ZipfianGenerator;



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

/**
 * Demonstrates using Transactions on an OptimisticTransactionDB with
 * varying isolation guarantees
 */
public class SimpleTxnTests {
  private static final String dbPath = "db_data_rocksdb";
  
  // Benchmark configuration
  private static final int DEFAULT_NUM_CLIENTS = 10;
  private static final int DEFAULT_KEYS_PER_TRANSACTION = 40;
//   private static final int DEFAULT_KEYS_PER_TRANSACTION = 5;
  private static final double DEFAULT_READ_RATIO = 0.5;
  private static final int DEFAULT_KEYSPACE_SIZE = 1000000;
  private static final int DEFAULT_DURATION_SECONDS = 30;
  private static final String BENCHMARK_CSV_FILE = "benchmark_results.csv";

  public static final void main(final String args[]) throws RocksDBException {
    // Set up for writing CSV results
    boolean csvExists = new File(BENCHMARK_CSV_FILE).exists();
    if (csvExists) {
      new File(BENCHMARK_CSV_FILE).delete();
      csvExists = false;
    }

    // INSERT_YOUR_CODE
    // Delete the dbPath directory and its contents if it exists
    File dbDir = new File(dbPath);
    if (dbDir.exists()) {
      // Recursively delete files and subdirectories
      File[] files = dbDir.listFiles();
      if (files != null) {
        for (File file : files) {
          file.delete();
        }
      }
      dbDir.delete();
    }

    // final Logger slf4jLogger = new RocksDbSlf4jLogger(InfoLogLevel.INFO_LEVEL);

    try(final Options options = new Options()
        .setCreateIfMissing(true);
        final OptimisticTransactionDB txnDb =
            OptimisticTransactionDB.open(options, dbPath);

        
        final FileWriter csvWriter = new FileWriter(BENCHMARK_CSV_FILE, true)) {
      // Write header only if file is new
      if (!csvExists) {
        csvWriter.write("clients,keys_per_txn,read_ratio,zipf,zipf_skew,keyspace,duration_sec,actual_duration_sec,"
            + "total_transactions,successful_transactions,failed_transactions,success_rate,"
            + "transactions_per_sec,operations_per_sec,reads,writes,read_pct,write_pct,mean_latency_ms\n");
        csvWriter.flush();
      }

      try (final WriteOptions writeOptions = new WriteOptions();
           final ReadOptions readOptions = new ReadOptions()) {

        //
        // "Repeatable Read" (Snapshot Isolation) Example
        //   -- Using a single Snapshot
        //
        repeatableRead(txnDb, writeOptions, readOptions);



        //
        // Simple OptimisticTransaction Example ("Read Committed")
        //
        // readCommitted(txnDb, writeOptions, readOptions);

        //
        // "Read Committed" (Monotonic Atomic Views) Example
        //   --Using multiple Snapshots
        //
        // readCommitted_monotonicAtomicViews(txnDb, writeOptions, readOptions);

      }
    } catch (IOException ioe) {
      System.err.println("Error writing benchmark results CSV: " + ioe);
    }
  }

  private static void writeBenchmarkResultToCSV(FileWriter csvWriter, BenchmarkResult result, boolean useZipf, double zipfSkew, double readRatio) throws IOException {
    csvWriter.write(
      result.clients + "," +
      result.keysPerTransaction + "," +
      String.format("%.2f", readRatio) + "," +   // read ratio used by the run (from result)
      (useZipf ? "1" : "0") + "," +                      // Zipf parameter: 1 = true, 0 = false
      String.format("%.2f", zipfSkew) + "," +            // zipf skew value
      result.keyspaceSize + "," +
      result.durationSeconds + "," +
      String.format("%.2f", result.actualDurationSeconds) + "," +
      result.totalTransactions + "," +
      result.successfulTransactions + "," +
      result.failedTransactions + "," +
      String.format("%.2f", result.successRate) + "," +
      String.format("%.2f", result.transactionsPerSecond) + "," +
      String.format("%.2f", result.operationsPerSecond) + "," +
      result.totalReads + "," +
      result.totalWrites + "," +
      String.format("%.2f", result.readPct) + "," +
      String.format("%.2f", result.writePct) + "," +
      String.format("%.2f", result.meanLatencyMs) +
      "\n"
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
    try(final Transaction txn = txnDb.beginTransaction(writeOptions)) {
      // Read a key in this transaction
      byte[] value = txn.get(readOptions, key1);
      assert(value == null);

      // Write a key in this transaction
      System.out.println("Writing key1: " + new String(key1, UTF_8) + " with value: " + new String(value1, UTF_8));
      txn.put(key1, value1);

      // Read a key OUTSIDE this transaction. Does not affect txn.
    //   value = txnDb.get(readOptions, key1);
    //   System.out.println("Reading key1: " + new String(key1, UTF_8) + " with value: " + new String(value, UTF_8));
    //   assert(value == null);

      // Write a key OUTSIDE of this transaction.
      // Does not affect txn since this is an unrelated key.
      // If we wrote key 'abc' here, the transaction would fail to commit.
    //   txnDb.put(writeOptions, key2, value2);

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
    final byte keyx[] = "keyx".getBytes(UTF_8);
    final byte key2[] = "key2".getBytes(UTF_8);
    final byte key3[] = "key3".getBytes(UTF_8);


    final byte value1t1[] = "v1t1".getBytes(UTF_8);
    final byte value1t2[] = "v1t2".getBytes(UTF_8);
    final byte value2[] = "v2".getBytes(UTF_8);
    final byte value3[] = "v3".getBytes(UTF_8);

    readOptions.setSnapshot(null);

    byte[] areadValue = txnDb.get(readOptions, key1);
    if (areadValue != null) {
        System.out.println("Read key1: " + new String(areadValue, UTF_8));
    } else {
        System.out.println("Read key1: null");
    }

    byte[] areadValue2 = txnDb.get(readOptions, key2);
    if (areadValue2 != null) {
        System.out.println("Read key2: " + new String(areadValue2, UTF_8));
    } else {
        System.out.println("Read key2: null");
    }

    byte[] areadValue3 = txnDb.get(readOptions, key3);
    if (areadValue2 != null) {
        System.out.println("Read key3: " + new String(areadValue3, UTF_8));
    } else {
        System.out.println("Read key3: null");
    }

    System.out.println("--------------------------------");

    // Set a snapshot at start of transaction by setting setSnapshot(true)
    try(final OptimisticTransactionOptions txnOptions =
            new OptimisticTransactionOptions().setSetSnapshot(true);
        final Transaction txn =
            txnDb.beginTransaction(writeOptions, txnOptions);
        final Transaction txn2 =
            txnDb.beginTransaction(writeOptions, txnOptions)) {

    //   final Snapshot snapshot = txn.getSnapshot();
    //   readOptions.setSnapshot(snapshot);

    txn.setSnapshot();
    txn2.setSnapshot();
    readOptions.setSnapshot(txn.getSnapshot());
    // readOptions.setSnapshot(txn2.getSnapshot());

    final ReadOptions treadOptions = new ReadOptions();
    treadOptions.setSnapshot(txn.getSnapshot());

    // Txn1.
    System.out.println("Txn1 write(key1), value: " + new String(value1t1, UTF_8));
    byte[] value = txn.getForUpdate(treadOptions, key1, true);
    System.out.println("Txn1 getForUpdate(key1): " + (value != null ? new String(value, UTF_8) : "null"));
    txn.put(key1, value1t1);
    System.out.println("Txn1 write(key2), value: " + new String(value2, UTF_8));
    txn.put(key2, value2);
    byte[] t1_value = txn.getForUpdate(treadOptions, key1, true);
    System.out.println("Txn1 read-for-update(key1): " + (t1_value != null ? new String(t1_value, UTF_8) : "null"));


    // // Txn2.
    // System.out.println("Txn2 write(key1), value: " + new String(value1t2, UTF_8));
    // // txn2.getForUpdate(readOptions, key1, true);
    txn2.put(keyx, value1t2);
    
    // System.out.println("Txn2 write(key3), value: " + new String(value3, UTF_8));
    // txn2.put(key3, value3);


    System.out.println("Txn2 committing");
    txn2.commit();
    System.out.println("Txn2 committed");


    try{
        System.out.println("Txn1 committing");
        txn.commit();
        System.out.println("Txn1 committed");
    }
    catch (RocksDBException e) {
        System.out.println("[ERROR]Transaction 1 failed: " + e.getStatus().getCode());
    }
    } finally {
    //   // Clear snapshot from read options since it is no longer valid
    //   readOptions.setSnapshot(null);

    //     byte[] readValue = txnDb.get(readOptions, key1);
    //     if (readValue != null) {
    //         System.out.println("Read key1: " + new String(readValue, UTF_8));
    //     } else {
    //         System.out.println("Read key1: null");
    //     }

    //     byte[] readValue2 = txnDb.get(readOptions, key2);
    //     if (readValue2 != null) {
    //         System.out.println("Read key2: " + new String(readValue2, UTF_8));
    //     } else {
    //         System.out.println("Read key2: null");
    //     }

    //     byte[] readValue3 = txnDb.get(readOptions, key3);
    //     if (readValue3 != null) {
    //         System.out.println("Read key3: " + new String(readValue3, UTF_8));
    //     } else {
    //         System.out.println("Read key3: null");
    //     }
    }

  // Start and commit a third transaction with 1 write

  // Start Transaction 3
  final Transaction txn3 = txnDb.beginTransaction(writeOptions);
  try {
      byte[] key4 = "key4".getBytes(UTF_8);
      byte[] value4 = "value4".getBytes(UTF_8);
      txn3.put(key4, value4);
      System.out.println("Txn3 committing");
      txn3.commit();
      System.out.println("Txn3 committed");
  } catch (RocksDBException e) {
      System.out.println("[ERROR]Transaction 3 failed: " + e.getStatus().getCode());
  } finally {
      txn3.close();
  }



  }

  /**
   * Demonstrates "Read Committed" (Monotonic Atomic Views) isolation
   *
   * In this example, we set the snapshot multiple times.  This is probably
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

    try (final OptimisticTransactionOptions txnOptions =
             new OptimisticTransactionOptions().setSetSnapshot(true);
         final Transaction txn =
             txnDb.beginTransaction(writeOptions, txnOptions)) {

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

}