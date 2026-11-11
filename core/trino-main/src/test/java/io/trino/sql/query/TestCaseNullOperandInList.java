/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.sql.query;

import com.google.common.collect.ImmutableMap;
import io.trino.Session;
import io.trino.plugin.tpch.TpchPlugin;
import io.trino.testing.QueryRunner;
import io.trino.testing.StandaloneQueryRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;

import static io.trino.testing.TestingHandles.TEST_CATALOG_NAME;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.junit.jupiter.api.parallel.ExecutionMode.CONCURRENT;

/// A simple `CASE x WHEN ... END` whose operand is NULL at runtime must not make a surrounding
/// multi-element `IN` list evaluate to NULL: with a NULL operand no WHEN branch matches, so the
/// CASE returns its ELSE value and the IN must be TRUE or FALSE. These tests use a table scan
/// (not a constant-folded VALUES list) so the operand is NULL at runtime.
@TestInstance(PER_CLASS)
@Execution(CONCURRENT)
public class TestCaseNullOperandInList
{
    private final QueryAssertions assertions;

    public TestCaseNullOperandInList()
    {
        Session session = testSessionBuilder()
                .setCatalog(TEST_CATALOG_NAME)
                .setSchema("tiny")
                .build();
        QueryRunner runner = new StandaloneQueryRunner(session);
        runner.installPlugin(new TpchPlugin());
        runner.createCatalog(TEST_CATALOG_NAME, "tpch", ImmutableMap.of("tpch.splits-per-node", "1"));
        assertions = new QueryAssertions(runner);
    }

    @AfterAll
    public void teardown()
    {
        assertions.close();
    }

    // evaluates to NULL for every row of tpch.tiny.nation (nationkey <= 24), read from a table
    private static final String NULL_OPERAND = "if(nationkey > 100, nationkey, NULL)";

    @Test
    public void testInListWithNullCaseOperand()
    {
        // x = NULL: CASE takes the ELSE branch (3), so 1 IN (3, 4) is false for every row
        assertThat(assertions.query(
                "SELECT DISTINCT x, 1 IN (CASE x WHEN 0 THEN 2 ELSE 3 END, 4), (1 IN (CASE x WHEN 0 THEN 2 ELSE 3 END, 4)) IS NULL " +
                        "FROM (SELECT " + NULL_OPERAND + " AS x FROM nation)"))
                .matches("VALUES (CAST(NULL AS BIGINT), false, false)");

        // WHERE NOT p must keep all rows
        assertThat(assertions.query(
                "SELECT count(*) FROM nation WHERE NOT (1 IN (CASE " + NULL_OPERAND + " WHEN 0 THEN 2 ELSE 3 END, 4))"))
                .matches("VALUES BIGINT '25'");

        assertThat(assertions.query(
                "SELECT count(*) FROM nation WHERE 1 NOT IN (CASE " + NULL_OPERAND + " WHEN 0 THEN 2 ELSE 3 END, 4)"))
                .matches("VALUES BIGINT '25'");
    }

    @Test
    public void testProbeInListWithNullCaseOperand()
    {
        // 58 IN (58, <anything>) is always TRUE; the first list element already matches
        assertThat(assertions.query(
                "SELECT count(*) FROM nation WHERE nationkey IN (nationkey, CASE 1 / " + NULL_OPERAND + " WHEN 2 / " + NULL_OPERAND + " THEN 1 END)"))
                .matches("VALUES BIGINT '25'");
    }
}
