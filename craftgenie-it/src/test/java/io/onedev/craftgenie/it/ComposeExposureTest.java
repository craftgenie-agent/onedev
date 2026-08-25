package io.onedev.craftgenie.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Every port the development stack publishes stays on the loopback interface.
 *
 * <p>pgAdmin runs in desktop mode - no login page, no master password - which is a
 * reasonable trade for a database on a developer's own machine and an open administrative
 * console for anyone else on the network. Compose publishes to {@code 0.0.0.0} when no
 * host address is given, so the difference between those two situations is one prefix
 * that is easy to leave off.
 *
 * <p>Worth knowing why a host firewall is not the backstop here: Docker publishes a port
 * by writing its own iptables rules into the DOCKER chain, which is consulted before the
 * INPUT chain most firewall tooling manages. A published port is commonly reachable on a
 * machine whose firewall looks closed.
 *
 * <p>No Docker needed - this reads the file.
 */
public class ComposeExposureTest {

	private static final Path COMPOSE = Path.of("..", "craftgenie-dev", "docker-compose.yml");

	@Test
	public void everyPublishedPortIsBoundToLoopback() {
		List<String> published = publishedPorts();

		// If the file moves, this test must fail rather than quietly assert nothing.
		assertTrue("Found no published ports in " + COMPOSE.toAbsolutePath()
				+ " - has the file moved?", published.size() >= 2);

		List<String> exposed = new ArrayList<>();
		for (String port : published) {
			if (!port.startsWith("127.0.0.1:")) {
				exposed.add(port);
			}
		}
		assertEquals("Published ports must bind to 127.0.0.1; Compose binds to 0.0.0.0 without it,"
				+ " which puts an unauthenticated pgAdmin on every interface",
				List.of(), exposed);
	}

	/**
	 * The port entries of every service, as written.
	 *
	 * <p>Read as text rather than parsed as YAML on purpose: the point is to check what a
	 * reviewer sees in the file, and a parser would need the variable substitution the
	 * entries are written with.
	 */
	private static List<String> publishedPorts() {
		List<String> ports = new ArrayList<>();
		boolean inPorts = false;
		for (String line : lines()) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			if (trimmed.equals("ports:")) {
				inPorts = true;
				continue;
			}
			if (inPorts) {
				if (trimmed.startsWith("- ")) {
					ports.add(trimmed.substring(2).trim().replace("\"", "").replace("'", ""));
				} else {
					// Any other key ends the block; nested keys under a port entry are the
					// long syntax, which this file does not use.
					inPorts = false;
				}
			}
		}
		return ports;
	}

	private static List<String> lines() {
		try {
			return Files.readAllLines(COMPOSE, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + COMPOSE.toAbsolutePath(), e);
		}
	}
}
