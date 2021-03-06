/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.core.metadata;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.shardingsphere.core.rule.ShardingRule;
import org.apache.shardingsphere.core.rule.TableRule;
import org.apache.shardingsphere.sql.parser.binder.metadata.schema.SchemaMetaData;
import org.apache.shardingsphere.sql.parser.binder.metadata.schema.SchemaMetaDataLoader;
import org.apache.shardingsphere.sql.parser.binder.metadata.table.TableMetaData;
import org.apache.shardingsphere.sql.parser.binder.metadata.table.TableMetaDataLoader;
import org.apache.shardingsphere.underlying.common.config.properties.ConfigurationProperties;
import org.apache.shardingsphere.underlying.common.config.properties.ConfigurationPropertyKey;
import org.apache.shardingsphere.underlying.common.database.type.DatabaseType;
import org.apache.shardingsphere.underlying.common.exception.ShardingSphereException;
import org.apache.shardingsphere.underlying.common.metadata.schema.loader.RuleTableMetaDataLoader;
import org.apache.shardingsphere.underlying.common.rule.DataNode;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Table meta data loader for sharding.
 */
public final class ShardingTableMetaDataLoader implements RuleTableMetaDataLoader<ShardingRule> {
    
    private static final String LINE_SEPARATOR = System.getProperty("line.separator");
    
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();
    
    private static final int FUTURE_GET_TIME_OUT_SEC = 5;
    
    @Override
    public Map<String, TableMetaData> load(final DatabaseType databaseType, final Map<String, DataSource> dataSourceMap,
                              final ShardingRule shardingRule, final ConfigurationProperties properties) throws SQLException {
        Map<String, TableMetaData> result = new HashMap<>(shardingRule.getTableRules().size(), 1);
        for (TableRule each : shardingRule.getTableRules()) {
            load(databaseType, dataSourceMap, each.getLogicTable(), shardingRule, properties).ifPresent(tableMetaData -> result.put(each.getLogicTable(), tableMetaData));
        }
        result.putAll(loadDefaultSchemaMetaData(databaseType, dataSourceMap, shardingRule, properties).getTables());
        return result;
    }
    
    @Override
    public Optional<TableMetaData> load(final DatabaseType databaseType, final Map<String, DataSource> dataSourceMap, 
                              final String tableName, final ShardingRule shardingRule, final ConfigurationProperties properties) throws SQLException {
        if (!shardingRule.findTableRule(tableName).isPresent()) {
            return Optional.empty();
        }
        boolean isCheckingMetaData = properties.getValue(ConfigurationPropertyKey.CHECK_TABLE_METADATA_ENABLED);
        int maxConnectionsSizePerQuery = properties.getValue(ConfigurationPropertyKey.MAX_CONNECTIONS_SIZE_PER_QUERY);
        TableRule tableRule = shardingRule.getTableRule(tableName);
        if (!isCheckingMetaData) {
            DataNode dataNode = tableRule.getActualDataNodes().iterator().next();
            return Optional.of(TableMetaDataLoader.load(dataSourceMap.get(shardingRule.getShardingDataSourceNames()
                    .getRawMasterDataSourceName(dataNode.getDataSourceName())), dataNode.getTableName(), databaseType.getName()));
        }
        Map<String, TableMetaData> actualTableMetaDataMap = parallelLoadTables(databaseType, dataSourceMap, tableRule, maxConnectionsSizePerQuery);
        checkUniformed(tableRule.getLogicTable(), actualTableMetaDataMap, shardingRule);
        return Optional.of(actualTableMetaDataMap.values().iterator().next());
    }
    
    private Map<String, TableMetaData> parallelLoadTables(final DatabaseType databaseType, final Map<String, DataSource> dataSourceMap, 
                                                          final TableRule tableRule, final int maxConnectionsSizePerQuery) {
        Map<String, List<DataNode>> dataNodeGroups = tableRule.getDataNodeGroups();
        Map<String, TableMetaData> actualTableMetaDataMap = new HashMap<>(dataNodeGroups.size(), 1);
        Map<String, Future<TableMetaData>> tableFutureMap = new HashMap<>(dataNodeGroups.size(), 1);
        ExecutorService executorService = Executors.newFixedThreadPool(Math.min(CPU_CORES * 2, dataNodeGroups.size() * maxConnectionsSizePerQuery));
        for (Entry<String, List<DataNode>> entry : dataNodeGroups.entrySet()) {
            for (DataNode each : entry.getValue()) {
                Future<TableMetaData> futures = executorService.submit(() -> loadTableByDataNode(each, databaseType, dataSourceMap));
                tableFutureMap.put(each.getTableName(), futures);
            }
        }
        tableFutureMap.forEach((key, value) -> {
            try {
                TableMetaData tableMetaData = value.get(FUTURE_GET_TIME_OUT_SEC, TimeUnit.SECONDS);
                actualTableMetaDataMap.put(key, tableMetaData);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new IllegalStateException(String.format("Error while fetching tableMetaData with key= %s and Value=%s", key, value), e);
            }
        });
        executorService.shutdownNow();
        return actualTableMetaDataMap;
    }
    
    private SchemaMetaData loadDefaultSchemaMetaData(final DatabaseType databaseType, final Map<String, DataSource> dataSourceMap, 
                                                     final ShardingRule shardingRule, final ConfigurationProperties properties) throws SQLException {
        int maxConnectionsSizePerQuery = properties.getValue(ConfigurationPropertyKey.MAX_CONNECTIONS_SIZE_PER_QUERY);
        Optional<String> actualDefaultDataSourceName = shardingRule.findActualDefaultDataSourceName();
        return actualDefaultDataSourceName.isPresent()
                ? SchemaMetaDataLoader.load(dataSourceMap.get(actualDefaultDataSourceName.get()), maxConnectionsSizePerQuery, databaseType.getName())
                : new SchemaMetaData(Collections.emptyMap());
    }
    
    private TableMetaData loadTableByDataNode(final DataNode dataNode, final DatabaseType databaseType, final Map<String, DataSource> dataSourceMap) {
        try {
            return TableMetaDataLoader.load(dataSourceMap.get(dataNode.getDataSourceName()), dataNode.getTableName(), databaseType.getName());
        } catch (SQLException e) {
            throw new IllegalStateException(String.format("SQLException for DataNode=%s and databaseType=%s", dataNode, databaseType.getName()), e);
        }
    }
    
    private void checkUniformed(final String logicTableName, final Map<String, TableMetaData> actualTableMetaDataMap, final ShardingRule shardingRule) {
        ShardingTableMetaDataDecorator decorator = new ShardingTableMetaDataDecorator();
        TableMetaData sample = decorator.decorate(actualTableMetaDataMap.values().iterator().next(), logicTableName, shardingRule);
        Collection<TableMetaDataViolation> violations = actualTableMetaDataMap.entrySet().stream()
                .filter(entry -> !sample.equals(decorator.decorate(entry.getValue(), logicTableName, shardingRule)))
                .map(entry -> new TableMetaDataViolation(entry.getKey(), entry.getValue())).collect(Collectors.toList());
        throwExceptionIfNecessary(violations, logicTableName);
    }
    
    private void throwExceptionIfNecessary(final Collection<TableMetaDataViolation> violations, final String logicTableName) {
        if (!violations.isEmpty()) {
            StringBuilder errorMessage = new StringBuilder(
                    "Cannot get uniformed table structure for logic table `%s`, it has different meta data of actual tables are as follows:").append(LINE_SEPARATOR);
            for (TableMetaDataViolation each : violations) {
                errorMessage.append("actual table: ").append(each.getActualTableName()).append(", meta data: ").append(each.getTableMetaData()).append(LINE_SEPARATOR);
            }
            throw new ShardingSphereException(errorMessage.toString(), logicTableName);
        }
    }
    
    @Override
    public int getOrder() {
        return 1;
    }
    
    @Override
    public Class<ShardingRule> getType() {
        return ShardingRule.class;
    }
    
    @RequiredArgsConstructor
    @Getter
    private final class TableMetaDataViolation {
        
        private final String actualTableName;
        
        private final TableMetaData tableMetaData;
    }
}
