package app.tozzi.core;

import app.tozzi.annotation.Projectable;
import app.tozzi.entity.*;
import app.tozzi.exception.InvalidFieldException;
import app.tozzi.exception.JPASearchException;
import app.tozzi.model.MyModel;
import app.tozzi.repository.MyRepository;
import app.tozzi.util.ReflectionUtils;
import jakarta.persistence.*;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Selection;
import lombok.Data;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@EnableAutoConfiguration
@ContextConfiguration(classes = {ProjectionBugVerificationTest.class})
@EntityScan(basePackages = {"app.tozzi.entity", "app.tozzi.core"})
@TestPropertySource(properties = {"spring.jpa.show-sql=true"})
@EnableJpaRepositories("app.tozzi.repository")
public class ProjectionBugVerificationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MyRepository myRepository;

    // Support entities for the @MappedSuperclass test scenario
    @MappedSuperclass
    @Data
    public static class BaseAuditEntity {
        @Id
        private Long id;
        private String auditField;
    }

    @Entity
    @Table(name = "derived_for_bug3")
    @Data
    @lombok.EqualsAndHashCode(callSuper = false)
    public static class DerivedEntity extends BaseAuditEntity {
        private String derivedField;
    }

    @Data
    public static class DerivedModel {
        @Projectable(entityFieldKey = "auditField")
        private String auditField;
    }

    @BeforeEach
    void setUp() {
        var t6a = TestEntity6.builder().id(1L).colTest6("col6_A").build();
        var t6b = TestEntity6.builder().id(2L).colTest6("col6_B").build();
        var t6c = TestEntity6.builder().id(3L).colTest6("col6_C").build();
        entityManager.persist(t6a);
        entityManager.persist(t6b);
        entityManager.persist(t6c);

        var t1a = TestEntity1.builder().id(1L).colTest1("col1_A").entity6s(new HashSet<>(Set.of(t6a, t6b))).build();
        var t2a = TestEntity2.builder().id(1L).colTest2("col2_A")
                .entities4(new HashSet<>(Set.of(TestEntity4.builder().id(1L).colTest4("col4_A")
                        .entity5(TestEntity5.builder().id(1L).colTest5("col5_A").build()).build()))).build();

        myRepository.save(MyEntity.builder().id(1L).email("entity-a@test.com")
                .test1(t1a).test2(t2a).test3(TestEntity3.builder().id(1L).colTest3("col3_A").build())
                .keywords(new ArrayList<>(List.of("kw1", "kw2"))).build());

        var t1b = TestEntity1.builder().id(2L).colTest1("col1_B").entity6s(new HashSet<>(Set.of(t6c))).build();
        var t2b = TestEntity2.builder().id(2L).colTest2("col2_B")
                .entities4(new HashSet<>(Set.of(TestEntity4.builder().id(2L).colTest4("col4_B")
                        .entity5(TestEntity5.builder().id(2L).colTest5("col5_B").build()).build()))).build();

        myRepository.save(MyEntity.builder().id(2L).email("entity-b@test.com")
                .test1(t1b).test2(t2b).test3(TestEntity3.builder().id(2L).colTest3("col3_B").build())
                .keywords(new ArrayList<>(List.of("kw3"))).build());

        // entity with no relations (test1=null, test2=null) – used for LEFT vs INNER join tests
        myRepository.save(MyEntity.builder().id(3L).email("entity-c-notest1@test.com").build());

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void joinType_leftIncludesNullRelation_innerExcludesIt() {
        var cb = entityManager.getCriteriaBuilder();

        var cqLeft = cb.createTupleQuery();
        var rootLeft = cqLeft.from(MyEntity.class);
        var selectionsLeft = JPAProjectionProcessor.loadSelection(
                List.of("stringMail", "mySubModel.searchMe"), rootLeft, MyEntity.class,
                ReflectionUtils.getAllProjectableFields(MyModel.class),
                ReflectionUtils.getIdFields(MyEntity.class), true, false, null);
        cqLeft.multiselect(selectionsLeft);
        var resultLeft = JPAProjectionProcessor.toMap(entityManager.createQuery(cqLeft).getResultList(),
                MyEntity.class, selectionsLeft, ReflectionUtils.getIdFields(MyEntity.class));

        var cqInner = cb.createTupleQuery();
        var rootInner = cqInner.from(MyEntity.class);
        var selectionsInner = JPAProjectionProcessor.loadSelection(
                List.of("stringMail", "mySubModel.searchMe"), rootInner, MyEntity.class,
                ReflectionUtils.getAllProjectableFields(MyModel.class),
                ReflectionUtils.getIdFields(MyEntity.class), true, true, Map.of("test2", JoinType.INNER));
        cqInner.multiselect(selectionsInner);
        var resultInner = JPAProjectionProcessor.toMap(entityManager.createQuery(cqInner).getResultList(),
                MyEntity.class, selectionsInner, ReflectionUtils.getIdFields(MyEntity.class));

        assertEquals(3, resultLeft.size());
        assertTrue(resultLeft.stream().anyMatch(m -> "entity-c-notest1@test.com".equals(m.get("email"))));

        assertEquals(2, resultInner.size());
        assertFalse(resultInner.stream().anyMatch(m -> "entity-c-notest1@test.com".equals(m.get("email"))));
    }

    @Test
    void deepJoin_threeLevels_nestedDataCorrectlyPopulated() {
        var cq = entityManager.getCriteriaBuilder().createTupleQuery();
        var root = cq.from(MyEntity.class);

        var selections = JPAProjectionProcessor.loadSelection(
                List.of("stringMail", "list.other"), root, MyEntity.class,
                ReflectionUtils.getAllProjectableFields(MyModel.class),
                ReflectionUtils.getIdFields(MyEntity.class), true, false, null);
        cq.multiselect(selections);
        var result = JPAProjectionProcessor.toMap(entityManager.createQuery(cq).getResultList(),
                MyEntity.class, selections, ReflectionUtils.getIdFields(MyEntity.class));

        assertTrue(result.size() >= 2);

        var a = result.stream().filter(m -> "entity-a@test.com".equals(m.get("email"))).findFirst()
                .orElseThrow(() -> new AssertionError("entity-a not found, ids: " + result.stream().map(m -> m.get("id")).toList()));
        assertNotNull(a.get("test1"));
        assertEquals(2, ((Collection<?>) ((Map<?, ?>) a.get("test1")).get("entity6s")).size());

        var b = result.stream().filter(m -> "entity-b@test.com".equals(m.get("email"))).findFirst()
                .orElseThrow(() -> new AssertionError("entity-b not found"));
        assertNotNull(b.get("test1"));
        assertEquals(1, ((Collection<?>) ((Map<?, ?>) b.get("test1")).get("entity6s")).size());
    }

    @Test
    void loadCompleteSelections_flatField_noUnwantedNestedIds() {
        var root = entityManager.getCriteriaBuilder().createTupleQuery().from(MyEntity.class);
        var selections = JPAProjectionProcessor.loadSelection(
                List.of("stringMail"), root, MyEntity.class,
                ReflectionUtils.getAllProjectableFields(MyModel.class),
                ReflectionUtils.getIdFields(MyEntity.class), true, false, null);

        var aliases = selections.stream().map(Selection::getAlias).toList();

        assertEquals(2, selections.size(), "Expected [email, id] only, got: " + aliases);
        assertTrue(aliases.contains("email"));
        assertTrue(aliases.contains("id"));
        assertTrue(aliases.stream().noneMatch(a -> a.contains(".")));
    }

    @Test
    void loadCompleteSelections_nestedField_addsOnlyTraversedEntityIds() {
        var root = entityManager.getCriteriaBuilder().createTupleQuery().from(MyEntity.class);
        var selections = JPAProjectionProcessor.loadSelection(
                List.of("list.other"), root, MyEntity.class,
                ReflectionUtils.getAllProjectableFields(MyModel.class),
                ReflectionUtils.getIdFields(MyEntity.class), true, false, null);

        var aliases = selections.stream().map(Selection::getAlias).toList();

        assertTrue(aliases.contains("test1.entity6s.colTest6"));
        assertTrue(aliases.contains("id"));
        assertTrue(aliases.contains("test1.id"));
        assertTrue(aliases.contains("test1.entity6s.id"));
        assertFalse(aliases.contains("test2.id"), "test2 not traversed, its id must not be added");
        assertFalse(aliases.contains("test3.id"), "test3 not traversed, its id must not be added");
    }

    @Test
    void selectionMetadata_fieldInMappedSuperclass_resolvedCorrectly() {
        assertThrows(NoSuchFieldException.class, () -> DerivedEntity.class.getDeclaredField("auditField"));

        var field = FieldUtils.getField(DerivedEntity.class, "auditField", true);
        assertNotNull(field);
        assertEquals(BaseAuditEntity.class, field.getDeclaringClass());
    }

    @Test
    void toMap_fieldInMappedSuperclass_doesNotThrow() {
        var idField = FieldUtils.getField(DerivedEntity.class, "id", true);
        assertNotNull(idField);
        var idFields = new LinkedHashMap<Class<?>, Map<String, Field>>();
        idFields.put(DerivedEntity.class, Map.of("id", idField));

        var mockTuple = org.mockito.Mockito.mock(Tuple.class);
        org.mockito.Mockito.when(mockTuple.get("id")).thenReturn(1L);
        org.mockito.Mockito.when(mockTuple.get("auditField")).thenReturn("value");
        org.mockito.Mockito.doThrow(new IllegalArgumentException())
                .when(mockTuple).get(org.mockito.Mockito.argThat((String s) -> !s.equals("id") && !s.equals("auditField")));

        var root = entityManager.getCriteriaBuilder().createTupleQuery().from(MyEntity.class);
        List<Selection<?>> selections = new ArrayList<>();
        selections.add(root.get("email").alias("auditField"));
        selections.add(root.get("id").alias("id"));

        assertDoesNotThrow(() -> JPAProjectionProcessor.toMap(List.of(mockTuple), DerivedEntity.class, selections, idFields));
    }

    @Test
    void cacheKey_cartesianProductDeduplicatedIntoOneRoot() {
        var cb = entityManager.getCriteriaBuilder();
        var cq = cb.createTupleQuery();
        var root = cq.from(MyEntity.class);
        cq.where(cb.equal(root.get("id"), 1L));

        var selections = JPAProjectionProcessor.loadSelection(
                List.of("stringMail", "list.other"), root, MyEntity.class,
                ReflectionUtils.getAllProjectableFields(MyModel.class),
                ReflectionUtils.getIdFields(MyEntity.class), true, false, null);
        cq.multiselect(selections);
        var tuples = entityManager.createQuery(cq).getResultList();

        assertEquals(2, tuples.size());

        var result = JPAProjectionProcessor.toMap(tuples, MyEntity.class, selections, ReflectionUtils.getIdFields(MyEntity.class));

        assertEquals(1, result.size());
        assertEquals(2, ((Collection<?>) ((Map<?, ?>) result.get(0).get("test1")).get("entity6s")).size());
    }

    @Test
    void cacheKey_distinctRootEntitiesNotCollapsed() {
        var cq = entityManager.getCriteriaBuilder().createTupleQuery();
        var root = cq.from(MyEntity.class);
        var selections = JPAProjectionProcessor.loadSelection(
                List.of("stringMail", "mySubModel.searchMe"), root, MyEntity.class,
                ReflectionUtils.getAllProjectableFields(MyModel.class),
                ReflectionUtils.getIdFields(MyEntity.class), true, false, null);
        cq.multiselect(selections);
        var result = JPAProjectionProcessor.toMap(entityManager.createQuery(cq).getResultList(),
                MyEntity.class, selections, ReflectionUtils.getIdFields(MyEntity.class));

        assertEquals(3, result.size());
        var emails = result.stream().map(m -> (String) m.get("email")).toList();
        assertEquals(3, new HashSet<>(emails).size());
        assertTrue(emails.containsAll(List.of("entity-a@test.com", "entity-b@test.com", "entity-c-notest1@test.com")));
    }

    @Test
    void endToEnd_fullProjection_nestedRelationsCorrect() {
        var cq = entityManager.getCriteriaBuilder().createTupleQuery();
        var root = cq.from(MyEntity.class);
        var selections = JPAProjectionProcessor.loadSelection(
                List.of("stringMail", "mySubModel.searchMe", "list.other"), root, MyEntity.class,
                ReflectionUtils.getAllProjectableFields(MyModel.class),
                ReflectionUtils.getIdFields(MyEntity.class), true, false, null);
        cq.multiselect(selections);
        var result = JPAProjectionProcessor.toMap(entityManager.createQuery(cq).getResultList(),
                MyEntity.class, selections, ReflectionUtils.getIdFields(MyEntity.class));

        assertTrue(result.size() >= 2);

        var a = result.stream().filter(m -> "entity-a@test.com".equals(m.get("email"))).findFirst()
                .orElseThrow(() -> new AssertionError("entity-a not found"));
        assertEquals("col2_A", ((Map<?, ?>) a.get("test2")).get("colTest2"));
        var entity6sA = (Collection<?>) ((Map<?, ?>) a.get("test1")).get("entity6s");
        assertEquals(2, entity6sA.size());
        assertTrue(entity6sA.stream().map(o -> ((Map<?, ?>) o).get("colTest6")).toList().containsAll(List.of("col6_A", "col6_B")));

        var b = result.stream().filter(m -> "entity-b@test.com".equals(m.get("email"))).findFirst()
                .orElseThrow(() -> new AssertionError("entity-b not found"));
        assertEquals("col2_B", ((Map<?, ?>) b.get("test2")).get("colTest2"));
    }

    @Test
    void loadSelection_unknownField_throwsInvalidFieldException() {
        var root = entityManager.getCriteriaBuilder().createTupleQuery().from(MyEntity.class);
        assertThrows(InvalidFieldException.class, () ->
                JPAProjectionProcessor.loadSelection(List.of("nonExistentField"), root, MyEntity.class,
                        ReflectionUtils.getAllProjectableFields(MyModel.class),
                        ReflectionUtils.getIdFields(MyEntity.class), true, false, null));
    }

    @Test
    void toMap_emptyTupleList_returnsEmptyList() {
        assertTrue(JPAProjectionProcessor.toMap(Collections.emptyList(), MyEntity.class,
                Collections.emptyList(), ReflectionUtils.getIdFields(MyEntity.class)).isEmpty());
    }

    @Test
    void loadSelection_nullOrEmptyFields_throwsJPASearchException() {
        var root = entityManager.getCriteriaBuilder().createTupleQuery().from(MyEntity.class);
        assertThrows(JPASearchException.class, () ->
                JPAProjectionProcessor.loadSelection(null, root, MyEntity.class,
                        ReflectionUtils.getAllProjectableFields(MyModel.class),
                        ReflectionUtils.getIdFields(MyEntity.class), true, false, null));
        assertThrows(JPASearchException.class, () ->
                JPAProjectionProcessor.loadSelection(Collections.emptyList(), root, MyEntity.class,
                        ReflectionUtils.getAllProjectableFields(MyModel.class),
                        ReflectionUtils.getIdFields(MyEntity.class), true, false, null));
    }
}

