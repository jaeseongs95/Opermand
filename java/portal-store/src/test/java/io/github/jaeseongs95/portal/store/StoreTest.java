package io.github.jaeseongs95.portal.store;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestConstructor;

/**
 * The storage test slice (Principles 7.2, 7.3): {@code @DataJdbcTest} on an embedded H2 database without a compatibility mode, with the real migration of the product applied
 * ({@code db/expand}, by the runner of {@code ...store.migration}: {@link StoreMigration}), and constructor injection into the test class (no field {@code @Autowired}). The slice
 * rolls each test back; a test that needs commits from several threads declares {@code @Transactional(propagation = Propagation.NOT_SUPPORTED)} and cleans its rows itself.
 * The product does not test other DBMSs (Principles 7.3: the driver is the user's part), so this slice runs on H2 only.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@DataJdbcTest
@Import({IdentifierQuoting.class, Savepoints.class, StoreMigration.class})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
public @interface StoreTest {
}
