/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.e2e.connector.hbase;

import org.apache.seatunnel.e2e.common.TestResource;
import org.apache.seatunnel.e2e.common.TestSuiteBase;
import org.apache.seatunnel.e2e.common.container.EngineType;
import org.apache.seatunnel.e2e.common.container.TestContainer;
import org.apache.seatunnel.e2e.common.junit.DisabledOnContainer;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestTemplate;
import org.testcontainers.containers.Container;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

@Slf4j
@DisabledOnContainer(
        value = {},
        type = {EngineType.SEATUNNEL},
        disabledReason = "The hbase container authentication configuration is incorrect.")
public class HbaseIT extends TestSuiteBase implements TestResource {

    private static final String TABLE_NAME = "seatunnel_test";
    private static final String MULTI_TABLE_ONE_NAME = "hbase_sink_1";

    private static final String MULTI_TABLE_TWO_NAME = "hbase_sink_2";

    private static final String FAMILY_NAME = "info";

    private Connection hbaseConnection;

    private Admin admin;

    private TableName table;

    private HbaseCluster hbaseCluster;

    @BeforeAll
    @Override
    public void startUp() throws Exception {
        hbaseCluster = new HbaseCluster();
        hbaseConnection = hbaseCluster.startService();
        // Create table for hbase sink test
        log.info("initial");
        hbaseCluster.createTable(TABLE_NAME, Arrays.asList(FAMILY_NAME));
        table = TableName.valueOf(TABLE_NAME);
        // Create table for hbase multi-table sink test
        hbaseCluster.createTable(MULTI_TABLE_ONE_NAME, Arrays.asList(FAMILY_NAME));
        hbaseCluster.createTable(MULTI_TABLE_TWO_NAME, Arrays.asList(FAMILY_NAME));
    }

    @AfterAll
    @Override
    public void tearDown() throws Exception {
        if (Objects.nonNull(admin)) {
            admin.close();
        }
        hbaseCluster.stopService();
    }

    @TestTemplate
    public void testHbaseSink(TestContainer container) throws IOException, InterruptedException {
        deleteData(table);
        Container.ExecResult sinkExecResult = container.executeJob("/fake-to-hbase.conf");
        Assertions.assertEquals(0, sinkExecResult.getExitCode());
        ArrayList<Result> results = readData(table);
        Assertions.assertEquals(results.size(), 5);
        Container.ExecResult sourceExecResult = container.executeJob("/hbase-to-assert.conf");
        Assertions.assertEquals(0, sourceExecResult.getExitCode());
    }

    @TestTemplate
    public void testHbaseSinkWithArray(TestContainer container)
            throws IOException, InterruptedException {
        deleteData(table);
        Container.ExecResult execResult = container.executeJob("/fake-to-hbase-array.conf");
        Assertions.assertEquals(0, execResult.getExitCode());
        Table hbaseTable = hbaseConnection.getTable(table);
        Scan scan = new Scan();
        ResultScanner scanner = hbaseTable.getScanner(scan);
        ArrayList<Result> results = new ArrayList<>();
        for (Result result : scanner) {
            String rowKey = Bytes.toString(result.getRow());
            for (Cell cell : result.listCells()) {
                String columnName = Bytes.toString(CellUtil.cloneQualifier(cell));
                String value = Bytes.toString(CellUtil.cloneValue(cell));
                if ("A".equals(rowKey) && "info:c_array_string".equals(columnName)) {
                    Assertions.assertEquals(value, "\"a\",\"b\",\"c\"");
                }
                if ("B".equals(rowKey) && "info:c_array_int".equals(columnName)) {
                    Assertions.assertEquals(value, "4,5,6");
                }
            }
            results.add(result);
        }
        Assertions.assertEquals(results.size(), 3);
        scanner.close();
    }

    @DisabledOnContainer(
            value = {},
            type = {EngineType.SPARK, EngineType.FLINK},
            disabledReason = "Currently SPARK/FLINK do not support multiple table write")
    public void testHbaseMultiTableSink(TestContainer container)
            throws IOException, InterruptedException {
        TableName multiTable1 = TableName.valueOf(MULTI_TABLE_ONE_NAME);
        TableName multiTable2 = TableName.valueOf(MULTI_TABLE_TWO_NAME);
        deleteData(multiTable1);
        deleteData(multiTable2);
        Container.ExecResult sinkExecResult =
                container.executeJob("/fake-to-hbase-with-multipletable.conf");
        Assertions.assertEquals(0, sinkExecResult.getExitCode());
        ArrayList<Result> results = readData(multiTable1);
        Assertions.assertEquals(results.size(), 1);
        results = readData(multiTable2);
        Assertions.assertEquals(results.size(), 1);
    }

    private void deleteData(TableName table) throws IOException {
        Table hbaseTable = hbaseConnection.getTable(table);
        Scan scan = new Scan();
        ResultScanner scanner = hbaseTable.getScanner(scan);
        // Delete the data generated by the test
        for (Result result = scanner.next(); result != null; result = scanner.next()) {
            Delete deleteRow = new Delete(result.getRow());
            hbaseTable.delete(deleteRow);
        }
    }

    public ArrayList<Result> readData(TableName table) throws IOException {
        Table hbaseTable = hbaseConnection.getTable(table);
        Scan scan = new Scan();
        ResultScanner scanner = hbaseTable.getScanner(scan);
        ArrayList<Result> results = new ArrayList<>();
        for (Result result : scanner) {
            results.add(result);
        }
        scanner.close();
        return results;
    }
}