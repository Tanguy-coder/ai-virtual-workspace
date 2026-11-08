package dev.aiw.identity;

import dev.aiw.shared.ApiException;
import dev.aiw.shared.CurrentUser;
import dev.aiw.shared.Ids;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuthenticationManager authManager;
    private final SecurityContextRepository contextRepository;

    AuthController(JdbcClient jdbc, PasswordEncoder encoder, AuthenticationManager authManager,
                   SecurityContextRepository contextRepository) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.authManager = authManager;
        this.contextRepository = contextRepository;
    }

    /** Lets the SPA obtain the CSRF cookie before its first state-changing request. */
    @GetMapping("/csrf")
    void csrf(CsrfToken token) {
        token.getToken();
    }

    record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
                           @NotBlank @Size(max = 100) String displayName,
                           @NotBlank @Size(min = 12, max = 200) String password) {}

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    UserView register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request,
                      HttpServletResponse response) {
        String id = Ids.newId();
        try {
            jdbc.sql("INSERT INTO users (id, email, display_name, password_hash) VALUES (?, ?, ?, ?)")
                    .params(id, body.email().trim(), body.displayName().trim(), encoder.encode(body.password()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("email_taken", "An account already exists for this email");
        }
        signIn(body.email().trim(), body.password(), request, response);
        return new UserView(id, body.email().trim(), body.displayName().trim());
    }

    record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    @PostMapping("/login")
    UserView login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        signIn(body.email(), body.password(), request, response);
        return me();
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    UserView me() {
        return jdbc.sql("SELECT id, email, display_name FROM users WHERE id = ?")
                .param(CurrentUser.id())
                .query((rs, n) -> new UserView(rs.getString("id"), rs.getString("email"), rs.getString("display_name")))
                .single();
    }

    private void signIn(String email, String password, HttpServletRequest request, HttpServletResponse response) {
        Authentication auth;
        try {
            auth = authManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
        } catch (AuthenticationException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "bad_credentials", "Invalid email or password");
        }
        // New session id on login (session fixation protection).
        HttpSession existing = request.getSession(false);
        if (existing != null) existing.invalidate();
        request.getSession(true);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }
}
