package io.onedev.craftgenie.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.ws.rs.NotAcceptableException;

import com.google.inject.Injector;
import com.thoughtworks.xstream.XStream;

import io.onedev.commons.loader.AppLoader;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import io.onedev.server.model.LinkSpec;
import io.onedev.server.model.support.issue.LinkSpecOpposite;
import io.onedev.server.rest.resource.LinkSpecResource;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.service.AuditService;
import io.onedev.server.service.LinkSpecService;

/**
 * A link spec update cannot take a name another spec is already using.
 *
 * <p>Creation checks this and the web UI checks this; update did not, and update is the
 * one that gets away with it. The primary name carries a unique database column, so a
 * collision there is at least refused by the database. An opposite name does not - it
 * lives inside a {@code @Lob} - so nothing below this method would object.
 *
 * <p>What makes it more than untidy: {@code DefaultLinkSpecService.updateCache} puts both
 * sides of a spec into one name-to-id map, and {@code find} resolves either side through
 * it. Two specs claiming one name means the second write wins the lookup, and saved
 * queries and links naming it silently start resolving to the wrong spec.
 *
 * <p>No database and no Docker: the collision is decided before anything is persisted,
 * which is the whole point of checking it here.
 */
public class LinkSpecUniquenessTest {

	private LinkSpecService linkSpecService;
	private LinkSpecResource resource;
	private MockedStatic<SecurityUtils> security;

	@Before
	public void setUp() {
		linkSpecService = mock(LinkSpecService.class);
		resource = new LinkSpecResource(linkSpecService, mock(AuditService.class));
		security = mockStatic(SecurityUtils.class);
		security.when(SecurityUtils::isAdministrator).thenReturn(true);

		// The audit trail marshals the spec through an XStream taken from the application
		// injector, which no test boots. Only the accepted path reaches it - a refusal is
		// decided before anything is read - but that path is worth covering too.
		Injector injector = mock(Injector.class);
		when(injector.getInstance(XStream.class)).thenReturn(new XStream());
		AppLoader.injector = injector;
	}

	@After
	public void tearDown() {
		security.close();
		AppLoader.injector = null;
	}

	@Test
	public void updateRefusesANameAnotherSpecHolds() {
		LinkSpec other = spec(7L, "Blocks", null);
		when(linkSpecService.find("Blocks")).thenReturn(other);
		when(linkSpecService.load(3L)).thenReturn(spec(3L, "Relates", null));

		NotAcceptableException refused = assertThrows(NotAcceptableException.class,
				() -> resource.updateSpec(3L, spec(null, "Blocks", null)));

		assertEquals("Link spec name is already used: Blocks", refused.getMessage());
		verify(linkSpecService, never()).update(any(), anyString(), anyString());
	}

	/**
	 * The case with nothing underneath it. An opposite name has no unique column, so if
	 * this method admits the collision, every layer below admits it too.
	 */
	@Test
	public void updateRefusesAnOppositeNameAnotherSpecHolds() {
		LinkSpec other = spec(7L, "Backend counterpart", "UI counterpart");
		// find() matches either end, which is exactly how the collision arrives.
		when(linkSpecService.find("UI counterpart")).thenReturn(other);
		when(linkSpecService.load(3L)).thenReturn(spec(3L, "Parent", "Child"));

		NotAcceptableException refused = assertThrows(NotAcceptableException.class,
				() -> resource.updateSpec(3L, spec(null, "Parent", "UI counterpart")));

		assertEquals("Link spec name is already used: UI counterpart", refused.getMessage());
		verify(linkSpecService, never()).update(any(), anyString(), anyString());
	}

	/**
	 * The check has to exclude the spec being updated, or no spec could ever keep its own
	 * name while changing anything else - which is the ordinary edit.
	 */
	@Test
	public void updateLetsASpecKeepItsOwnNames() {
		LinkSpec existing = spec(3L, "Parent", "Child");
		when(linkSpecService.load(3L)).thenReturn(existing);
		when(linkSpecService.find("Parent")).thenReturn(existing);
		when(linkSpecService.find("Child")).thenReturn(existing);

		LinkSpec submitted = spec(null, "Parent", "Child");
		submitted.setIssueQuery("\"State\" is \"Open\"");
		resource.updateSpec(3L, submitted);

		verify(linkSpecService).update(existing, "Parent", "Child");
		assertEquals("\"State\" is \"Open\"", existing.getIssueQuery());
	}

	private static LinkSpec spec(Long id, String name, String oppositeName) {
		LinkSpec spec = new LinkSpec();
		spec.setId(id);
		spec.setName(name);
		if (oppositeName != null) {
			LinkSpecOpposite opposite = new LinkSpecOpposite();
			opposite.setName(oppositeName);
			spec.setOpposite(opposite);
		}
		return spec;
	}
}
