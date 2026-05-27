package dbinc.sqladvisor.domain.awr.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AwrParserTest {

    private final AwrParser parser = new AwrParser();

    @Test
    void parsesVerticallyExtractedTopSqlRows() {
        String rawText = """
                SQL ordered by Elapsed Time
                Elapsed Time (s)
                CPU Time (s)
                Executions
                Elap per Exec (s)
                % Total DB Time
                SQL Id
                SQL Module
                SQL Text
                10,338
                1
                2,134
                4.84
                6.99
                5yqqyn38m2jjw
                JDBC Thin Client
                UPDATE CODE_MAKE_NO
                 SET...
                4,497
                51
                84,177
                0.05
                3.04
                1kgrkvwnjxwxy
                JDBC Thin Client
                INSERT INTO jcwdata.JUNMUN_LOG...
                615
                0
                3
                204.99
                0.42
                fv6c94crd1qaa
                SELECT COUNT(CLIM_CLM_NO)
                 ...
                Back to SQL Statistics
                """;

        AwrParser.ParsedAwr parsed = parser.parse("awr.html", rawText);

        assertThat(parsed.sqlMetrics()).hasSize(3);
        assertThat(parsed.sqlMetrics().get(0).sqlId()).isEqualTo("5yqqyn38m2jjw");
        assertThat(parsed.sqlMetrics().get(0).elapsedTimeSec()).isEqualTo(10338);
        assertThat(parsed.sqlMetrics().get(0).executions()).isEqualTo(2134);
        assertThat(parsed.sqlMetrics().get(0).sqlText()).isEqualTo("UPDATE CODE_MAKE_NO SET...");
        assertThat(parsed.sqlMetrics().get(2).sqlText()).isEqualTo("SELECT COUNT(CLIM_CLM_NO) ...");
    }
}
