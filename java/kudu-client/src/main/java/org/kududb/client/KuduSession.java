// Copyright (c) 2013, Cloudera, inc.
// Confidential Cloudera Information: Covered by NDA.
package org.kududb.client;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Range;
import com.google.common.collect.Ranges;
import com.google.common.collect.Sets;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;
import kudu.master.Master;
import org.kududb.util.Slice;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.TimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.GuardedBy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.kududb.client.ExternalConsistencyMode.NO_CONSISTENCY;

/**
 * A KuduSession belongs to a specific KuduClient, and represents a context in
 * which all read/write data access should take place. Within a session,
 * multiple operations may be accumulated and batched together for better
 * efficiency. Settings like timeouts, priorities, and trace IDs are also set
 * per session.
 *
 * KuduSession is separate from KuduTable because a given batch or transaction
 * may span multiple tables. This is particularly important in the future when
 * we add ACID support, but even in the context of batching, we may be able to
 * coalesce writes to different tables hosted on the same server into the same
 * RPC.
 *
 * KuduSession is separate from KuduClient because, in a multi-threaded
 * application, different threads may need to concurrently execute
 * transactions. Similar to a JDBC "session", transaction boundaries will be
 * delineated on a per-session basis -- in between a "BeginTransaction" and
 * "Commit" call on a given session, all operations will be part of the same
 * transaction. Meanwhile another concurrent Session object can safely run
 * non-transactional work or other transactions without interfering.
 *
 * Additionally, there is a guarantee that writes from different sessions do not
 * get batched together into the same RPCs -- this means that latency-sensitive
 * clients can run through the same KuduClient object as throughput-oriented
 * clients, perhaps by setting the latency-sensitive session's timeouts low and
 * priorities high. Without the separation of batches, a latency-sensitive
 * single-row insert might get batched along with 10MB worth of inserts from the
 * batch writer, thus delaying the response significantly.
 *
 * Though we currently do not have transactional support, users will be forced
 * to use a KuduSession to instantiate reads as well as writes.  This will make
 * it more straight-forward to add RW transactions in the future without
 * significant modifications to the API.
 *
 * Timeouts are handled differently depending on the flush mode.
 * With AUTO_FLUSH_SYNC, the timeout is set on each apply()'d operation.
 * With AUTO_FLUSH_BACKGROUND and MANUAL_FLUSH, the timeout is assigned to a whole batch of
 * operations upon flush()'ing. It means that in a situation with a timeout of 500ms and a flush
 * interval of 1000ms, an operation can be oustanding for up to 1500ms before being timed out.
 */
public class KuduSession implements SessionConfiguration {

  public static final Logger LOG = LoggerFactory.getLogger(KuduSession.class);
  private static final Range<Float> PERCENTAGE_RANGE = Ranges.closed(0.0f, 1.0f);

  private final KuduClient client;
  private final Random randomizer = new Random();
  private int interval = 1000;
  private int mutationBufferSpace = 1000;
  private int mutationBufferLowWatermark;
  private FlushMode flushMode;
  private ExternalConsistencyMode consistencyMode;
  private long currentTimeout = 0;

  /**
   * The following two maps are to be handled together when batching is enabled. The first one is
   * where the batching happens, the second one is where we keep track of what's been sent but
   * hasn't come back yet. A batch cannot be in both maps at the same time. A batch cannot be
   * added to the in flight map if there's already another batch for the same tablet. If this
   * happens, and the batch in the first map is full, then we fail fast and send it back to the
   * client.
   * The second map stores Deferreds because KuduRpc.callback clears out the Deferred it contains
   * (as a way to reset the RPC), so we want to store the Deferred that's with the RPC that's
   * sent out.
   */
  @GuardedBy("this")
  private final Map<Slice, Batch> operations = new HashMap<Slice, Batch>();

  @GuardedBy("this")
  private final Map<Slice, Deferred<OperationResponse>> operationsInFlight = new HashMap<Slice,
      Deferred<OperationResponse>>();

  /**
   * This Set is used when not in AUTO_FLUSH_SYNC mode in order to keep track of the operations
   * that are looking up their tablet, meaning that they aren't in any of the maps above. This is
   * not expected to grow a lot except when a client starts and only for a short amount of time.
   */
  @GuardedBy("this")
  private final Set<Operation> operationsInLookup = Sets.newIdentityHashSet();
  // Only populated when we're waiting to flush and there are operations in lookup
  private Deferred<Void> lookupsDone;

  /**
   * Tracks whether the session has been closed.
   */
  volatile boolean closed;

  /**
   * Package-private constructor meant to be used via KuduClient
   * @param client client that creates this session
   */
  KuduSession(KuduClient client) {
    this.client = client;
    this.flushMode = FlushMode.AUTO_FLUSH_SYNC;
    this.consistencyMode = NO_CONSISTENCY;
    setMutationBufferLowWatermark(0.5f);
  }

  @Override
  public FlushMode getFlushMode() {
    return this.flushMode;
  }

  @Override
  public void setFlushMode(FlushMode flushMode) {
    if (hasPendingOperations()) {
      throw new IllegalArgumentException("Cannot change flush mode when writes are buffered");
    }
    this.flushMode = flushMode;
  }

  @Override
  public void setExternalConsistencyMode(ExternalConsistencyMode consistencyMode) {
    if (hasPendingOperations()) {
      throw new IllegalArgumentException("Cannot change consistency mode "
          + "when writes are buffered");
    }
    this.consistencyMode = consistencyMode;
  }

  @Override
  public void setMutationBufferSpace(int size) {
    if (hasPendingOperations()) {
      throw new IllegalArgumentException("Cannot change the buffer" +
          " size when operations are buffered");
    }
    this.mutationBufferSpace = size;
  }

  @Override
  public void setMutationBufferLowWatermark(float mutationBufferLowWatermark) {
    if (hasPendingOperations()) {
      throw new IllegalArgumentException("Cannot change the buffer" +
          " low watermark when operations are buffered");
    } else if (!PERCENTAGE_RANGE.contains(mutationBufferLowWatermark)) {
      throw new IllegalArgumentException("The low watermark must be between 0 and 1 inclusively");
    }
    this.mutationBufferLowWatermark = (int)(mutationBufferLowWatermark * mutationBufferSpace);
  }

  /**
   * Lets us set a specific seed for tests
   * @param seed
   */
  @VisibleForTesting
  void setRandomSeed(long seed) {
    this.randomizer.setSeed(seed);
  }

  @Override
  public void setFlushInterval(int interval) {
    this.interval = interval;
  }

  @Override
  public void setTimeoutMillis(long timeout) {
    this.currentTimeout = timeout;
  }

  @Override
  public long getTimeoutMillis() {
    return this.currentTimeout;
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  /**
   * Flushes the buffered operations and marks this sessions as closed.
   * See the javadoc on {@link #flush()} on how to deal with exceptions coming out of this method.
   * @return a Deferred whose callback chain will be invoked when.
   * everything that was buffered at the time of the call has been flushed.
   */
  public Deferred<ArrayList<OperationResponse>> close() {
    closed = true;
    client.removeSession(this);
    return flush();
  }

  /**
   * Flushes the buffered operations.
   * If joining on the Deferred throws a DeferredGroupException (or invokes the errback), call
   * {@link RowsWithErrorException#fromDeferredGroupException(com.stumbleupon.async.DeferredGroupException)}
   * in order to get a single RowsWithErrorException.
   * @return a Deferred whose callback chain will be invoked when
   * everything that was buffered at the time of the call has been flushed.
   */
  public Deferred<ArrayList<OperationResponse>> flush() {
    LOG.trace("Flushing all tablets");
    synchronized (this) {
      if (!operationsInLookup.isEmpty()) {
        lookupsDone = new Deferred<Void>();
        return lookupsDone.addCallbackDeferring(new OperationsInLookupDoneCB());
      }
    }
    return flushAllBatches();
  }

  class OperationsInLookupDoneCB implements
      Callback<Deferred<ArrayList<OperationResponse>>, Void> {
    @Override
    public Deferred<ArrayList<OperationResponse>>
        call(Void nothing) throws Exception {
      return flushAllBatches();
    }
  }

  /**
   * This will flush all the batches but not the operations that are currently in lookup.
   */
  private Deferred<ArrayList<OperationResponse>> flushAllBatches() {
    HashMap<Slice, Batch> copyOfOps;
    final ArrayList<Deferred<OperationResponse>> d =
        new ArrayList<Deferred<OperationResponse>>(operations.size());
    synchronized (this) {
      copyOfOps = new HashMap<Slice, Batch>(operations);
    }
    for (Map.Entry<Slice, Batch> entry: copyOfOps.entrySet()) {
      d.add(flushTablet(entry.getKey(), entry.getValue()));
    }
    return Deferred.group(d);
  }

  @Override
  public boolean hasPendingOperations() {
    synchronized (this) {
      return !this.operations.isEmpty() || !this.operationsInFlight.isEmpty() ||
          !this.operationsInLookup.isEmpty();
    }
  }

  /**
   * Apply the given operation.
   * The behavior of this function depends on the current flush mode. Regardless
   * of flush mode, however, Apply may begin to perform processing in the background
   * for the call (e.g looking up the tablet, etc).
   * @param operation operation to apply
   * @return a Deferred to track this operation
   */
  public Deferred<OperationResponse> apply(final Operation operation) {
    if (operation == null) {
      throw new NullPointerException("Cannot apply a null operation");
    }

    if (KuduClient.cannotRetryRequest(operation)) {
      return KuduClient.tooManyAttemptsOrTimeout(operation, null);
    }

    // If we autoflush, just send it to the TS
    if (flushMode == FlushMode.AUTO_FLUSH_SYNC) {
      if (currentTimeout != 0) {
        operation.setTimeoutMillis(currentTimeout);
      }
      operation.setExternalConsistencyMode(this.consistencyMode);
      return client.sendRpcToTablet(operation);
    }
    String table = operation.getTable().getName();
    byte[] rowkey = operation.key();
    KuduClient.RemoteTablet tablet = client.getTablet(table, rowkey);
    // We go straight to the buffer if we know the tabletSlice
    if (tablet != null) {
      operation.setTablet(tablet);
      // Handles the difference between manual and auto flush
      return addToBuffer(tablet.getTabletId(), operation);
    }

    synchronized (this) {
      operationsInLookup.add(operation);
    }
    // TODO starts looking a lot like sendRpcToTablet
    operation.attempt++;
    if (client.isTableNotServed(table)) {
      Callback<Deferred<OperationResponse>, Master.IsCreateTableDoneResponsePB> cb =
          new TabletLookupCB<Master.IsCreateTableDoneResponsePB>(operation);
      return client.delayedIsCreateTableDone(table, operation, cb, getOpInLookupErrback(operation));
    }

    Deferred<Master.GetTableLocationsResponsePB> d = client.locateTablet(table, rowkey);
    d.addErrback(getOpInLookupErrback(operation));
    return d.addCallbackDeferring(new TabletLookupCB<Master.GetTableLocationsResponsePB>(operation));
  }

  /**
   * This errback is different from the one in KuduClient because we need to be able to remove
   * the operation from operationsInLookup if whatever master query we issue throws an Exception.
   * @param operation Operation to errback to.
   * @return An errback.
   */
  Callback<Exception, Exception> getOpInLookupErrback(final Operation operation) {
    return new Callback<Exception, Exception>() {
      @Override
      public Exception call(Exception e) throws Exception {
        // TODO maybe we can retry it?
        synchronized (this) {
          operationsInLookup.remove(operation);
        }
        operation.errback(e);
        return e;
      }
    };
  }

  final class TabletLookupCB<D> implements Callback<Deferred<OperationResponse>, D> {
    final Operation operation;
    TabletLookupCB(Operation operation) {
      this.operation = operation;
    }
    public Deferred<OperationResponse> call(final D arg) {
      return handleOperationInLookup(operation);
    }
    public String toString() {
      return "retry RPC after lookup";
    }
  }

  Callback<Deferred<OperationResponse>, OperationResponse>
  getRetryOpInLookupCB(final Operation operation) {
    final class RetryOpInFlightCB implements Callback<Deferred<OperationResponse>,
        OperationResponse> {
      public Deferred<OperationResponse> call(final OperationResponse arg) {
        return handleOperationInLookup(operation);
      }

      public String toString() {
        return "retry RPC after PleaseThrottleException";
      }
    }
    return new RetryOpInFlightCB();
  }

  private Deferred<OperationResponse> handleOperationInLookup(Operation operation) {
    try {
      return apply(operation); // Retry the RPC.
    } catch (PleaseThrottleException pte) {
      return pte.getDeferred().addBothDeferring(getRetryOpInLookupCB(operation));
    }
  }

  /**
   * For manual and background flushing, this will batch the given operation
   * with the others, if any, for the specified tablet.
   * @param tablet tablet used to for batching
   * @param operation operation to batch
   * @return Defered to track the operation
   */
  private Deferred<OperationResponse> addToBuffer(Slice tablet, Operation operation) {
    boolean scheduleFlush = false;
    boolean batchIsFull = false;
    Batch batch;

    // First check if we need to flush the current batch.
    synchronized (this) {
      batch = operations.get(tablet);
      if (batch != null && batch.ops.size() + 1 > mutationBufferSpace) {
        if (flushMode == FlushMode.MANUAL_FLUSH) {
          throw new NonRecoverableException("MANUAL_FLUSH is enabled but the buffer is too big");
        }
        if (operationsInFlight.containsKey(tablet)) {
          // There's is already another batch in flight for this tablet.
          // We cannot continue here, we have to send this back to the client.
          // This is our high watermark.
          throw new PleaseThrottleException("The RPC cannot be buffered because the current " +
              "buffer is full and the previous buffer hasn't been flushed yet", null,
              operation, operationsInFlight.get(tablet));
        }
        batchIsFull = true;
      }
    }

    // We're doing this out of the synchronized block because flushTablet can take some time
    // encoding all the data.
    if (batchIsFull) {
      flushTablet(tablet, batch);
    }

    Deferred<Void> lookupsDoneCopy = null;
    synchronized (this) {
      // We need to get the batch again since we went out of the synchronized block. We can get a
      // new one, the same one, or null.
      batch = operations.get(tablet);

      // doing + 1 to see if the current insert would push us over the limit.
      // TODO obviously wrong, not same size thing.
      if (batch != null && operationsInFlight.containsKey(tablet) &&
          batch.ops.size() + 1 > mutationBufferLowWatermark) {
        // This is our low watermark, we throw PleaseThrottleException before hitting the high
        // mark. As we get fuller past the watermark it becomes likelier to trigger it.
        int randomWatermark = batch.ops.size() + 1 + randomizer.nextInt(mutationBufferSpace -
            mutationBufferLowWatermark);
        if (randomWatermark > mutationBufferSpace) {
          throw new PleaseThrottleException("The previous buffer hasn't been flushed and the " +
              "current one is over the low watermark, please retry later", null, operation,
              operationsInFlight.get(tablet));
        }
      }
      if (batch == null) {
        // We found a tablet that needs batching, this is the only place where
        // we schedule a flush.
        batch = new Batch(operation.getTable());
        batch.setExternalConsistencyMode(this.consistencyMode);
        Batch oldBatch = operations.put(tablet, batch);
        assert (oldBatch == null);
        addBatchCallbacks(batch);
        scheduleFlush = true;
      }
      batch.ops.add(operation);
      if (!operationsInLookup.isEmpty()) {
        operationsInLookup.remove(operation);
        if (lookupsDone != null && operationsInLookup.isEmpty()) {
          lookupsDoneCopy = lookupsDone;
          lookupsDone = null;
        }
      }
      if (flushMode == FlushMode.AUTO_FLUSH_BACKGROUND && scheduleFlush) {
        // Accumulated a first insert but we're not in manual mode,
        // schedule the flush.
        LOG.trace("Scheduling a flush");
        scheduleNextPeriodicFlush(tablet, batch);
      }
    }

    // We do this outside of the synchronized block because we might end up calling flushTablet.
    if (lookupsDoneCopy != null) {
      lookupsDoneCopy.callback(null);
    }

    // Get here if we accumulated an insert, regardless of if it scheduled
    // a flush.
    return operation.getDeferred();
  }

  /**
   * Creates callbacks to handle a multi-put and adds them to the request.
   * @param request The request for which we must handle the response.
   */
  private void addBatchCallbacks(final Batch request) {
    final class BatchCallback implements
        Callback<OperationResponse, OperationResponse> {
      public OperationResponse call(final OperationResponse response) {
        LOG.trace("Got a InsertsBatch response for " + request.ops.size() + " rows");
        if (response.getWriteTimestamp() != 0) {
          KuduSession.this.client.updateLastPropagatedTimestamp(response.getWriteTimestamp());
        }
        // TODO return something specific for each operation
        for (int i = 0; i < request.ops.size(); i++) {
          request.ops.get(i).callback(response);
        }
        return response;
      }

      @Override
      public String toString() {
        return "apply batch response";
      }
    };

    final class BatchErrCallback implements Callback<Exception, Exception> {
      @Override
      public Exception call(Exception e) throws Exception {
        for (int i = 0; i < request.ops.size(); i++) {
          request.ops.get(i).errback(e);
        }
        return e;
      }

      @Override
      public String toString() {
        return "apply batch error response";
      }
    }

    request.getDeferred().addCallbacks(new BatchCallback(), new BatchErrCallback());
  }

  /**
   * Schedules the next periodic flush of buffered edits.
   */
  private void scheduleNextPeriodicFlush(Slice tablet, Batch batch) {
    client.newTimeout(new FlusherTask(tablet, batch), interval);
  }

  /**
   * Flushes the edits for the given tablet. It will also check that the Batch we're flushing is
   * the one that was requested. This is mostly done so that the FlusherTask doesn't trigger
   * lots of small flushes under a write-heavy scenario where we're able to fill a Batch multiple
   * times per interval.
   *
   * Also, if there's already a Batch in flight for the given tablet,
   * the flush will be delayed and the returned Deferred will be chained to it.
   *
   * This method should not be called within a synchronized block because we can spend a lot of
   * time encoding the batch.
   */
  private Deferred<OperationResponse> flushTablet(Slice tablet, Batch expectedBatch) {
    assert (expectedBatch != null);
    assert (!Thread.holdsLock(this));
    Batch batch;
    synchronized (this) {
      // Check this first, no need to wait after anyone if the batch we were supposed to flush
      // was already flushed.
      if (operations.get(tablet) != expectedBatch) {
        LOG.trace("Had to flush a tablet but it was already flushed: " + Bytes.getString(tablet));
        return Deferred.fromResult(null);
      }

      if (operationsInFlight.containsKey(tablet)) {
        LOG.trace("This tablet is already in flight, attaching a callback to retry later: " +
            Bytes.getString(tablet));
        return operationsInFlight.get(tablet).addCallbackDeferring(
            new FlushRetryCallback(tablet, operations.get(tablet)));
      }

      batch = operations.remove(tablet);
      if (batch == null) {
        LOG.trace("Had to flush a tablet but there was nothing to flush: " +
            Bytes.getString(tablet));
        return Deferred.fromResult(null);
      }
      Deferred<OperationResponse> batchDeferred = batch.getDeferred();
      batchDeferred.addCallbacks(getOpInFlightCallback(tablet), getOpInFlightErrback(tablet));
      Deferred<OperationResponse> oldBatch = operationsInFlight.put(tablet, batchDeferred);
      assert (oldBatch == null);
      if (currentTimeout != 0) {
        batch.deadlineTracker.reset();
        batch.setTimeoutMillis(currentTimeout);
      }
    }
    return client.sendRpcToTablet(batch);
  }


  /**
   * Simple callback so that we try to flush this tablet again if we were waiting on the previous
   * Batch to finish.
   */
  class FlushRetryCallback implements Callback<Deferred<OperationResponse>, OperationResponse> {
    private final Slice tablet;
    private final Batch expectedBatch;
    public FlushRetryCallback(Slice tablet, Batch expectedBatch) {
      this.tablet = tablet;
      this.expectedBatch = expectedBatch;
    }

    @Override
    public Deferred<OperationResponse> call(OperationResponse o) throws Exception {
      LOG.trace("Previous batch in flight is done, flushing this tablet again: " +
          Bytes.getString(tablet));
      return flushTablet(tablet, expectedBatch);
    }
  }

  /**
   * Simple callback that removes the tablet from the in flight operations map once it completed.
   */
  private Callback<OperationResponse, OperationResponse>
      getOpInFlightCallback(final Slice tablet) {
    return new Callback<OperationResponse, OperationResponse>() {
      @Override
      public OperationResponse call(OperationResponse o) throws Exception {
        tabletInFlightDone(tablet);
        return o;
      }
    };
  }

  /**
   * We need a separate callback for errors since the generics are different. We still remove the
   * tablet from the in flight operations since there's nothing we can do about it,
   * and by returning the Exception we will bubble it up to the user.
   */
  private Callback<Exception, Exception> getOpInFlightErrback(final Slice tablet) {
    return new Callback<Exception, Exception>() {
      @Override
      public Exception call(Exception e) throws Exception {
        tabletInFlightDone(tablet);
        return e;
      }
    };
  }

  private void tabletInFlightDone(Slice tablet) {
    synchronized (KuduSession.this) {
      LOG.trace("Unmarking this tablet as in flight: " + Bytes.getString(tablet));
      operationsInFlight.remove(tablet);
    }
  }

  /**
   * A FlusherTask is created for each scheduled flush per tabletSlice.
   */
  class FlusherTask implements TimerTask {
    final Slice tabletSlice;
    final Batch expectedBatch;

    FlusherTask(Slice tabletSlice, Batch expectedBatch) {
      this.tabletSlice = tabletSlice;
      this.expectedBatch = expectedBatch;
    }

    public void run(final Timeout timeout) {
      if (isClosed()) {
        return; // we ran too late, no-op
      }
      LOG.trace("Timed flushing: " + Bytes.getString(tabletSlice));
      flushTablet(this.tabletSlice, this.expectedBatch);
    }
    public String toString() {
      return "flush commits of session " + KuduSession.this +
          " for tabletSlice " + Bytes.getString(tabletSlice);
    }
  };
}