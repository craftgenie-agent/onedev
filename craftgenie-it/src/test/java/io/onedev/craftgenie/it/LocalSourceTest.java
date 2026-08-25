package io.onedev.craftgenie.it;

import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * These tests run against the working tree's server-core, not a published one.
 *
 * <p>The trap this guards is quiet. {@code server-core} is a test-scoped sibling, but
 * selecting only this module still resolves it: OneDev publishes it to its own Maven
 * repository, which the parent pom declares. So the build goes green having exercised
 * <em>upstream's</em> jar, and a local change to an entity, the dialect or the naming
 * strategy is not covered by the very tests written to cover it. Nothing fails; the
 * coverage is simply imaginary.
 *
 * <p>{@code ./cg-dev.sh test} builds server-core into the reactor first, which is what
 * makes this pass. A failure here means the module under test is a downloaded artifact -
 * run through the script, or build server-core yourself.
 */
public class LocalSourceTest {

	/**
	 * Any type this fork adds and upstream does not have would do. LinkSpecResource is
	 * chosen because it is a whole class rather than a changed line, so its absence is
	 * unambiguous.
	 */
	private static final String FORK_ONLY_TYPE = "io.onedev.server.rest.resource.LinkSpecResource";

	@Test
	public void serverCoreOnTheClasspathIsTheOneInThisWorkingTree() {
		try {
			Class.forName(FORK_ONLY_TYPE);
		} catch (ClassNotFoundException e) {
			fail("server-core on the test classpath is a published artifact, not this working"
					+ " tree: " + FORK_ONLY_TYPE + " is missing. These tests would have reported"
					+ " green while covering upstream's code. Run ./cg-dev.sh test, or"
					+ " mvn -Pce -pl craftgenie-it -am -DskipTests install first."
					+ " (If this fork no longer adds that type, point FORK_ONLY_TYPE at one it"
					+ " does - do not delete the check.)");
		}
	}
}
