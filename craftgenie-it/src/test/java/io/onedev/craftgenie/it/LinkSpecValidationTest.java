package io.onedev.craftgenie.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import javax.validation.Validation;
import javax.validation.Validator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.server.model.LinkSpec;
import io.onedev.server.model.support.issue.LinkSpecOpposite;
import io.onedev.server.rest.resource.LinkSpecResource;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.service.AuditService;
import io.onedev.server.service.LinkSpecService;

/**
 * What a link spec submitted over REST is checked against before it is stored.
 *
 * <p>The opposite side is the gap worth a test of its own. {@code LinkSpec.getOpposite()} carries
 * no {@code @Valid}, so cascaded validation stops at the spec and never reaches the opposite's own
 * {@code @NotEmpty} name. Nothing downstream looks either - the opposite is serialized whole into
 * a {@code @Lob} - so an empty name on the other side was simply stored, and only surfaced later
 * wherever something tried to read it back.
 *
 * <p>A real validator rather than a mocked one: the point is whether the constraints actually run.
 */
public class LinkSpecValidationTest {

	private LinkSpecService linkSpecService;
	private LinkSpecResource resource;
	private MockedStatic<SecurityUtils> security;

	@Before
	public void setUp() {
		linkSpecService = mock(LinkSpecService.class);
		Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
		resource = new LinkSpecResource(linkSpecService, mock(AuditService.class), validator);
		security = mockStatic(SecurityUtils.class);
		security.when(SecurityUtils::isAdministrator).thenReturn(true);
	}

	@After
	public void tearDown() {
		security.close();
	}

	@Test
	public void createRefusesAnEmptyNameOnTheOtherSide() {
		ExplicitException refused = assertThrows(ExplicitException.class,
				() -> resource.createSpec(spec("Parent", "")));

		assertTrue(refused.getMessage(),
				refused.getMessage().startsWith("opposite.name: "));
		verify(linkSpecService, never()).create(any());
	}

	@Test
	public void updateRefusesAnEmptyNameOnTheOtherSide() {
		ExplicitException refused = assertThrows(ExplicitException.class,
				() -> resource.updateSpec(3L, spec("Parent", "")));

		assertTrue(refused.getMessage(),
				refused.getMessage().startsWith("opposite.name: "));
		verify(linkSpecService, never()).update(any(), anyString(), anyString());
		// Refused before the spec is even loaded, so nothing was read on the way to failing.
		verify(linkSpecService, never()).load(any(Long.class));
	}

	/**
	 * One name cannot mean both directions.
	 *
	 * <p>{@code LinkDescriptor} decides which way round a link is with
	 * {@code !linkName.equals(spec.getName())}, so a spec naming both sides the same always
	 * resolves to the primary side and the opposite side cannot be addressed by name at all. Half
	 * the link exists and is unreachable.
	 *
	 * <p>The uniqueness check is structurally unable to catch this - it excludes the spec being
	 * updated, so it cannot see one colliding with itself.
	 */
	@Test
	public void createRefusesTheSameNameOnBothSides() {
		ExplicitException refused = assertThrows(ExplicitException.class,
				() -> resource.createSpec(spec("Relates to", "Relates to")));

		assertEquals("Name and name on the other side should be different", refused.getMessage());
		verify(linkSpecService, never()).create(any());
	}

	@Test
	public void updateRefusesTheSameNameOnBothSides() {
		ExplicitException refused = assertThrows(ExplicitException.class,
				() -> resource.updateSpec(3L, spec("Relates to", "Relates to")));

		assertEquals("Name and name on the other side should be different", refused.getMessage());
		verify(linkSpecService, never()).update(any(), anyString(), anyString());
	}

	/** The spec's own constraints still apply; the opposite is an addition, not a replacement. */
	@Test
	public void createRefusesAnEmptyNameOnThisSide() {
		ExplicitException refused = assertThrows(ExplicitException.class,
				() -> resource.createSpec(spec("", "Child")));

		assertTrue(refused.getMessage(), refused.getMessage().startsWith("name: "));
		verify(linkSpecService, never()).create(any());
	}

	private static LinkSpec spec(String name, String oppositeName) {
		LinkSpec spec = new LinkSpec();
		spec.setName(name);
		if (oppositeName != null) {
			LinkSpecOpposite opposite = new LinkSpecOpposite();
			opposite.setName(oppositeName);
			spec.setOpposite(opposite);
		}
		return spec;
	}
}
