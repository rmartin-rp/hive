/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hadoop.hive.metastore.columnstats.aggr;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.hive.common.ndv.NumDistinctValueEstimator;
import org.apache.hadoop.hive.common.ndv.NumDistinctValueEstimatorFactory;
import org.apache.hadoop.hive.metastore.api.ColumnStatistics;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsData;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.StringColumnStatsData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StringColumnStatsAggregator extends ColumnStatsAggregator implements
    IExtrapolatePartStatus {

  private static final Logger LOG = LoggerFactory.getLogger(LongColumnStatsAggregator.class);

  @Override
  public ColumnStatisticsObj aggregate(String colName, List<String> partNames,
      List<ColumnStatistics> css) throws MetaException {
    ColumnStatisticsObj statsObj = null;

    // check if all the ColumnStatisticsObjs contain stats and all the ndv are
    // bitvectors. Only when both of the conditions are true, we merge bit
    // vectors. Otherwise, just use the maximum function.
    boolean doAllPartitionContainStats = partNames.size() == css.size();
    LOG.debug("doAllPartitionContainStats for " + colName + " is " + doAllPartitionContainStats);
    NumDistinctValueEstimator ndvEstimator = null;
    String colType = null;
    for (ColumnStatistics cs : css) {
      if (cs.getStatsObjSize() != 1) {
        throw new MetaException(
            "The number of columns should be exactly one in aggrStats, but found "
                + cs.getStatsObjSize());
      }
      ColumnStatisticsObj cso = cs.getStatsObjIterator().next();
      if (statsObj == null) {
        colType = cso.getColType();
        statsObj = ColumnStatsAggregatorFactory.newColumnStaticsObj(colName, colType, cso
            .getStatsData().getSetField());
      }
      if (!cso.getStatsData().getStringStats().isSetBitVectors()
          || cso.getStatsData().getStringStats().getBitVectors().length() == 0) {
        ndvEstimator = null;
        break;
      } else {
        // check if all of the bit vectors can merge
        NumDistinctValueEstimator estimator = NumDistinctValueEstimatorFactory
            .getNumDistinctValueEstimator(cso.getStatsData().getStringStats().getBitVectors());
        if (ndvEstimator == null) {
          ndvEstimator = estimator;
        } else {
          if (ndvEstimator.canMerge(estimator)) {
            continue;
          } else {
            ndvEstimator = null;
            break;
          }
        }
      }
    }
    if (ndvEstimator != null) {
      ndvEstimator = NumDistinctValueEstimatorFactory
          .getEmptyNumDistinctValueEstimator(ndvEstimator);
    }
    LOG.debug("all of the bit vectors can merge for " + colName + " is " + (ndvEstimator != null));
    ColumnStatisticsData columnStatisticsData = new ColumnStatisticsData();
    if (doAllPartitionContainStats || css.size() < 2) {
      StringColumnStatsData aggregateData = null;
      for (ColumnStatistics cs : css) {
        ColumnStatisticsObj cso = cs.getStatsObjIterator().next();
        StringColumnStatsData newData = cso.getStatsData().getStringStats();
        if (ndvEstimator != null) {
          ndvEstimator.mergeEstimators(NumDistinctValueEstimatorFactory
              .getNumDistinctValueEstimator(newData.getBitVectors()));
        }
        if (aggregateData == null) {
          aggregateData = newData.deepCopy();
        } else {
          aggregateData
              .setMaxColLen(Math.max(aggregateData.getMaxColLen(), newData.getMaxColLen()));
          aggregateData
              .setAvgColLen(Math.max(aggregateData.getAvgColLen(), newData.getAvgColLen()));
          aggregateData.setNumNulls(aggregateData.getNumNulls() + newData.getNumNulls());
          aggregateData.setNumDVs(Math.max(aggregateData.getNumDVs(), newData.getNumDVs()));
        }
      }
      if (ndvEstimator != null) {
        // if all the ColumnStatisticsObjs contain bitvectors, we do not need to
        // use uniform distribution assumption because we can merge bitvectors
        // to get a good estimation.
        aggregateData.setNumDVs(ndvEstimator.estimateNumDistinctValues());
      } else {
        // aggregateData already has the ndv of the max of all
      }
      columnStatisticsData.setStringStats(aggregateData);
    } else {
      // we need extrapolation
      LOG.debug("start extrapolation for " + colName);

      Map<String, Integer> indexMap = new HashMap<String, Integer>();
      for (int index = 0; index < partNames.size(); index++) {
        indexMap.put(partNames.get(index), index);
      }
      Map<String, Double> adjustedIndexMap = new HashMap<String, Double>();
      Map<String, ColumnStatisticsData> adjustedStatsMap = new HashMap<String, ColumnStatisticsData>();
      if (ndvEstimator == null) {
        // if not every partition uses bitvector for ndv, we just fall back to
        // the traditional extrapolation methods.
        for (ColumnStatistics cs : css) {
          String partName = cs.getStatsDesc().getPartName();
          ColumnStatisticsObj cso = cs.getStatsObjIterator().next();
          adjustedIndexMap.put(partName, (double) indexMap.get(partName));
          adjustedStatsMap.put(partName, cso.getStatsData());
        }
      } else {
        // we first merge all the adjacent bitvectors that we could merge and
        // derive new partition names and index.
        StringBuilder pseudoPartName = new StringBuilder();
        double pseudoIndexSum = 0;
        int length = 0;
        int curIndex = -1;
        StringColumnStatsData aggregateData = null;
        for (ColumnStatistics cs : css) {
          String partName = cs.getStatsDesc().getPartName();
          ColumnStatisticsObj cso = cs.getStatsObjIterator().next();
          StringColumnStatsData newData = cso.getStatsData().getStringStats();
          // newData.isSetBitVectors() should be true for sure because we
          // already checked it before.
          if (indexMap.get(partName) != curIndex) {
            // There is bitvector, but it is not adjacent to the previous ones.
            if (length > 0) {
              // we have to set ndv
              adjustedIndexMap.put(pseudoPartName.toString(), pseudoIndexSum / length);
              aggregateData.setNumDVs(ndvEstimator.estimateNumDistinctValues());
              ColumnStatisticsData csd = new ColumnStatisticsData();
              csd.setStringStats(aggregateData);
              adjustedStatsMap.put(pseudoPartName.toString(), csd);
              // reset everything
              pseudoPartName = new StringBuilder();
              pseudoIndexSum = 0;
              length = 0;
              ndvEstimator = NumDistinctValueEstimatorFactory
                  .getEmptyNumDistinctValueEstimator(ndvEstimator);
            }
            aggregateData = null;
          }
          curIndex = indexMap.get(partName);
          pseudoPartName.append(partName);
          pseudoIndexSum += curIndex;
          length++;
          curIndex++;
          if (aggregateData == null) {
            aggregateData = newData.deepCopy();
          } else {
            aggregateData.setAvgColLen(Math.min(aggregateData.getAvgColLen(),
                newData.getAvgColLen()));
            aggregateData.setMaxColLen(Math.max(aggregateData.getMaxColLen(),
                newData.getMaxColLen()));
            aggregateData.setNumNulls(aggregateData.getNumNulls() + newData.getNumNulls());
          }
          ndvEstimator.mergeEstimators(NumDistinctValueEstimatorFactory
              .getNumDistinctValueEstimator(newData.getBitVectors()));
        }
        if (length > 0) {
          // we have to set ndv
          adjustedIndexMap.put(pseudoPartName.toString(), pseudoIndexSum / length);
          aggregateData.setNumDVs(ndvEstimator.estimateNumDistinctValues());
          ColumnStatisticsData csd = new ColumnStatisticsData();
          csd.setStringStats(aggregateData);
          adjustedStatsMap.put(pseudoPartName.toString(), csd);
        }
      }
      extrapolate(columnStatisticsData, partNames.size(), css.size(), adjustedIndexMap,
          adjustedStatsMap, -1);
    }
    LOG.debug("Ndv estimatation for {} is {} # of partitions requested: {} # of partitions found: {}", colName,
        columnStatisticsData.getStringStats().getNumDVs(),partNames.size(), css.size());
    statsObj.setStatsData(columnStatisticsData);
    return statsObj;
  }

  @Override
  public void extrapolate(ColumnStatisticsData extrapolateData, int numParts,
      int numPartsWithStats, Map<String, Double> adjustedIndexMap,
      Map<String, ColumnStatisticsData> adjustedStatsMap, double densityAvg) {
    int rightBorderInd = numParts;
    StringColumnStatsData extrapolateStringData = new StringColumnStatsData();
    Map<String, StringColumnStatsData> extractedAdjustedStatsMap = new HashMap<>();
    for (Map.Entry<String, ColumnStatisticsData> entry : adjustedStatsMap.entrySet()) {
      extractedAdjustedStatsMap.put(entry.getKey(), entry.getValue().getStringStats());
    }
    List<Map.Entry<String, StringColumnStatsData>> list = new LinkedList<Map.Entry<String, StringColumnStatsData>>(
        extractedAdjustedStatsMap.entrySet());
    // get the avgLen
    Collections.sort(list, new Comparator<Map.Entry<String, StringColumnStatsData>>() {
      @Override
      public int compare(Map.Entry<String, StringColumnStatsData> o1,
          Map.Entry<String, StringColumnStatsData> o2) {
        return Double.compare(o1.getValue().getAvgColLen(), o2.getValue().getAvgColLen());
      }
    });
    double minInd = adjustedIndexMap.get(list.get(0).getKey());
    double maxInd = adjustedIndexMap.get(list.get(list.size() - 1).getKey());
    double avgColLen = 0;
    double min = list.get(0).getValue().getAvgColLen();
    double max = list.get(list.size() - 1).getValue().getAvgColLen();
    if (minInd == maxInd) {
      avgColLen = min;
    } else if (minInd < maxInd) {
      // right border is the max
      avgColLen = (min + (max - min) * (rightBorderInd - minInd) / (maxInd - minInd));
    } else {
      // left border is the max
      avgColLen = (min + (max - min) * minInd / (minInd - maxInd));
    }

    // get the maxLen
    Collections.sort(list, new Comparator<Map.Entry<String, StringColumnStatsData>>() {
      @Override
      public int compare(Map.Entry<String, StringColumnStatsData> o1,
          Map.Entry<String, StringColumnStatsData> o2) {
        return Long.compare(o1.getValue().getMaxColLen(), o2.getValue().getMaxColLen());
      }
    });
    minInd = adjustedIndexMap.get(list.get(0).getKey());
    maxInd = adjustedIndexMap.get(list.get(list.size() - 1).getKey());
    double maxColLen = 0;
    min = list.get(0).getValue().getAvgColLen();
    max = list.get(list.size() - 1).getValue().getAvgColLen();
    if (minInd == maxInd) {
      maxColLen = min;
    } else if (minInd < maxInd) {
      // right border is the max
      maxColLen = (min + (max - min) * (rightBorderInd - minInd) / (maxInd - minInd));
    } else {
      // left border is the max
      maxColLen = (min + (max - min) * minInd / (minInd - maxInd));
    }

    // get the #nulls
    long numNulls = 0;
    for (Map.Entry<String, StringColumnStatsData> entry : extractedAdjustedStatsMap.entrySet()) {
      numNulls += entry.getValue().getNumNulls();
    }
    // we scale up sumNulls based on the number of partitions
    numNulls = numNulls * numParts / numPartsWithStats;

    // get the ndv
    long ndv = 0;
    Collections.sort(list, new Comparator<Map.Entry<String, StringColumnStatsData>>() {
      @Override
      public int compare(Map.Entry<String, StringColumnStatsData> o1,
          Map.Entry<String, StringColumnStatsData> o2) {
       return Long.compare(o1.getValue().getNumDVs(), o2.getValue().getNumDVs());
      }
    });
    minInd = adjustedIndexMap.get(list.get(0).getKey());
    maxInd = adjustedIndexMap.get(list.get(list.size() - 1).getKey());
    min = list.get(0).getValue().getNumDVs();
    max = list.get(list.size() - 1).getValue().getNumDVs();
    if (minInd == maxInd) {
      ndv = (long) min;
    } else if (minInd < maxInd) {
      // right border is the max
      ndv = (long) (min + (max - min) * (rightBorderInd - minInd) / (maxInd - minInd));
    } else {
      // left border is the max
      ndv = (long) (min + (max - min) * minInd / (minInd - maxInd));
    }
    extrapolateStringData.setAvgColLen(avgColLen);
    ;
    extrapolateStringData.setMaxColLen((long) maxColLen);
    extrapolateStringData.setNumNulls(numNulls);
    extrapolateStringData.setNumDVs(ndv);
    extrapolateData.setStringStats(extrapolateStringData);
  }

}
