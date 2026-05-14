package com.devroom.auth.infra;

import com.devroom.auth.domain.DevroomUserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Bridge mellan DevroomUser (JPA-entitet) och Spring Security:s UserDetails-kontrakt.
 *
 * Spring Security anropar loadUserByUsername() under login-flödet — den slår upp användaren och
 * bygger en UserDetails som Spring sedan använder för password-verifiering (mot PasswordEncoder)
 * och authority-kontroll.
 *
 * team_id ingår inte i UserDetails — TokenCustomizer (Task 11) hämtar den separat via repositoryt
 * när tokens utfärdas.
 */
@Service
public class DevroomUserDetailsService implements UserDetailsService {

    private final DevroomUserRepository repo;

    public DevroomUserDetailsService(DevroomUserRepository repo) {
        this.repo = repo;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        var user = repo.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(username));
        return User.builder()
                .username(user.getUsername())
                .password(user.getPassword())
                .authorities("ROLE_USER")
                .disabled(!user.isEnabled())
                .build();
    }
}
