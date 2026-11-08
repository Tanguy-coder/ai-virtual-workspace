package dev.aiw.identity;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Looks users up by email; the resulting principal name is the user id. */
@Service
class AppUserDetailsService implements UserDetailsService {
    private final JdbcClient jdbc;

    AppUserDetailsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return jdbc.sql("SELECT id, password_hash FROM users WHERE lower(email) = lower(?)")
                .param(email)
                .query((rs, n) -> User.withUsername(rs.getString("id"))
                        .password(rs.getString("password_hash"))
                        .authorities(List.of())
                        .build())
                .optional()
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }
}
