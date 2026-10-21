package io.github.houseelves.desk.security;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

/**
 * You: the GitHub account the desk works as (see {@link GhAccount}), and its token. The token is only ever used
 * server-side, for your own calls.
 */
@RequestScoped
public class CurrentUser {

    @Inject
    GhAccount account;

    public String login() {
        return account.login();
    }

    public String githubToken() {
        return account.token();
    }
}
