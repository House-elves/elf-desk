package io.github.houseelves.desk.github;

import java.util.Map;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** GitHub's GraphQL endpoint, called with the signed-in user's token. */
@RegisterRestClient(configKey = "github")
@Path("/graphql")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface GitHubApi {

    @POST
    JsonNode graphql(@HeaderParam("Authorization") String authorization, Map<String, Object> request);
}
