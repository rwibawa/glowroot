/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.central.repo;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.base.Optional;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.util.Cache;
import org.glowroot.central.util.Cache.CacheLoader;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common.repo.AgentRollupRepository;
import org.glowroot.common.repo.ImmutableAgentRollup;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

import static com.google.common.base.Preconditions.checkNotNull;

public class AgentRollupDao implements AgentRollupRepository {

    private static final Logger logger = LoggerFactory.getLogger(AgentRollupDao.class);

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;
    private final AgentConfigDao agentConfigDao;

    private final PreparedStatement readPS;
    private final PreparedStatement readParentIdPS;
    private final PreparedStatement insertPS;
    private final PreparedStatement insertLastCaptureTimePS;

    private final PreparedStatement isAgentPS;

    private final PreparedStatement deletePS;

    private final Cache<String, Optional<String>> agentRollupIdCache;

    AgentRollupDao(Session session, AgentConfigDao agentConfigDao, ClusterManager clusterManager)
            throws Exception {
        this.session = session;
        this.agentConfigDao = agentConfigDao;

        session.execute("create table if not exists agent_rollup (one int, agent_rollup_id varchar,"
                + " parent_agent_rollup_id varchar, agent boolean, last_capture_time timestamp,"
                + " primary key (one, agent_rollup_id)) " + WITH_LCS);

        try {
            cleanUpAgentRollupTable(session);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }

        readPS = session.prepare("select agent_rollup_id, parent_agent_rollup_id, agent,"
                + " last_capture_time from agent_rollup where one = 1");
        readParentIdPS = session.prepare("select parent_agent_rollup_id from agent_rollup where"
                + " one = 1 and agent_rollup_id = ?");
        insertPS = session.prepare("insert into agent_rollup (one, agent_rollup_id,"
                + " parent_agent_rollup_id, agent) values (1, ?, ?, ?)");
        // it would be nice to use "update ... if not exists" here, which would eliminate the need
        // to filter incomplete records in readAgentRollups() and eliminate the need to clean up
        // table occassionally in cleanUpAgentRollupTable(), but "if not exists" easily leads to
        // lots of timeout errors "Cassandra timeout during write query at consistency SERIAL"
        insertLastCaptureTimePS = session.prepare("insert into agent_rollup (one, agent_rollup_id,"
                + " last_capture_time) values (1, ?, ?)");

        isAgentPS = session
                .prepare("select agent from agent_rollup where one = 1 and agent_rollup_id = ?");

        deletePS =
                session.prepare("delete from agent_rollup where one = 1 and agent_rollup_id = ?");

        agentRollupIdCache =
                clusterManager.createCache("agentRollupIdCache", new AgentRollupIdCacheLoader());
    }

    public void store(String agentId, @Nullable String agentRollupId) throws Exception {
        insert(agentId, agentRollupId, true);
        if (agentRollupId != null) {
            List<String> agentRollupIds = getAgentRollupIds(agentRollupId);
            for (int j = agentRollupIds.size() - 1; j >= 0; j--) {
                String loopAgentRollupId = agentRollupIds.get(j);
                String loopParentAgentRollupId = j == 0 ? null : agentRollupIds.get(j - 1);
                insert(loopAgentRollupId, loopParentAgentRollupId, false);
            }
        }
    }

    @Override
    public List<AgentRollup> readAgentRollups() throws Exception {
        ResultSet results = session.execute(readPS.bind());
        Set<AgentRollupRecord> topLevel = Sets.newHashSet();
        Multimap<String, AgentRollupRecord> childMultimap = ArrayListMultimap.create();
        for (Row row : results) {
            int i = 0;
            String id = checkNotNull(row.getString(i++));
            String parentId = row.getString(i++);
            if (row.isNull(i)) {
                // this row was created by insertLastCaptureTimePS, but there has not been a
                // collectInit() for it yet, so exclude it from layout service (to avoid
                // AgentConfigNotFoundException)
                continue;
            }
            boolean agent = row.getBool(i++);
            Date lastCaptureTime = row.getTimestamp(i++);
            AgentRollupRecord agentRollupRecord = ImmutableAgentRollupRecord.builder()
                    .id(id)
                    .agent(agent)
                    .lastCaptureTime(lastCaptureTime)
                    .build();
            if (parentId == null) {
                topLevel.add(agentRollupRecord);
            } else {
                childMultimap.put(parentId, agentRollupRecord);
            }
        }
        List<AgentRollup> agentRollups = Lists.newArrayList();
        for (AgentRollupRecord topLevelAgentRollup : topLevel) {
            agentRollups.add(createAgentRollup(topLevelAgentRollup, childMultimap));
        }
        agentRollups.sort(Comparator.comparing(AgentRollup::display));
        return agentRollups;
    }

    @Override
    public String readAgentRollupDisplay(String agentRollupId) throws Exception {
        AgentConfig agentConfig = agentConfigDao.read(agentRollupId);
        if (agentConfig == null) {
            return agentRollupId;
        }
        String display = agentConfig.getGeneralConfig().getDisplay();
        if (display.isEmpty()) {
            return agentRollupId;
        }
        return display;
    }

    @Override
    public boolean isAgent(String agentRollupId) throws Exception {
        BoundStatement boundStatement = isAgentPS.bind();
        boundStatement.setString(0, agentRollupId);
        Row row = session.execute(boundStatement).one();
        if (row == null) {
            return false;
        }
        return row.getBool(0);
    }

    // includes agentId itself
    // agentId is index 0
    // its direct parent is index 1
    // etc...
    public List<String> readAgentRollupIds(String agentId) throws Exception {
        String agentRollupId = agentRollupIdCache.get(agentId).orNull();
        if (agentRollupId == null) {
            // agent must have been manually deleted
            return ImmutableList.of(agentId);
        }
        List<String> agentRollupIds = getAgentRollupIds(agentRollupId);
        Collections.reverse(agentRollupIds);
        agentRollupIds.add(0, agentId);
        return agentRollupIds;
    }

    ListenableFuture<ResultSet> updateLastCaptureTime(String agentId, long captureTime)
            throws Exception {
        BoundStatement boundStatement = insertLastCaptureTimePS.bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        return session.executeAsync(boundStatement);
    }

    void delete(String agentRollupId) throws Exception {
        BoundStatement boundStatement = deletePS.bind();
        boundStatement.setString(0, agentRollupId);
        session.execute(boundStatement);
    }

    private void insert(String agentRollupId, @Nullable String parentAgentRollupId, boolean agent)
            throws Exception {
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, parentAgentRollupId);
        boundStatement.setBool(i++, agent);
        session.execute(boundStatement);

        agentRollupIdCache.invalidate(agentRollupId);
    }

    private AgentRollup createAgentRollup(AgentRollupRecord agentRollupRecord,
            Multimap<String, AgentRollupRecord> parentChildMap) throws Exception {
        Collection<AgentRollupRecord> childAgentRollupRecords =
                parentChildMap.get(agentRollupRecord.id());
        ImmutableAgentRollup.Builder builder = ImmutableAgentRollup.builder()
                .id(agentRollupRecord.id())
                .display(readAgentRollupDisplay(agentRollupRecord.id()))
                .agent(agentRollupRecord.agent())
                .lastCaptureTime(agentRollupRecord.lastCaptureTime());
        List<AgentRollup> childAgentRollups = Lists.newArrayList();
        for (AgentRollupRecord childAgentRollupRecord : childAgentRollupRecords) {
            childAgentRollups.add(createAgentRollup(childAgentRollupRecord, parentChildMap));
        }
        childAgentRollups.sort(Comparator.comparing(AgentRollup::display));
        return builder.addAllChildren(childAgentRollups)
                .build();
    }

    static List<String> getAgentRollupIds(String agentRollupId) {
        List<String> agentRollupIds = Lists.newArrayList();
        int lastFoundIndex = -1;
        int nextFoundIndex;
        while ((nextFoundIndex = agentRollupId.indexOf('/', lastFoundIndex)) != -1) {
            agentRollupIds.add(agentRollupId.substring(0, nextFoundIndex));
            lastFoundIndex = nextFoundIndex + 1;
        }
        agentRollupIds.add(agentRollupId);
        return agentRollupIds;
    }

    private static void cleanUpAgentRollupTable(Session session) throws Exception {
        ResultSet results =
                session.execute("select agent_rollup_id, agent from agent_rollup");
        PreparedStatement deletePS = session.prepare(
                "delete from agent_rollup where one = 1 and agent_rollup_id = ? if agent = null");
        for (Row row : results) {
            if (row.isNull(1)) {
                // this row was created by insertLastCaptureTimePS, but there has not been a
                // collectInit() for it yet, so exclude it from layout service (to avoid
                // AgentConfigNotFoundException)
                BoundStatement boundStatement = deletePS.bind();
                boundStatement.setString(0, checkNotNull(row.getString(0)));
                session.execute(boundStatement);
            }
        }
    }

    @Value.Immutable
    public interface AgentConfigUpdate {
        AgentConfig config();
        UUID configUpdateToken();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface AgentRollupRecord {
        String id();
        boolean agent();
        @Nullable
        Date lastCaptureTime();
    }

    private class AgentRollupIdCacheLoader implements CacheLoader<String, Optional<String>> {
        @Override
        public Optional<String> load(String agentId) throws Exception {
            BoundStatement boundStatement = readParentIdPS.bind();
            boundStatement.setString(0, agentId);
            ResultSet results = session.execute(boundStatement);
            Row row = results.one();
            if (row == null) {
                return Optional.absent();
            }
            return Optional.fromNullable(row.getString(0));
        }
    }
}
