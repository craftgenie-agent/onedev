package io.onedev.server.rest.resource;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.shiro.authz.UnauthenticatedException;
import org.apache.shiro.authz.UnauthorizedException;

import io.onedev.server.data.migration.VersionedXmlDoc;
import io.onedev.server.model.LinkSpec;
import io.onedev.server.rest.annotation.Api;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.service.AuditService;
import io.onedev.server.service.LinkSpecService;

/**
 * Issue link specs over REST.
 *
 * <p>Link specs could previously only be managed through the web UI, which left issue
 * links as the one part of the issue workflow that could not be provisioned
 * programmatically alongside fields, states and boards.
 *
 * <p>The asymmetric case is the interesting one: a spec with an opposite gives the two
 * ends different names - "Backend counterpart" one way, "UI counterpart" the other - which
 * is what makes cross-repository links readable from either side.
 */
@Path("/link-specs")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Singleton
public class LinkSpecResource {

	private final LinkSpecService linkSpecService;

	private final AuditService auditService;

	@Inject
	public LinkSpecResource(LinkSpecService linkSpecService, AuditService auditService) {
		this.linkSpecService = linkSpecService;
		this.auditService = auditService;
	}

	@Api(order=100)
	@Path("/{linkSpecId}")
	@GET
	public LinkSpec getSpec(@PathParam("linkSpecId") Long linkSpecId) {
		if (SecurityUtils.getAuthUser() == null)
			throw new UnauthenticatedException();

		return linkSpecService.load(linkSpecId);
	}

	@Api(order=400, description="Query link specs, ordered as they appear in the UI. "
			+ "Supply <code>name</code> to look one up by either side of an asymmetric link")
	@GET
	public List<LinkSpec> querySpecs(@QueryParam("name") String name) {
		if (SecurityUtils.getAuthUser() == null)
			throw new UnauthenticatedException();

		if (name != null) {
			// find() matches either end of an asymmetric spec, which is what a caller
			// holding only one of the two names needs.
			LinkSpec spec = linkSpecService.find(name);
			return spec != null ? List.of(spec) : List.of();
		}
		return linkSpecService.queryAndSort();
	}

	@Api(order=500, description="Create new issue link spec")
	@POST
	public Long createSpec(@NotNull LinkSpec linkSpec) {
		if (!SecurityUtils.isAdministrator())
			throw new UnauthorizedException();

		if (linkSpecService.find(linkSpec.getName()) != null)
			throw new javax.ws.rs.NotAcceptableException(
					"Link spec name is already used: " + linkSpec.getName());
		if (linkSpec.getOpposite() != null
				&& linkSpecService.find(linkSpec.getOpposite().getName()) != null) {
			throw new javax.ws.rs.NotAcceptableException(
					"Link spec name is already used: " + linkSpec.getOpposite().getName());
		}

		// Specs are listed by ascending order, and the UI assigns one on create. A spec
		// left at the default 0 would jump ahead of every existing spec.
		if (linkSpec.getOrder() == 0) {
			linkSpec.setOrder(linkSpecService.queryAndSort().stream()
					.mapToInt(LinkSpec::getOrder).max().orElse(0) + 1);
		}

		linkSpecService.create(linkSpec);
		var newAuditContent = VersionedXmlDoc.fromBean(linkSpec).toXML();
		auditService.audit(null, "created issue link \"" + linkSpec.getName()
				+ "\" via RESTful API", null, newAuditContent);
		return linkSpec.getId();
	}

	@Api(order=550, description="Update issue link spec of specified id. Renaming either side "
			+ "updates every saved query and issue that references the old name")
	@Path("/{linkSpecId}")
	@POST
	public Response updateSpec(@PathParam("linkSpecId") Long linkSpecId, @NotNull LinkSpec linkSpec) {
		if (!SecurityUtils.isAdministrator())
			throw new UnauthorizedException();

		// The old names have to be read before the incoming state is applied: update()
		// uses them to migrate references and to refuse a rename that would orphan a
		// query still using the old name.
		LinkSpec existing = linkSpecService.load(linkSpecId);
		String oldName = existing.getName();
		String oldOppositeName = existing.getOpposite() != null
				? existing.getOpposite().getName() : null;
		var oldAuditContent = VersionedXmlDoc.fromBean(existing).toXML();

		existing.setName(linkSpec.getName());
		existing.setMultiple(linkSpec.isMultiple());
		existing.setIssueQuery(linkSpec.getIssueQuery());
		existing.setOpposite(linkSpec.getOpposite());
		if (linkSpec.getOrder() != 0)
			existing.setOrder(linkSpec.getOrder());

		linkSpecService.update(existing, oldName, oldOppositeName);

		var newAuditContent = VersionedXmlDoc.fromBean(existing).toXML();
		auditService.audit(null, "changed issue link \"" + oldName + "\" via RESTful API",
				oldAuditContent, newAuditContent);
		return Response.ok().build();
	}

	@Api(order=600)
	@Path("/{linkSpecId}")
	@DELETE
	public Response deleteSpec(@PathParam("linkSpecId") Long linkSpecId) {
		if (!SecurityUtils.isAdministrator())
			throw new UnauthorizedException();

		var linkSpec = linkSpecService.load(linkSpecId);
		var oldAuditContent = VersionedXmlDoc.fromBean(linkSpec).toXML();
		linkSpecService.delete(linkSpec);
		auditService.audit(null, "deleted issue link \"" + linkSpec.getName()
				+ "\" via RESTful API", oldAuditContent, null);
		return Response.ok().build();
	}

}
