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

/// A simple `CASE x WHEN v ... END` with an operand that is NULL at runtime compares the operand
/// with each WHEN value using `=`; `NULL = v` is unknown, so no branch matches. For types held
/// as Java objects (VARCHAR, long DECIMAL, JSON) the equality operator must not be invoked on
/// the null operand. These tests use a table scan (not a constant-folded VALUES list) so the
/// operand is NULL at runtime, and put the CASE inside a multi-element IN list whose probe is
/// also in the list (so the result must be TRUE for every row).
@TestInstance(PER_CLASS)
@Execution(CONCURRENT)
public class TestCaseNullOperandNpe
{
    private final QueryAssertions assertions;

    public TestCaseNullOperandNpe()
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
    private static final String NULL_INT = "if(nationkey > 100, nationkey, NULL)";

    @Test
    public void testVarcharOperand()
    {
        assertThat(assertions.query(
                "SELECT count(*) FROM nation WHERE name IN (CAST(name AS varchar(100)), " +
                        "CASE if(nationkey > 100, 'z', NULL) WHEN if(nationkey > 7, 'd', 'g') THEN 'q' ELSE 'r' END)"))
                .matches("VALUES BIGINT '25'");
    }

    @Test
    public void testLongDecimalOperand()
    {
        // dividing DECIMAL(17,16) literals by a NULL tinyint gives DECIMAL with precision > 18 (Int128)
        assertThat(assertions.query(
                "SELECT count(*) FROM nation WHERE nationkey IN (nationkey, " +
                        "CASE 0.8536943744990519 / " + NULL_INT + " WHEN 0.20020916029333746 / " + NULL_INT + " THEN 0.8479217874628734 END)"))
                .matches("VALUES BIGINT '25'");
    }

    @Test
    public void testJsonOperand()
    {
        assertThat(assertions.query(
                "SELECT count(*) FROM nation WHERE CAST(name AS JSON) IN (CAST(name AS JSON), " +
                        "CASE if(nationkey > 100, JSON '1', NULL) WHEN if(nationkey > 7, JSON '2', JSON '3') THEN JSON '4' ELSE JSON '5' END)"))
                .matches("VALUES BIGINT '25'");
    }

    @Test
    public void testBareCaseWithNullOperand()
    {
        // the CASE alone must evaluate to its ELSE value, not crash
        assertThat(assertions.query(
                "SELECT count(*) FROM (SELECT CASE " + NULL_INT + " WHEN 0 THEN 2 ELSE 3 END AS r FROM nation) WHERE r = 3"))
                .matches("VALUES BIGINT '25'");
    }
}
