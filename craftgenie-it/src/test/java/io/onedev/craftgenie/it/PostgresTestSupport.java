package io.onedev.craftgenie.it;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.hibernate.Interceptor;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.service.ServiceRegistry;
import org.junit.AssumptionViolatedException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.onedev.commons.utils.ClassUtils;
import io.onedev.server.model.AbstractEntity;
import io.onedev.server.persistence.HibernateConfig;
import io.onedev.server.persistence.PrefixedNamingStrategy;

/**
 * A real PostgreSQL, and a Hibernate {@link SessionFactory} built from OneDev's own
 * entity model against it.
 *
 * <p>The point is to exercise the production persistence path rather than a
 * reconstruction of it: the entity scan, the {@code o_} naming strategy and the custom
 * {@code PostgreSQLDialect} are all OneDev's, loaded from {@code server-core}. Only the
 * connection details differ, and they come from the container.
 *
 * <p>The container is started once per JVM and shared. Each test gets its own database
 * inside it via {@link #freshDatabase}, so schema-creating tests cannot see each other's
 * tables while still paying the container start cost only once.
 */
public final class PostgresTestSupport {

	/**
	 * Match the server version {@code craftgenie-dev/docker-compose.yml} runs. Testing
	 * against a different major than developers use locally would defeat the purpose.
	 */
	private static final DockerImageName IMAGE = DockerImageName.parse("postgres:17-alpine");

	/** OneDev prefixes every table and column with this; see CoreModule. */
	public static final String TABLE_PREFIX = "o_";

	private static volatile PostgreSQLContainer<?> container;

	private PostgresTestSupport() {
	}

	/**
	 * Skip rather than fail when Docker is missing.
	 *
	 * <p>A developer without Docker should get a green build with tests reported as
	 * skipped, not a red one they cannot act on. CI, which does have Docker, still runs
	 * them - so this cannot silently hide a regression there.
	 */
	public static void assumeDockerAvailable() {
		if (!DockerClientFactory.instance().isDockerAvailable()) {
			throw new AssumptionViolatedException(
					"Docker is not available - skipping PostgreSQL integration tests");
		}
	}

	/** The shared container, started on first use. */
	public static PostgreSQLContainer<?> container() {
		if (container == null) {
			synchronized (PostgresTestSupport.class) {
				if (container == null) {
					PostgreSQLContainer<?> started = new PostgreSQLContainer<>(IMAGE)
							.withDatabaseName("onedev_it")
							.withUsername("onedev")
							// Pin the collation craftgenie-dev/docker-compose.yml pins, so ordering
							// here is byte-wise for the same reason it is in development.
							//
							// Without this the image initialises at en_US.utf8 and
							// DialectDivergenceTest still passes - but only by accident of musl,
							// whose collation is byte-wise whatever the locale says. The assertion
							// would break the moment the image moved off Alpine, and the comment
							// explaining it would have been wrong the whole time.
							.withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8 --locale=C")
							.withPassword("onedev");
					started.start();
					// Testcontainers' Ryuk sidecar reaps the container when the JVM exits, but
					// it is disabled in some CI setups; this covers that case too.
					Runtime.getRuntime().addShutdownHook(new Thread(started::stop));
					container = started;
				}
			}
		}
		return container;
	}

	/**
	 * Create an empty database in the shared container and return its JDBC URL.
	 *
	 * @param name distinguishes databases across tests; keep it short and lowercase,
	 *             since an unquoted PostgreSQL identifier is folded to lower case
	 */
	public static String freshDatabase(String name) {
		PostgreSQLContainer<?> pg = container();
		try {
			// Executed through psql rather than JDBC because CREATE DATABASE cannot run
			// inside the transaction a JDBC connection would wrap it in.
			//
			// Two -c flags, not one with both statements: psql runs a single -c as one
			// transaction, which fails with "DROP DATABASE cannot run inside a transaction
			// block". Separate flags are separate transactions.
			var result = pg.execInContainer("psql", "-U", pg.getUsername(), "-d", pg.getDatabaseName(),
					"-v", "ON_ERROR_STOP=1",
					"-c", "DROP DATABASE IF EXISTS " + name,
					"-c", "CREATE DATABASE " + name);
			if (result.getExitCode() != 0) {
				throw new IllegalStateException("Could not create database " + name + ": "
						+ result.getStderr());
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
		return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/" + name;
	}

	/**
	 * Write a {@code conf/hibernate.properties} and load it through OneDev's own
	 * {@link HibernateConfig}.
	 *
	 * <p>Going through the file rather than building a {@link Properties} directly is
	 * deliberate: {@code HibernateConfig} is what production uses, and its substitution
	 * and environment-override behaviour is then covered too.
	 */
	public static HibernateConfig hibernateConfig(Path installDir, String jdbcUrl) {
		Path conf = installDir.resolve("conf");
		try {
			Files.createDirectories(conf);
			Files.writeString(conf.resolve("hibernate.properties"), String.join("\n",
					"hibernate.dialect=io.onedev.server.persistence.PostgreSQLDialect",
					"hibernate.connection.driver_class=org.postgresql.Driver",
					"hibernate.connection.url=" + jdbcUrl,
					"hibernate.connection.username=" + container().getUsername(),
					"hibernate.connection.password=" + container().getPassword(),
					"hibernate.connection.provider_class="
							+ "org.hibernate.hikaricp.internal.HikariCPConnectionProvider",
					"hibernate.hikari.transactionIsolation=TRANSACTION_READ_COMMITTED",
					"hibernate.hikari.autoCommit=true",
					"hibernate.hikari.maximumPoolSize=4",
					"hibernate.show_sql=false",
					"javax.persistence.validation.mode=none",
					"hibernate.validator.apply_to_ddl=false",
					"hibernate.hbm2ddl.auto=create",
					""), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return new HibernateConfig(installDir.toFile());
	}

	/**
	 * Build a {@link SessionFactory} over every OneDev entity, the way
	 * {@code DefaultSessionFactoryService} does.
	 *
	 * <p>Second-level caching is off. In production it is backed by Hazelcast, which
	 * would mean starting a cluster member per test for no coverage gain -
	 * {@code DefaultSessionFactoryService} takes the same branch when Hazelcast is absent.
	 */
	public static SessionFactory buildSessionFactory(HibernateConfig config, Interceptor interceptor) {
		Properties settings = new Properties();
		settings.putAll(config);
		settings.put("hibernate.cache.use_second_level_cache", "false");
		settings.put("hibernate.cache.use_query_cache", "false");

		ServiceRegistry serviceRegistry = new StandardServiceRegistryBuilder()
				.applySettings(settings).build();
		MetadataSources sources = new MetadataSources(serviceRegistry);
		for (Class<? extends AbstractEntity> each
				: ClassUtils.findImplementations(AbstractEntity.class, AbstractEntity.class)) {
			sources.addAnnotatedClass(each);
		}
		Metadata metadata = sources.getMetadataBuilder()
				.applyPhysicalNamingStrategy(new PrefixedNamingStrategy(TABLE_PREFIX))
				.build();
		return interceptor != null
				? metadata.getSessionFactoryBuilder().applyInterceptor(interceptor).build()
				: metadata.getSessionFactoryBuilder().build();
	}

	/** A temporary install directory, deleted when the JVM exits. */
	public static Path tempInstallDir(String prefix) {
		try {
			Path dir = Files.createTempDirectory(prefix);
			Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursively(dir.toFile())));
			return dir;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void deleteRecursively(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteRecursively(child);
			}
		}
		// Best effort on shutdown: a file we cannot remove is a temp-directory leak, not a
		// test failure, and throwing from a shutdown hook would mask the real result.
		file.delete();
	}
}
