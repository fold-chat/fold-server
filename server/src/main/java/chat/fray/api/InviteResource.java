package chat.fray.api;

import chat.fray.auth.FraySecurityContext;
import chat.fray.db.InviteRepository;
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
    @Context ContainerRequestContext requestContext;

    @POST
    public Response create(CreateInviteRequest req) {
        var sc = sc();
        String id = UUID.randomUUID().toString();
        String code = generateCode(8);

        inviteRepo.create(id, code, sc.getUserId(), req.max_uses(), req.expires_at());
        var invite = inviteRepo.findByCode(code);
        return Response.status(Response.Status.CREATED).entity(invite.orElse(Map.of())).build();
    }

    @GET
    public Response list() {
        return Response.ok(inviteRepo.listActive()).build();
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
        var invite = inviteRepo.findByCode(code);
        if (invite.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        inviteRepo.delete(code);
        return Response.noContent().build();
    }

    // --- DTOs ---

    public record CreateInviteRequest(Long max_uses, String expires_at) {}

    // --- Helpers ---

    private FraySecurityContext sc() {
        return (FraySecurityContext) requestContext.getSecurityContext();
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
