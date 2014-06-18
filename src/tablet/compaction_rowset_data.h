// Copyright (c) 2013, Cloudera, inc.
#ifndef KUDU_TABLET_COMPACTION_ROWSET_DATA_H
#define KUDU_TABLET_COMPACTION_ROWSET_DATA_H

#include <string>
#include <vector>
#include <gtest/gtest.h>

#include "tablet/rowset.h"

namespace kudu {
namespace tablet {

class RowSetTree;

namespace compaction_policy {

// Class to calculate the CDF of data stored in the tablet.
class DataSizeCDF {
 public:
  // Error value guaranteed by CDF estimation
  static const double kEpsilon;

  // Create the CDF. The given RowSetTree must remain valid
  // for the lifetime of this object.
  explicit DataSizeCDF(const RowSetTree* tree);

  // Evaluate what percentage of data in the tablet falls before the given
  // key. If there is no data on-disk in the tablet, returns 1.0 for any key.
  double Evaluate(const Slice& key) const;

 private:
  FRIEND_TEST(TestDataSizeCDF, TestStringFractionInRange);

  static double ComputeTotalSize(const RowSetVector& rowsets);

  // Return the fraction indicating where "point" falls lexicographically between the
  // key range of [min, max].
  // For example, between "0000" and "9999", "5000" is right in the middle of the range,
  // hence this would return 0.5f. On the other hand, "1000" is 10% of the way through,
  // so would return 0.1f.
  //
  // If "point" falls outside of the provided range, returns either 0 or 1 depending
  // on whether it falls before or after the range, respectively.
  //
  // If the range is degenerate (ie min == max), then returns 1 for the case where
  // point == min == max.
  static double StringFractionInRange(const Slice &min, const Slice &max, const Slice &point);

  const RowSetTree *tree_;
  double total_size_;

  DISALLOW_COPY_AND_ASSIGN(DataSizeCDF);
};

// Struct used to cache some computed statistics on a RowSet used
// during compaction.
class CompactionCandidate {
 public:
  static void CollectCandidates(const RowSetTree& tree,
                                std::vector<CompactionCandidate>* candidates);

  int size_mb() const { return size_mb_; }
  void set_size_mb(const int size_mb) { size_mb_ = size_mb; }

  // Return the value of the CDF at the minimum key of this candidate.
  double cdf_min_key() const { return cdf_min_key_; }
  // Return the value of the CDF at the maximum key of this candidate.
  double cdf_max_key() const { return cdf_max_key_; }

  // Return the "width" of the candidate rowset.
  //
  // This is an estimate of the percentage of the tablet data which
  // is spanned by this RowSet, calculated by integrating the
  // probability distribution function across this rowset's keyrange.
  double width() const {
    return cdf_max_key_ - cdf_min_key_;
  }

  double density() const {
    return width() / size_mb_;
  }

  RowSet* rowset() const { return rowset_; }

  string ToString() const;

  // Return true if this candidate overlaps the other candidate in key space.
  bool Intersects(const CompactionCandidate& other) const;

 private:
  explicit CompactionCandidate(const DataSizeCDF& cdf,
                               const std::tr1::shared_ptr<RowSet>& rs);

  RowSet* rowset_;
  int size_mb_;
  double cdf_min_key_, cdf_max_key_;
};

} // namespace compaction_policy
} // namespace tablet
} // namespace kudu
#endif
