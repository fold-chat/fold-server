package chat.fray.api;

import chat.fray.auth.FraySecurityContext;
import chat.fray.db.CategoryRepository;
import chat.fray.db.ChannelRepository;
import chat.fray.event.*;
import chat.fray.security.Permission;
import chat.fray.security.PermissionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/v0/categories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CategoryResource {

    @Inject CategoryRepository categoryRepo;
    @Inject ChannelRepository channelRepo;
    @Inject PermissionService permissionService;
    @Inject EventBus eventBus;
    @Context ContainerRequestContext requestContext;

    @GET
    public Response list() {
        return Response.ok(categoryRepo.listAll()).build();
    }

    @POST
    public Response create(CreateCategoryRequest req) {
        permissionService.requireServerPermission(sc().getUserId(), Permission.MANAGE_CHANNELS);
        if (req.name() == null || req.name().isBlank()) {
            return Response.status(400).entity(Map.of("error", "invalid_name", "message", "Name required")).build();
        }
        String id = UUID.randomUUID().toString();
        int position = req.position() != null ? req.position() : categoryRepo.nextPosition();
        categoryRepo.create(id, req.name().trim(), position);
        var created = categoryRepo.findById(id);
        created.ifPresent(c -> eventBus.publish(Event.of(EventType.CATEGORY_CREATE, c, Scope.server())));
        return created
                .map(c -> Response.status(201).entity(c).build())
                .orElse(Response.status(500).build());
    }

    @PATCH
    @Path("/{id}")
    public Response update(@PathParam("id") String id, UpdateCategoryRequest req) {
        permissionService.requireServerPermission(sc().getUserId(), Permission.MANAGE_CHANNELS);
        var existing = categoryRepo.findById(id);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        var cat = existing.get();
        String name = req.name() != null ? req.name().trim() : (String) cat.get("name");
        Integer position = req.position() != null ? req.position() : ((Long) cat.get("position")).intValue();
        categoryRepo.update(id, name, position);
        var updated = categoryRepo.findById(id);
        updated.ifPresent(c -> eventBus.publish(Event.of(EventType.CATEGORY_UPDATE, c, Scope.server())));
        return updated
                .map(c -> Response.ok(c).build())
                .orElse(Response.status(500).build());
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        permissionService.requireServerPermission(sc().getUserId(), Permission.MANAGE_CHANNELS);
        if (categoryRepo.findById(id).isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        // Delete all channels in this category first, then the category
        var channels = channelRepo.findByCategoryId(id);
        for (var ch : channels) {
            channelRepo.delete((String) ch.get("id"));
            eventBus.publish(Event.of(EventType.CHANNEL_DELETE, Map.of("id", ch.get("id")), Scope.server()));
        }
        categoryRepo.delete(id);
        eventBus.publish(Event.of(EventType.CATEGORY_DELETE, Map.of("id", id), Scope.server()));
        return Response.noContent().build();
    }

    @PATCH
    @Path("/reorder")
    public Response reorder(List<ReorderItem> items) {
        permissionService.requireServerPermission(sc().getUserId(), Permission.MANAGE_CHANNELS);
        if (items == null || items.isEmpty()) {
            return Response.status(400).entity(Map.of("error", "invalid_body", "message", "Items required")).build();
        }
        for (var item : items) {
            if (item.id() == null || categoryRepo.findById(item.id()).isEmpty()) {
                return Response.status(400).entity(Map.of("error", "invalid_id", "message", "Category not found: " + item.id())).build();
            }
        }
        categoryRepo.batchUpdatePositions(
                items.stream().map(i -> new chat.fray.db.CategoryRepository.IdPosition(i.id(), i.position())).toList()
        );
        for (var item : items) {
            categoryRepo.findById(item.id()).ifPresent(c ->
                    eventBus.publish(Event.of(EventType.CATEGORY_UPDATE, c, Scope.server())));
        }
        return Response.ok(categoryRepo.listAll()).build();
    }

    public record CreateCategoryRequest(String name, Integer position) {}
    public record UpdateCategoryRequest(String name, Integer position) {}
    public record ReorderItem(String id, int position) {}

    private FraySecurityContext sc() {
        return (FraySecurityContext) requestContext.getSecurityContext();
    }
}
