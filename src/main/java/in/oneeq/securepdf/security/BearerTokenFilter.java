package in.oneeq.securepdf.security;

import in.oneeq.securepdf.config.SecurePdfProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Minimal bearer-token gate for the /api/v1/books/** endpoints.
 *
 * <p>Deliberately simple so it is obvious where real authorization belongs.
 * In production this is where you would validate a PoshDesk JWT, confirm the
 * caller is entitled to {@code bookId}, and only then let the request through —
 * especially for the {@code /key} endpoint, which is the crux of the whole
 * "short-lived authorization" security model.
 */
@Component
public class BearerTokenFilter extends OncePerRequestFilter {

    private static final String PROTECTED_PREFIX = "/api/v1/books";

    private final SecurePdfProperties props;

    public BearerTokenFilter(SecurePdfProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PROTECTED_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String expected = "Bearer " + props.getAuthToken();

        if (header == null || !constantTimeEquals(header, expected)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":false,\"error\":\"unauthorized\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes();
        byte[] y = b.getBytes();
        if (x.length != y.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < x.length; i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }
}
