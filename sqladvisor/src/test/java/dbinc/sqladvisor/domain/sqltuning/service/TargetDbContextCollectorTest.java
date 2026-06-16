package dbinc.sqladvisor.domain.sqltuning.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TargetDbContextCollectorTest {

    @Test
    void extractsInsertTargetWhenOracleHintAppearsBeforeInto() throws Exception {
        TargetDbContextCollector collector = new TargetDbContextCollector(mock(TargetDbConnectionService.class));
        Method method = TargetDbContextCollector.class.getDeclaredMethod("extractDmlTargetReferences", String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Object> targets = (List<Object>) method.invoke(collector, """
                INSERT /*+ APPEND */ INTO test.AX_ORDERS (ORDER_ID, ORDER_DATE, AMOUNT, STATUS)
                SELECT test.SEQ_AX_ORDER_ID.NEXTVAL, T.ORDER_DATE, T.AMOUNT, T.STATUS
                  FROM DUAL T
                """);

        assertThat(targets)
                .extracting(Object::toString)
                .containsExactly("TableReference[owner=TEST, tableName=AX_ORDERS]");
    }
}
