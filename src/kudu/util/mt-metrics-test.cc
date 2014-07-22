// Copyright (c) 2013, Cloudera, inc.

#include <tr1/memory>
#include <vector>

#include <boost/bind.hpp>
#include <boost/foreach.hpp>
#include <boost/function.hpp>
#include <gflags/gflags.h>
#include <gtest/gtest.h>
#include <rapidjson/prettywriter.h>

#include "kudu/gutil/atomicops.h"
#include "kudu/gutil/ref_counted.h"
#include "kudu/gutil/strings/substitute.h"
#include "kudu/util/jsonwriter.h"
#include "kudu/util/metrics.h"
#include "kudu/util/monotime.h"
#include "kudu/util/test_util.h"
#include "kudu/util/thread.h"

DEFINE_int32(mt_metrics_test_num_threads, 4,
             "Number of threads to spawn in mt metrics tests");

namespace kudu {

using std::tr1::shared_ptr;
using std::vector;

class MultiThreadedMetricsTest : public KuduTest {
 public:
  static void RegisterCounters(MetricRegistry* metrics,
                               const string& name_prefix, int num_counters);
};

// Call increment on a Counter a bunch of times.
static void CountWithCounter(Counter* counter, int num_increments) {
  for (int i = 0; i < num_increments; i++) {
    counter->Increment();
  }
}

// Helper function that spawns and then joins a bunch of threads.
static void RunWithManyThreads(boost::function<void()>* f, int num_threads) {
  vector<scoped_refptr<kudu::Thread> > threads;
  for (int i = 0; i < num_threads; i++) {
    scoped_refptr<kudu::Thread> new_thread;
    CHECK_OK(kudu::Thread::Create("test", StringPrintf("thread%d", i),
          *f, &new_thread));
    threads.push_back(new_thread);
  }
  for (int i = 0; i < num_threads; i++) {
    ASSERT_STATUS_OK(ThreadJoiner(threads[i].get()).Join());
  }
}

METRIC_DEFINE_counter(test_counter, MetricUnit::kRequests, "Test counter");

// Ensure that incrementing a counter is thread-safe.
TEST_F(MultiThreadedMetricsTest, CounterIncrementTest) {
  Counter counter(METRIC_test_counter);
  int num_threads = FLAGS_mt_metrics_test_num_threads;
  int num_increments = 1000;
  boost::function<void()> f =
      boost::bind(CountWithCounter, &counter, num_increments);
  RunWithManyThreads(&f, num_threads);
  ASSERT_EQ(num_threads * num_increments, counter.value());
}

// Helper function to register a bunch of counters in a loop.
void MultiThreadedMetricsTest::RegisterCounters(MetricRegistry* metrics, const string& name_prefix,
    int num_counters) {
  uint64_t tid = Env::Default()->gettid();
  for (int i = 0; i < num_counters; i++) {
    string name = strings::Substitute("$0-$1-$2", name_prefix, tid, i);
    metrics->FindOrCreateCounter(name, METRIC_test_counter)->Increment();
  }
}

// Ensure that adding a counter to a registry is thread-safe.
TEST_F(MultiThreadedMetricsTest, AddCounterToRegistryTest) {
  MetricRegistry metrics;
  int num_threads = FLAGS_mt_metrics_test_num_threads;
  int num_counters = 1000;
  boost::function<void()> f =
      boost::bind(RegisterCounters, &metrics, "prefix", num_counters);
  RunWithManyThreads(&f, num_threads);
  ASSERT_EQ(num_threads * num_counters, metrics.UnsafeMetricsMapForTests().size());
}

} // namespace kudu