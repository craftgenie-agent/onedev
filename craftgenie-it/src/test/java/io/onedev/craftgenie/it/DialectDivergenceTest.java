package io.onedev.craftgenie.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Why developing on HSQLDB and shipping on PostgreSQL is a real risk, demonstrated rather
 * than asserted in a comment.
 *
 * <p>OneDev's bundled HSQLDB is configured with {@code sql.ignore_case=true}
 * ({@code server-product/system/conf/hibernate.properties}), which makes {@code VARCHAR}
 * comparison case-insensitive. PostgreSQL is case-sensitive. Any query, unique constraint
 * or lookup that compares strings can therefore behave differently in development and in
 * production, and the development result is the more permissive one - so the bug shows up
 * only after deploy.
 *
 * <p>These tests pin that difference. If a future HSQLDB or PostgreSQL change removes it,
 * they fail and someone gets to decide what that means, rather than the assumption
 * quietly rotting. They are documentation with a build failure attached.
 */
public class DialectDivergenceTest {

	private static final String DATABASE = "divergence";

	/** Exactly the URL OneDev's shipped configuration uses, {@code sql.ignore_case=true} included. */
	private static final String HSQLDB_URL =
			"jdbc:hsqldb:mem:craftgenie-divergence;sql.ignore_case=true;hsqldb.tx=mvcc";

	private static String postgresUrl;

	@BeforeClass
	public static void prepare() {
		PostgresTestSupport.assumeDockerAvailable();
		postgresUrl = PostgresTestSupport.freshDatabase(DATABASE);
	}

	/**
	 * The headline difference: the same equality predicate matches on HSQLDB and does not
	 * on PostgreSQL.
	 */
	@Test
	public void stringEqualityIsCaseInsensitiveOnHsqldbButNotPostgres() throws SQLException {
		String create = "CREATE TABLE o_probe (o_id INT PRIMARY KEY, o_name VARCHAR(255))";
		String insert = "INSERT INTO o_probe (o_id, o_name) VALUES (1, 'Alice')";
		String probe = "SELECT count(*) FROM o_probe WHERE o_name = 'alice'";

		assertEquals("HSQLDB with sql.ignore_case=true should match 'alice' against 'Alice'",
				1, countOn(hsqldb(), create, insert, probe));

		assertEquals("PostgreSQL is case-sensitive and must NOT match 'alice' against 'Alice'",
				0, countOn(postgres(), create, insert, probe));
	}

	/**
	 * The same divergence on ordering.
	 *
	 * <p>Under the C collation {@link PostgresTestSupport} pins - the one the compose file
	 * pins for development - PostgreSQL orders by byte value, so every upper-case letter
	 * sorts before every lower-case one: {@code 'B'} is 0x42 and {@code 'a'} is 0x61.
	 * HSQLDB in ignore-case mode folds them together and orders alphabetically regardless
	 * of case.
	 *
	 * <p>The data matters: {@code apple}/{@code Banana} separates the two rules, whereas
	 * {@code Apple}/{@code banana} would sort identically under both and prove nothing.
	 * A paged, ordered query tested only on HSQLDB returns a different page in production.
	 */
	@Test
	public void orderingDiffersBetweenEngines() throws SQLException {
		String create = "CREATE TABLE o_probe (o_id INT PRIMARY KEY, o_name VARCHAR(255))";
		String insert = "INSERT INTO o_probe (o_id, o_name) VALUES (1, 'apple'), (2, 'Banana')";
		String probe = "SELECT o_name FROM o_probe ORDER BY o_name";

		String hsqldbOrder = String.join(",", stringsOn(hsqldb(), create, insert, probe));
		String postgresOrder = String.join(",", stringsOn(postgres(), create, insert, probe));

		assertEquals("HSQLDB ignores case, so 'apple' sorts before 'Banana'",
				"apple,Banana", hsqldbOrder);
		assertEquals("PostgreSQL under C collation sorts by byte value, so 'Banana' comes first",
				"Banana,apple", postgresOrder);
	}

	/**
	 * The collation is pinned, not inherited.
	 *
	 * <p>{@link #orderingDiffersBetweenEngines} rests on byte-wise ordering. An Alpine
	 * image gives that whatever the locale, because musl collates by byte - so without
	 * this the ordering assertion would keep passing on a database initialised at
	 * en_US.utf8 and would fail, confusingly, only on a move to a glibc image.
	 *
	 * <p>Asserting the collation directly turns that into a clear failure at the point
	 * the assumption breaks.
	 */
	@Test
	public void theTestDatabaseIsInitialisedWithTheCollationDevelopmentUses() throws SQLException {
		try (Connection c = postgres(); Statement s = c.createStatement();
				ResultSet rs = s.executeQuery(
						"SELECT datcollate FROM pg_database WHERE datname = current_database()")) {
			rs.next();
			assertEquals("The container must initdb with --locale=C, as the compose file does",
					"C", rs.getString(1));
		}
	}

	/**
	 * A unique index admits values on PostgreSQL that HSQLDB rejects.
	 *
	 * <p>This is the shape that bites hardest: a uniqueness rule that appears enforced in
	 * development silently permits near-duplicates in production - two users differing
	 * only by capitalisation, for instance.
	 */
	@Test
	public void uniqueConstraintsAdmitCaseVariantsOnPostgresOnly() throws SQLException {
		String create = "CREATE TABLE o_probe (o_id INT PRIMARY KEY, o_name VARCHAR(255) UNIQUE)";
		String first = "INSERT INTO o_probe (o_id, o_name) VALUES (1, 'Alice')";
		String variant = "INSERT INTO o_probe (o_id, o_name) VALUES (2, 'alice')";

		assertTrue("HSQLDB should reject 'alice' as a duplicate of 'Alice'",
				insertFails(hsqldb(), create, first, variant));

		assertTrue("PostgreSQL should accept 'alice' alongside 'Alice'",
				!insertFails(postgres(), create, first, variant));
	}

	// ---------------------------------------------------------------- connections

	private static Connection hsqldb() throws SQLException {
		return DriverManager.getConnection(HSQLDB_URL, "sa", "");
	}

	private static Connection postgres() throws SQLException {
		return DriverManager.getConnection(postgresUrl,
				PostgresTestSupport.container().getUsername(),
				PostgresTestSupport.container().getPassword());
	}

	// ---------------------------------------------------------------- helpers

	private static int countOn(Connection connection, String create, String insert, String probe)
			throws SQLException {
		try (Connection c = connection; Statement s = c.createStatement()) {
			s.execute("DROP TABLE IF EXISTS o_probe");
			s.execute(create);
			s.execute(insert);
			try (ResultSet rs = s.executeQuery(probe)) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private static java.util.List<String> stringsOn(Connection connection, String create,
			String insert, String probe) throws SQLException {
		var values = new java.util.ArrayList<String>();
		try (Connection c = connection; Statement s = c.createStatement()) {
			s.execute("DROP TABLE IF EXISTS o_probe");
			s.execute(create);
			s.execute(insert);
			try (ResultSet rs = s.executeQuery(probe)) {
				while (rs.next()) {
					values.add(rs.getString(1));
				}
			}
		}
		return values;
	}

	private static boolean insertFails(Connection connection, String create, String first,
			String second) throws SQLException {
		try (Connection c = connection; Statement s = c.createStatement()) {
			s.execute("DROP TABLE IF EXISTS o_probe");
			s.execute(create);
			s.execute(first);
			try {
				s.execute(second);
				return false;
			} catch (SQLException expected) {
				return true;
			}
		}
	}
}
