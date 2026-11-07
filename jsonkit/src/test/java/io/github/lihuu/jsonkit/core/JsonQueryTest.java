package io.github.lihuu.jsonkit.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonQueryTest {

    private static final String DOCUMENT = """
            {
              "user": {"name": "Alice"},
              "items": [
                {"id": 1, "status": "failed"},
                {"id": 2, "status": "ok"},
                {"id": 3, "status": "failed"}
              ],
              "a.b": {"c": true},
              "unicode": {"键": "值"},
              "nullValue": null,
              "emptyArray": [],
              "nested": [[1, 2], [3]]
            }
            """;

    private static List<String> texts(List<Object> values) {
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            result.add(String.valueOf(value));
        }
        return result;
    }

    private static JsonQuery.QueryResult query(String path) throws Exception {
        Object document = JsonTreeReader.read(
                new ByteArrayInputStream(DOCUMENT.getBytes(StandardCharsets.UTF_8)), "<stdin>");
        return JsonQuery.query(document, path);
    }

    @Test
    void normalizesTheDocumentedShorthandForms() throws Exception {
        assertEquals("$.user.name", PathExpression.normalize("user.name"));
        assertEquals("$.user.name", PathExpression.normalize(".user.name"));
        assertEquals("$[0].id", PathExpression.normalize("[0].id"));
        assertEquals("$['a.b']", PathExpression.normalize("['a.b']"));
        assertEquals("$.items[?@.status == 'failed'].id",
                PathExpression.normalize("items[?@.status == 'failed'].id"));
        assertEquals("$..id", PathExpression.normalize("$..id"));
        assertEquals("$", PathExpression.normalize("$"));
    }

    @Test
    void rejectsAnEmptyPath() {
        assertThrows(InvalidPathException.class, () -> PathExpression.normalize(""));
        assertThrows(InvalidPathException.class, () -> PathExpression.normalize("   "));
        assertThrows(InvalidPathException.class, () -> JsonQuery.query(null, ""));
    }

    @Test
    void rejectsInvalidJsonPathSyntax() {
        InvalidPathException failure = assertThrows(InvalidPathException.class, () -> query("$["));

        assertTrue(failure.getMessage().contains("invalid JSONPath"), failure.getMessage());
    }

    @Test
    void standardAndShorthandFormsAgree() throws Exception {
        assertEquals(query("$.user.name").values(), query("user.name").values());
        assertEquals(query("$.items[0].id").values(), query("items[0].id").values());
    }

    @Test
    void singularPathsSelectOneValue() throws Exception {
        JsonQuery.QueryResult result = query("user.name");

        assertTrue(result.singular());
        assertEquals("$.user.name", result.expression());
        assertEquals(List.of("Alice"), result.values());
    }

    @Test
    void singularPathCanSelectAStringWithADotInTheKey() throws Exception {
        assertEquals(List.of(true), query("['a.b'].c").values());
    }

    @Test
    void singularPathSupportsUnicodeKeys() throws Exception {
        assertEquals(List.of("值"), query("unicode.键").values());
    }

    @Test
    void singularPathReportsNoMatchAsAnEmptyList() throws Exception {
        JsonQuery.QueryResult result = query("user.missing");

        assertTrue(result.singular());
        assertTrue(result.values().isEmpty());
    }

    @Test
    void singularPathKeepsAJsonNullAsAResult() throws Exception {
        JsonQuery.QueryResult result = query("nullValue");

        assertTrue(result.singular());
        assertEquals(Arrays.asList((Object) null), result.values());
    }

    @Test
    void singularPathKeepsASelectedArrayAsAnArray() throws Exception {
        JsonQuery.QueryResult result = query("items");

        assertTrue(result.singular());
        assertEquals(1, result.values().size());
        assertTrue(result.values().get(0) instanceof List<?>);
    }

    @Test
    void rootPathSelectsTheWholeDocument() throws Exception {
        JsonQuery.QueryResult result = query("$");

        assertTrue(result.singular());
        assertEquals(1, result.values().size());
        assertTrue(result.values().get(0) instanceof java.util.Map<?, ?>);
    }

    @Test
    void wildcardPathsAreNotSingular() throws Exception {
        JsonQuery.QueryResult result = query("items[*].id");

        assertFalse(result.singular());
        assertEquals(List.of("1", "2", "3"), texts(result.values()));
    }

    @Test
    void slicesAreNotSingular() throws Exception {
        JsonQuery.QueryResult result = query("items[0:2].id");

        assertFalse(result.singular());
        assertEquals(List.of("1", "2"), texts(result.values()));
    }

    @Test
    void selectorListsAreNotSingular() throws Exception {
        JsonQuery.QueryResult result = query("items[0,2].id");

        assertFalse(result.singular());
        assertEquals(List.of("1", "3"), texts(result.values()));
    }

    @Test
    void descendantQueriesAreNotSingular() throws Exception {
        JsonQuery.QueryResult result = query("$..id");

        assertFalse(result.singular());
        assertEquals(List.of("1", "2", "3"), texts(result.values()));
    }

    @Test
    void filtersUseStandardJsonPathSemantics() throws Exception {
        JsonQuery.QueryResult result = query("$.items[?@.status == 'failed'].id");

        assertFalse(result.singular());
        assertEquals(List.of("1", "3"), texts(result.values()));
    }

    @Test
    void filtersSupportMatchAndSearchFunctions() throws Exception {
        assertEquals(List.of("1", "3"), texts(query("items[?match(@.status, 'fail.*')].id").values()));
        assertEquals(List.of("1", "3"), texts(query("items[?search(@.status, 'ail')].id").values()));
    }

    @Test
    void filtersCompareNumbersByValue() throws Exception {
        assertEquals(List.of("3"), texts(query("items[?@.id == 3].id").values()));
        assertEquals(List.of("2", "3"), texts(query("items[?@.id >= 2].id").values()));
    }

    @Test
    void filtersSupportLengthAndCountFunctions() throws Exception {
        assertEquals(List.of("2"), texts(query("items[?length(@.status) == 2].id").values()));
        assertEquals(List.of("1", "2", "3"), texts(query("items[?count(@.*) == 2].id").values()));
    }

    @Test
    void theRootPathCanSelectANullDocument() throws Exception {
        Object document = JsonTreeReader.read(
                new ByteArrayInputStream("null".getBytes(StandardCharsets.UTF_8)), "<stdin>");

        JsonQuery.QueryResult result = JsonQuery.query(document, "$");

        assertTrue(result.singular());
        assertEquals(Arrays.asList((Object) null), result.values());
    }

    @Test
    void multiValueQueriesKeepInputMemberOrder() throws Exception {
        assertEquals(List.of("failed", "ok", "failed"), query("items[*].status").values());
    }

    @Test
    void multiValueQueriesReportNoMatchAsAnEmptyList() throws Exception {
        JsonQuery.QueryResult result = query("items[*].missing");

        assertFalse(result.singular());
        assertTrue(result.values().isEmpty());
    }
}
