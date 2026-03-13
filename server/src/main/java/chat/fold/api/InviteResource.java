package chat.fold.api;

import chat.fold.auth.FoldSecurityContext;
import chat.fold.db.InviteRepository;
import chat.fold.security.Permission;
import chat.fold.security.PermissionService;
import chat.fold.service.AuditLogService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;

@Path("/api/v0/invites")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class InviteResource {

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";

    @Inject InviteRepository inviteRepo;
    @Inject PermissionService permissionService;
    @Inject AuditLogService auditLogService;
    @Context ContainerRequestContext requestContext;

    @POST
    public Response create(CreateInviteRequest req) {
        var sc = sc();
        permissionService.requireServerPermission(sc.getUserId(), Permission.CREATE_INVITES);
        String id = UUID.randomUUID().toString();
        String code = generateCode(8);

        if (req.description() == null || req.description().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "description_required")).build();
        }
        inviteRepo.create(id, code, sc.getUserId(), req.description().strip(), req.max_uses(), req.expires_at());
        var invite = inviteRepo.findByCode(code);
        auditLogService.log(sc.getUserId(), "INVITE_CREATE", "invite", id, Map.of("code", code));
        return Response.status(Response.Status.CREATED).entity(invite.orElse(Map.of())).build();
    }

    @GET
    public Response list() {
        permissionService.requireServerPermission(sc().getUserId(), Permission.CREATE_INVITES);
        return Response.ok(inviteRepo.listAll()).build();
    }

    @GET
    @Path("/{code}")
    public Response getByCode(@PathParam("code") String code) {
        // Public endpoint — return limited info
        var invite = inviteRepo.findByCode(code);
        if (invite.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        var inv = invite.get();
        boolean valid = isValid(inv);
        return Response.ok(Map.of(
                "code", code,
                "valid", valid,
                "expires_at", inv.get("expires_at") != null ? inv.get("expires_at") : ""
        )).build();
    }

    @DELETE
    @Path("/{code}")
    public Response revoke(@PathParam("code") String code) {
        permissionService.requireServerPermission(sc().getUserId(), Permission.MANAGE_INVITES);
        var invite = inviteRepo.findByCode(code);
        if (invite.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        var inviteId = (String) invite.get().get("id");
        inviteRepo.revoke(code);
        auditLogService.log(sc().getUserId(), "INVITE_REVOKE", "invite", inviteId, Map.of("code", code));
        return Response.ok(inviteRepo.findByCode(code).orElse(Map.of())).build();
    }

    @POST
    @Path("/{code}/reinstate")
    public Response reinstate(@PathParam("code") String code) {
        permissionService.requireServerPermission(sc().getUserId(), Permission.MANAGE_INVITES);
        var invite = inviteRepo.findByCode(code);
        if (invite.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        var inviteId = (String) invite.get().get("id");
        inviteRepo.reinstate(code);
        auditLogService.log(sc().getUserId(), "INVITE_REINSTATE", "invite", inviteId, Map.of("code", code));
        return Response.ok(inviteRepo.findByCode(code).orElse(Map.of())).build();
    }

    // --- DTOs ---

    public record CreateInviteRequest(String description, Long max_uses, String expires_at) {}

    // --- Helpers ---

    private FoldSecurityContext sc() {
        return (FoldSecurityContext) requestContext.getSecurityContext();
    }

    private static String generateCode(int length) {
        var random = new SecureRandom();
        var sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private static boolean isValid(Map<String, Object> invite) {
        if (invite.get("revoked_at") != null) return false;
        var expiresAt = invite.get("expires_at");
        if (expiresAt != null && !expiresAt.toString().isEmpty()) {
            if (java.time.Instant.parse(expiresAt.toString()).isBefore(java.time.Instant.now())) {
                return false;
            }
        }
        var maxUses = invite.get("max_uses");
        var useCount = invite.get("use_count");
        if (maxUses != null && useCount != null) {
            return ((Long) useCount) < ((Long) maxUses);
        }
        return true;
    }
}
