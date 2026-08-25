package io.onedev.craftgenie.it;

import static io.onedev.craftgenie.it.PostgresTestSupport.TABLE_PREFIX;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.SessionFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * OneDev's real entity model, mapped onto a real PostgreSQL.
 *
 * <p>Nothing here asserts against a hand-written schema. The schema is whatever Hibernate
 * generates from {@code server-core}'s entities through OneDev's own dialect and naming
 * strategy, which is exactly the thing that has never been exercised outside production.
 */
public class SchemaGenerationTest {

	private static final String DATABASE = "schemagen";

	private static SessionFactory sessionFactory;
	private static String jdbcUrl;

	@BeforeClass
	public static void createSchema() {
		PostgresTestSupport.assumeDockerAvailable();
		jdbcUrl = PostgresTestSupport.freshDatabase(DATABASE);
		Path installDir = PostgresTestSupport.tempInstallDir("craftgenie-schemagen");
		// hbm2ddl.auto=create, so building the factory is what creates the schema.
		// A mapping error surfaces here rather than in an assertion below.
		sessionFactory = PostgresTestSupport.buildSessionFactory(
				PostgresTestSupport.hibernateConfig(installDir, jdbcUrl), null);
	}

	@AfterClass
	public static void closeSessionFactory() {
		if (sessionFactory != null) {
			sessionFactory.close();
			sessionFactory = null;
		}
	}

	@Test
	public void schemaBuildsFromOneDevEntities() {
		assertNotNull("SessionFactory should have been built", sessionFactory);
		assertTrue("SessionFactory should be open", sessionFactory.isOpen());
	}

	@Test
	public void everyEntityGetsATable() throws SQLException {
		List<String> tables = query(
				"SELECT table_name FROM information_schema.tables "
						+ "WHERE table_schema = 'public' ORDER BY table_name");

		// OneDev 16.5.6 maps just over 100 entities. A floor rather than an exact count:
		// upstream adds entities regularly and this test should not fail for that.
		assertTrue("Expected a substantial schema but found " + tables.size() + " tables",
				tables.size() > 90);

		assertTrue("Expected the user table to exist, found: " + tables,
				tables.contains(TABLE_PREFIX + "user"));
		assertTrue(tables.contains(TABLE_PREFIX + "project"));
		assertTrue(tables.contains(TABLE_PREFIX + "issue"));
		assertTrue(tables.contains(TABLE_PREFIX + "pullrequest"));
	}

	@Test
	public void namingStrategyPrefixesEveryTable() throws SQLException {
		List<String> unprefixed = query(
				"SELECT table_name FROM information_schema.tables "
						+ "WHERE table_schema = 'public' AND table_name NOT LIKE '" + TABLE_PREFIX + "%'");
		assertEquals("Every table should carry the '" + TABLE_PREFIX + "' prefix",
				List.of(), unprefixed);
	}

	/**
	 * The reason OneDev ships its own dialect.
	 *
	 * <p>{@code PostgreSQLDialect} remaps {@code BLOB} to {@code bytea} because
	 * PostgreSQL large objects live in a side table that is not cleaned up when the owning
	 * row is deleted, and cannot be read in auto-commit mode. If this mapping regresses,
	 * OneDev leaks storage and breaks lazy loading - and stock Hibernate would silently
	 * give us the broken behaviour, since it maps BLOB to oid by default.
	 */
	@Test
	public void blobColumnsAreMappedToBytea() throws SQLException {
		List<String> oidColumns = query(
				"SELECT table_name || '.' || column_name FROM information_schema.columns "
						+ "WHERE table_schema = 'public' AND udt_name = 'oid'");
		assertEquals("BLOB columns must map to bytea, not oid - the custom dialect is not in effect",
				List.of(), oidColumns);

		List<String> byteaColumns = query(
				"SELECT table_name || '.' || column_name FROM information_schema.columns "
						+ "WHERE table_schema = 'public' AND udt_name = 'bytea'");
		assertTrue("Expected bytea columns from the BLOB remap, found none", byteaColumns.size() > 5);
	}

	private static List<String> query(String sql) throws SQLException {
		List<String> values = new ArrayList<>();
		try (Connection connection = DriverManager.getConnection(jdbcUrl,
						PostgresTestSupport.container().getUsername(),
						PostgresTestSupport.container().getPassword());
				Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery(sql)) {
			while (rs.next()) {
				values.add(rs.getString(1));
			}
		}
		return values;
	}
}
