package com.splitmates.auth;

import com.splitmates.common.ApiException;
import com.splitmates.config.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/** Verifies a Google Identity Services ID token via Google's tokeninfo endpoint. */
@Component
public class GoogleVerifier {
    public record GoogleUser(String email, String name) {
    }

    private final AppProperties props;
    private final RestClient http = RestClient.create("https://oauth2.googleapis.com");

    public GoogleVerifier(AppProperties props) {
        this.props = props;
    }

    @SuppressWarnings("unchecked")
    public GoogleUser verify(String idToken) {
        String clientId = props.google().clientId();
        if (clientId == null || clientId.isBlank()) {
            throw ApiException.badRequest("Google sign-in is not configured on this server");
        }
        Map<String, Object> info;
        try {
            info = http.get().uri(u -> u.path("/tokeninfo").queryParam("id_token", idToken).build())
                    .retrieve().body(Map.class);
        } catch (RestClientException e) {
            throw ApiException.unauthorized("Invalid Google token");
        }
        if (info == null || !clientId.equals(info.get("aud"))) throw ApiException.unauthorized("Invalid Google token");
        if (!"true".equals(String.valueOf(info.get("email_verified")))) throw ApiException.unauthorized("Google email not verified");
        String email = String.valueOf(info.get("email")).toLowerCase();
        Object name = info.get("name");
        return new GoogleUser(email, name == null ? email.split("@")[0] : name.toString());
    }
}
