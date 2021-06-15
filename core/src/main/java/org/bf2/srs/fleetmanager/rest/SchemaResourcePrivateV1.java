package org.bf2.srs.fleetmanager.rest;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

/**
 * Get the OpenAPI schema for version 1 of this REST API.
 * Private API.
 *
 * @author Jakub Senko <jsenko@redhat.com>
 */
@Path("/api/serviceregistry_mgmt/v1/admin")
public interface SchemaResourcePrivateV1 {

    /**
     * Get the OpenAPI schema for version 1 of this REST API.
     */
    @Path("/")
    @GET
    @Produces("application/json")
    String getSchema();
}