package io.github.houseelves.desk.security;

import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

/**
 * The signed-in GitHub user: their login and the GitHub access token from the
 * session. The token is only ever used server-side, for that user's own calls.
 */
@RequestScoped
public class CurrentUser {

    @Inject
    SecurityIdentity identity;

    public String login() {
        return identity.getPrincipal().getName();
    }

    public String githubToken() {
        AccessTokenCredential token = identity.getCredential(AccessTokenCredential.class);
        if (token == null || token.getToken() == null) {
            throw new UnauthorizedException("No GitHub token in this session - sign in again");
        }
        return token.getToken();
    }
}
