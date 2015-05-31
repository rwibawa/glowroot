/*
 * Copyright 2014-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.local.ui;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.AggregateCollector;
import org.glowroot.collector.AggregateIntervalCollector;
import org.glowroot.collector.ProfileAggregate;
import org.glowroot.collector.QueryAggregate;
import org.glowroot.collector.QueryComponent.AggregateQuery;
import org.glowroot.collector.TransactionSummary;
import org.glowroot.common.ScratchBuffer;
import org.glowroot.config.ConfigService;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.AggregateDao.MergedAggregate;
import org.glowroot.local.store.AggregateDao.TransactionSummarySortOrder;
import org.glowroot.local.store.QueryResult;
import org.glowroot.local.store.TransactionSummaryQuery;
import org.glowroot.transaction.model.ProfileNode;

import static com.google.common.base.Preconditions.checkNotNull;

class TransactionCommonService {

    private final AggregateDao aggregateDao;
    private final @Nullable AggregateCollector aggregateCollector;
    private final ConfigService configService;

    private final long fixedRollupMillis;

    TransactionCommonService(AggregateDao aggregateDao,
            @Nullable AggregateCollector aggregateCollector, ConfigService configService,
            long fixedRollupSeconds) {
        this.aggregateDao = aggregateDao;
        this.aggregateCollector = aggregateCollector;
        this.configService = configService;
        this.fixedRollupMillis = fixedRollupSeconds * 1000;
    }

    TransactionSummary readOverallSummary(String transactionType, long from, long to)
            throws SQLException {
        List<AggregateIntervalCollector> orderedIntervalCollectors =
                getOrderedIntervalCollectorsInRange(from, to);
        if (orderedIntervalCollectors.isEmpty()) {
            return aggregateDao.readOverallTransactionSummary(transactionType, from, to);
        }
        long revisedTo = getRevisedTo(to, orderedIntervalCollectors);
        TransactionSummary overallSummary =
                aggregateDao.readOverallTransactionSummary(transactionType, from, revisedTo);
        for (AggregateIntervalCollector intervalCollector : orderedIntervalCollectors) {
            TransactionSummary liveOverallSummary =
                    intervalCollector.getLiveOverallSummary(transactionType);
            if (liveOverallSummary != null) {
                overallSummary = combineTransactionSummaries(null, overallSummary,
                        liveOverallSummary);
            }
        }
        return overallSummary;
    }

    QueryResult<TransactionSummary> readTransactionSummaries(TransactionSummaryQuery query)
            throws SQLException {
        List<AggregateIntervalCollector> orderedIntervalCollectors =
                getOrderedIntervalCollectorsInRange(query.from(), query.to());
        if (orderedIntervalCollectors.isEmpty()) {
            return aggregateDao.readTransactionSummaries(query);
        }
        long revisedTo = getRevisedTo(query.to(), orderedIntervalCollectors);
        TransactionSummaryQuery revisedQuery = query.withTo(revisedTo);
        QueryResult<TransactionSummary> queryResult =
                aggregateDao.readTransactionSummaries(revisedQuery);
        if (orderedIntervalCollectors.isEmpty()) {
            return queryResult;
        }
        return mergeInLiveTransactionSummaries(revisedQuery, queryResult,
                orderedIntervalCollectors);
    }

    boolean shouldHaveTraces(String transactionType, @Nullable String transactionName, long from,
            long to) throws SQLException {
        if (transactionName == null) {
            return aggregateDao.shouldHaveOverallTraces(transactionType, from, to);
        } else {
            return aggregateDao.shouldHaveTransactionTraces(transactionType, transactionName, from,
                    to);
        }
    }

    boolean shouldHaveErrorTraces(String transactionType, @Nullable String transactionName,
            long from, long to) throws SQLException {
        if (transactionName == null) {
            return aggregateDao.shouldHaveOverallErrorTraces(transactionType, from, to);
        } else {
            return aggregateDao.shouldHaveTransactionErrorTraces(transactionType, transactionName,
                    from, to);
        }
    }

    List<Aggregate> getAggregates(String transactionType, @Nullable String transactionName,
            long from, long to) throws Exception {
        int rollupLevel = getRollupLevel(from, to);
        List<AggregateIntervalCollector> orderedIntervalCollectors =
                getOrderedIntervalCollectorsInRange(from, to);
        long revisedTo = getRevisedTo(to, orderedIntervalCollectors);
        List<Aggregate> aggregates = getAggregatesFromDao(transactionType, transactionName, from,
                revisedTo, rollupLevel);
        if (rollupLevel == 0) {
            aggregates = Lists.newArrayList(aggregates);
            aggregates.addAll(getLiveAggregates(transactionType, transactionName,
                    orderedIntervalCollectors));
            return aggregates;
        }
        long revisedFrom = revisedTo - AggregateDao.ROLLUP_THRESHOLD_MILLIS;
        if (!aggregates.isEmpty()) {
            long lastRolledUpTime = aggregates.get(aggregates.size() - 1).captureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
        }
        List<Aggregate> orderedNonRolledUpAggregates = Lists.newArrayList();
        orderedNonRolledUpAggregates.addAll(getAggregatesFromDao(transactionType, transactionName,
                revisedFrom, revisedTo, 0));
        orderedNonRolledUpAggregates.addAll(getLiveAggregates(transactionType, transactionName,
                orderedIntervalCollectors));
        aggregates = Lists.newArrayList(aggregates);
        aggregates.addAll(rollUp(transactionType, transactionName, orderedNonRolledUpAggregates));
        return aggregates;
    }

    Map<String, List<AggregateQuery>> getQueries(String transactionType,
            @Nullable String transactionName, long from, long to) throws Exception {
        List<QueryAggregate> queryAggregates =
                getQueryAggregates(transactionType, transactionName, from, to);
        return AggregateMerging.getOrderedAndTruncatedQueries(queryAggregates,
                configService.getAdvancedConfig().maxAggregateQueriesPerQueryType());
    }

    ProfileNode getProfile(String transactionType, @Nullable String transactionName, long from,
            long to, double truncateLeafPercentage) throws Exception {
        List<ProfileAggregate> profileAggregate =
                getProfileAggregates(transactionType, transactionName, from, to);
        ProfileNode syntheticRootNode = AggregateMerging.getMergedProfile(profileAggregate);
        if (truncateLeafPercentage != 0) {
            int minSamples = (int) (syntheticRootNode.getSampleCount() * truncateLeafPercentage);
            // don't truncate any root nodes
            truncateLeafs(syntheticRootNode.getChildNodes(), minSamples);
        }
        return syntheticRootNode;
    }

    private List<AggregateIntervalCollector> getOrderedIntervalCollectorsInRange(long from,
            long to) {
        if (aggregateCollector == null) {
            return ImmutableList.of();
        }
        return aggregateCollector.getOrderedIntervalCollectorsInRange(from, to);
    }

    private List<Aggregate> getAggregatesFromDao(String transactionType,
            @Nullable String transactionName, long from, long to, int rollupLevel)
            throws SQLException {
        if (transactionName == null) {
            return aggregateDao.readOverallAggregates(transactionType, from, to, rollupLevel);
        } else {
            return aggregateDao.readTransactionAggregates(transactionType, transactionName, from,
                    to, rollupLevel);
        }
    }

    // this method may return some rolled up query aggregates and some non-rolled up
    // they are all distinct though
    // this is ok since the results of this method are currently just aggregated into single
    // result as opposed to charted over time period
    private List<QueryAggregate> getQueryAggregates(String transactionType,
            @Nullable String transactionName, long from, long to) throws Exception {
        int rollupLevel = getRollupLevel(from, to);
        List<AggregateIntervalCollector> orderedIntervalCollectors =
                getOrderedIntervalCollectorsInRange(from, to);
        long revisedTo = getRevisedTo(to, orderedIntervalCollectors);
        List<QueryAggregate> queryAggregates = getQueryAggregatesFromDao(transactionType,
                transactionName, from, revisedTo, rollupLevel);
        if (rollupLevel == 0) {
            queryAggregates = Lists.newArrayList(queryAggregates);
            queryAggregates.addAll(getLiveQueryAggregates(transactionType, transactionName,
                    orderedIntervalCollectors));
            return queryAggregates;
        }
        long revisedFrom = revisedTo - AggregateDao.ROLLUP_THRESHOLD_MILLIS;
        if (!queryAggregates.isEmpty()) {
            long lastRolledUpTime = queryAggregates.get(queryAggregates.size() - 1).captureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
        }
        List<QueryAggregate> orderedNonRolledUpQueryAggregates = Lists.newArrayList();
        orderedNonRolledUpQueryAggregates.addAll(getQueryAggregatesFromDao(transactionType,
                transactionName, revisedFrom, revisedTo, 0));
        orderedNonRolledUpQueryAggregates.addAll(getLiveQueryAggregates(transactionType,
                transactionName, orderedIntervalCollectors));
        queryAggregates = Lists.newArrayList(queryAggregates);
        queryAggregates.addAll(orderedNonRolledUpQueryAggregates);
        return queryAggregates;
    }

    private List<QueryAggregate> getQueryAggregatesFromDao(String transactionType,
            @Nullable String transactionName, long from, long to, int rollupLevel)
            throws SQLException {
        if (transactionName == null) {
            return aggregateDao.readOverallQueryAggregates(transactionType, from, to, rollupLevel);
        } else {
            return aggregateDao.readTransactionQueryAggregates(transactionType, transactionName,
                    from, to, rollupLevel);
        }
    }

    // this method may return some rolled up profile aggregates and some non-rolled up
    // they are all distinct though
    // this is ok since the results of this method are currently just aggregated into single
    // result as opposed to charted over time period
    private List<ProfileAggregate> getProfileAggregates(String transactionType,
            @Nullable String transactionName, long from, long to) throws Exception {
        int rollupLevel = getRollupLevel(from, to);
        List<AggregateIntervalCollector> orderedIntervalCollectors =
                getOrderedIntervalCollectorsInRange(from, to);
        long revisedTo = getRevisedTo(to, orderedIntervalCollectors);
        List<ProfileAggregate> profileAggregates = getProfileAggregatesFromDao(transactionType,
                transactionName, from, revisedTo, rollupLevel);
        if (rollupLevel == 0) {
            profileAggregates = Lists.newArrayList(profileAggregates);
            profileAggregates.addAll(getLiveProfileAggregates(transactionType, transactionName,
                    orderedIntervalCollectors));
            return profileAggregates;
        }
        long revisedFrom = revisedTo - AggregateDao.ROLLUP_THRESHOLD_MILLIS;
        if (!profileAggregates.isEmpty()) {
            long lastRolledUpTime = profileAggregates.get(profileAggregates.size() - 1)
                    .captureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
        }
        List<ProfileAggregate> orderedNonRolledUpProfileAggregates = Lists.newArrayList();
        orderedNonRolledUpProfileAggregates.addAll(getProfileAggregatesFromDao(transactionType,
                transactionName, revisedFrom, revisedTo, 0));
        orderedNonRolledUpProfileAggregates.addAll(getLiveProfileAggregates(transactionType,
                transactionName, orderedIntervalCollectors));
        profileAggregates = Lists.newArrayList(profileAggregates);
        profileAggregates.addAll(orderedNonRolledUpProfileAggregates);
        return profileAggregates;
    }

    private List<ProfileAggregate> getProfileAggregatesFromDao(String transactionType,
            @Nullable String transactionName, long from, long to, int rollupLevel)
            throws SQLException {
        if (transactionName == null) {
            return aggregateDao.readOverallProfileAggregates(transactionType, from, to,
                    rollupLevel);
        } else {
            return aggregateDao.readTransactionProfileAggregates(transactionType, transactionName,
                    from, to, rollupLevel);
        }
    }

    private List<Aggregate> rollUp(String transactionType, @Nullable String transactionName,
            List<Aggregate> orderedNonRolledUpAggregates) throws Exception {
        List<Aggregate> rolledUpAggregates = Lists.newArrayList();
        ScratchBuffer scratchBuffer = new ScratchBuffer();
        MergedAggregate currMergedAggregate = null;
        long currRollupTime = Long.MIN_VALUE;
        for (Aggregate nonRolledUpAggregate : orderedNonRolledUpAggregates) {
            long rollupTime = (long) Math.ceil(nonRolledUpAggregate.captureTime()
                    / (double) fixedRollupMillis) * fixedRollupMillis;
            if (rollupTime != currRollupTime && currMergedAggregate != null) {
                rolledUpAggregates.add(currMergedAggregate.toAggregate(scratchBuffer));
                currMergedAggregate = new MergedAggregate(0, transactionType, transactionName,
                        configService.getAdvancedConfig().maxAggregateQueriesPerQueryType());
            }
            if (currMergedAggregate == null) {
                currMergedAggregate = new MergedAggregate(0, transactionType, transactionName,
                        configService.getAdvancedConfig().maxAggregateQueriesPerQueryType());
            }
            currRollupTime = rollupTime;
            // capture time is the largest of the ordered aggregate capture times
            currMergedAggregate.setCaptureTime(nonRolledUpAggregate.captureTime());
            currMergedAggregate.addTotalMicros(nonRolledUpAggregate.totalMicros());
            currMergedAggregate.addErrorCount(nonRolledUpAggregate.errorCount());
            currMergedAggregate.addTransactionCount(nonRolledUpAggregate.transactionCount());
            currMergedAggregate.addTotalCpuMicros(nonRolledUpAggregate.totalCpuMicros());
            currMergedAggregate.addTotalBlockedMicros(nonRolledUpAggregate.totalBlockedMicros());
            currMergedAggregate.addTotalWaitedMicros(nonRolledUpAggregate.totalWaitedMicros());
            currMergedAggregate.addTotalAllocatedKBytes(
                    nonRolledUpAggregate.totalAllocatedKBytes());
            currMergedAggregate.addTraceCount(nonRolledUpAggregate.traceCount());
            currMergedAggregate.addTimers(nonRolledUpAggregate.timers());
            currMergedAggregate.addHistogram(nonRolledUpAggregate.histogram());
        }
        if (currMergedAggregate != null) {
            // roll up final one
            rolledUpAggregates.add(currMergedAggregate.toAggregate(scratchBuffer));
        }
        return rolledUpAggregates;
    }

    private static int getRollupLevel(long from, long to) {
        if (to - from <= AggregateDao.ROLLUP_THRESHOLD_MILLIS) {
            return 0;
        } else {
            return 1;
        }
    }

    private static long getRevisedTo(long to,
            List<AggregateIntervalCollector> orderedIntervalCollectors) {
        if (orderedIntervalCollectors.isEmpty()) {
            return to;
        } else {
            // -1 since query 'to' is inclusive
            // this way don't need to worry about de-dupping between live and stored aggregates
            return orderedIntervalCollectors.get(0).getEndTime() - 1;
        }
    }

    private static QueryResult<TransactionSummary> mergeInLiveTransactionSummaries(
            TransactionSummaryQuery query, QueryResult<TransactionSummary> queryResult,
            List<AggregateIntervalCollector> intervalCollectors) {
        List<TransactionSummary> transactionSummaries = queryResult.records();
        Map<String, TransactionSummary> transactionSummaryMap = Maps.newHashMap();
        for (TransactionSummary transactionSummary : transactionSummaries) {
            String transactionName = transactionSummary.transactionName();
            // transaction name is only null for overall summary
            checkNotNull(transactionName);
            transactionSummaryMap.put(transactionName, transactionSummary);
        }
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            List<TransactionSummary> liveTransactionSummaries =
                    intervalCollector.getLiveTransactionSummaries(query.transactionType());
            for (TransactionSummary liveTransactionSummary : liveTransactionSummaries) {
                String transactionName = liveTransactionSummary.transactionName();
                // transaction name is only null for overall summary
                checkNotNull(transactionName);
                TransactionSummary transactionSummary = transactionSummaryMap.get(transactionName);
                if (transactionSummary == null) {
                    transactionSummaryMap.put(transactionName, liveTransactionSummary);
                } else {
                    transactionSummaryMap.put(transactionName,
                            combineTransactionSummaries(transactionName, transactionSummary,
                                    liveTransactionSummary));
                }
            }
        }
        transactionSummaries =
                sortTransactionSummaries(transactionSummaryMap.values(), query.sortOrder());
        boolean moreAvailable = queryResult.moreAvailable();
        if (transactionSummaries.size() > query.limit()) {
            moreAvailable = true;
            transactionSummaries = transactionSummaries.subList(0, query.limit());
        }
        return new QueryResult<TransactionSummary>(transactionSummaries, moreAvailable);
    }

    private static List<Aggregate> getLiveAggregates(String transactionType,
            @Nullable String transactionName, List<AggregateIntervalCollector> intervalCollectors)
            throws IOException {
        List<Aggregate> aggregates = Lists.newArrayList();
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            Aggregate liveAggregate =
                    intervalCollector.getLiveAggregate(transactionType, transactionName);
            if (liveAggregate != null) {
                aggregates.add(liveAggregate);
            }
        }
        return aggregates;
    }

    private static List<QueryAggregate> getLiveQueryAggregates(String transactionType,
            @Nullable String transactionName, List<AggregateIntervalCollector> intervalCollectors)
            throws IOException {
        List<QueryAggregate> queryAggregates = Lists.newArrayList();
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            QueryAggregate liveQueryAggregate =
                    intervalCollector.getLiveQueryAggregate(transactionType, transactionName);
            if (liveQueryAggregate != null) {
                queryAggregates.add(liveQueryAggregate);
            }
        }
        return queryAggregates;
    }

    private static List<ProfileAggregate> getLiveProfileAggregates(String transactionType,
            @Nullable String transactionName, List<AggregateIntervalCollector> intervalCollectors)
            throws IOException {
        List<ProfileAggregate> profileAggregates = Lists.newArrayList();
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            ProfileAggregate liveProfileAggregate =
                    intervalCollector.getLiveProfileAggregate(transactionType, transactionName);
            if (liveProfileAggregate != null) {
                profileAggregates.add(liveProfileAggregate);
            }
        }
        return profileAggregates;
    }

    private static TransactionSummary combineTransactionSummaries(@Nullable String transactionName,
            TransactionSummary summary1, TransactionSummary summary2) {
        return TransactionSummary.builder()
                .transactionName(transactionName)
                .totalMicros(summary1.totalMicros() + summary2.totalMicros())
                .transactionCount(summary1.transactionCount() + summary2.transactionCount())
                .build();
    }

    // using non-recursive algorithm to avoid stack overflow error on deep profiles
    private static void truncateLeafs(List<ProfileNode> rootNodes, int minSamples) {
        Deque<ProfileNode> toBeVisited = new ArrayDeque<ProfileNode>();
        toBeVisited.addAll(rootNodes);
        ProfileNode node;
        while ((node = toBeVisited.poll()) != null) {
            for (Iterator<ProfileNode> i = node.getChildNodes().iterator(); i.hasNext();) {
                ProfileNode childNode = i.next();
                if (childNode.getSampleCount() < minSamples) {
                    i.remove();
                    // TODO capture sampleCount per timerName of ellipsed structure
                    // and use this in UI
                    node.setEllipsed();
                } else {
                    toBeVisited.add(childNode);
                }
            }
        }
    }

    private static List<TransactionSummary> sortTransactionSummaries(
            Iterable<TransactionSummary> transactionSummaries,
            TransactionSummarySortOrder sortOrder) {
        switch (sortOrder) {
            case TOTAL_TIME:
                return TransactionSummary.orderingByTotalTimeDesc.immutableSortedCopy(
                        transactionSummaries);
            case AVERAGE_TIME:
                return TransactionSummary.orderingByAverageTimeDesc.immutableSortedCopy(
                        transactionSummaries);
            case THROUGHPUT:
                return TransactionSummary.orderingByTransactionCountDesc.immutableSortedCopy(
                        transactionSummaries);
            default:
                throw new AssertionError("Unexpected sort order: " + sortOrder);
        }
    }
}
