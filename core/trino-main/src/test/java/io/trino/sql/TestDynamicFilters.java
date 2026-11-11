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
package io.trino.sql;

import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slices;
import io.trino.json.Json;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.Range;
import io.trino.spi.predicate.ValueSet;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.plan.DynamicFilterId;
import org.junit.jupiter.api.Test;

import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.sql.ir.ComparisonOperator.EQUAL;
import static io.trino.sql.ir.ComparisonOperator.LESS_THAN;
import static io.trino.type.JsonType.JSON;
import static org.assertj.core.api.Assertions.assertThat;

public class TestDynamicFilters
{
    @Test
    public void testApplyComparisonOnDiscreteValueSet()
    {
        // JSON is equatable but not orderable, so a collected dynamic filter summary for a JSON
        // join key is a discrete ValueSet whose getRanges() throws UnsupportedOperationException
        Symbol symbol = new Symbol(JSON, "c");
        DynamicFilterId filterId = new DynamicFilterId("df");
        Json jsonValue = Json.unchecked(Slices.utf8Slice("1"));
        Domain discreteDomain = Domain.create(ValueSet.copyOf(JSON, ImmutableList.of(jsonValue)), false);

        // EQUAL keeps the discrete values
        assertThat(new DynamicFilters.Descriptor(filterId, symbol.toSymbolReference(), EQUAL, false).applyComparison(discreteDomain))
                .isEqualTo(discreteDomain);
        // EQUAL with null allowed (IS NOT DISTINCT FROM)
        assertThat(new DynamicFilters.Descriptor(filterId, symbol.toSymbolReference(), EQUAL, true).applyComparison(discreteDomain))
                .isEqualTo(Domain.create(discreteDomain.getValues(), true));

        // a non-equality comparison cannot be represented as ranges over a discrete set:
        // the filter must be skipped (Domain.all) instead of throwing UnsupportedOperationException
        assertThat(new DynamicFilters.Descriptor(filterId, symbol.toSymbolReference(), LESS_THAN, false).applyComparison(discreteDomain))
                .isEqualTo(Domain.all(JSON));
    }

    @Test
    public void testApplyComparisonOnRangeValueSet()
    {
        // orderable types keep the range-based behavior
        Symbol symbol = new Symbol(INTEGER, "c");
        DynamicFilterId filterId = new DynamicFilterId("df");
        Domain domain = Domain.multipleValues(INTEGER, ImmutableList.of(1L, 2L, 3L));

        assertThat(new DynamicFilters.Descriptor(filterId, symbol.toSymbolReference(), EQUAL, false).applyComparison(domain))
                .isEqualTo(domain);
        assertThat(new DynamicFilters.Descriptor(filterId, symbol.toSymbolReference(), LESS_THAN, false).applyComparison(domain))
                .isEqualTo(Domain.create(ValueSet.ofRanges(Range.lessThan(INTEGER, 3L)), false));
    }
}
