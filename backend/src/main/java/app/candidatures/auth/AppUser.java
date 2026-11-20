package app.candidatures.auth;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Utilisateur connecté. Toutes les données (offres, CV, réglages) sont rattachées à son `id`.
 * `sessionVersion` est comparé à la base à chaque requête : s'il a changé, la session n'est plus valable.
 */
public record AppUser(long id, String username, String passwordHash, boolean admin, int sessionVersion, boolean totp)
        implements UserDetails, Serializable {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return admin ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }
}
