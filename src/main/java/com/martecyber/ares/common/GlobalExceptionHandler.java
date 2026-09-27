package com.martecyber.ares.common;

import com.martecyber.ares.auth.AuthService;
import com.martecyber.ares.plugins.MissingPluginException;
import com.martecyber.ares.plugins.Plugin;
import com.martecyber.ares.plugins.PluginDependencyException;
import com.martecyber.ares.plugins.PluginMessages;
import com.martecyber.ares.plugins.PluginRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Central error handler. Emits RFC 7807 ProblemDetail responses with a
 * consistent envelope.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private final PluginRepository pluginRepo;

    public GlobalExceptionHandler(PluginRepository pluginRepo) {
        this.pluginRepo = pluginRepo;
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException ex, HttpServletRequest req) {
        return problem(HttpStatus.NOT_FOUND, "Not found", ex.getMessage(), req);
    }

    /** Thrown directly by code that already knows exactly which plugin is missing. See {@link
     *  #noHandlerFound} for the other, more common way a missing plugin surfaces — a request to a
     *  path only that plugin's (currently unloaded) controller would have mapped. */
    @ExceptionHandler(MissingPluginException.class)
    public ProblemDetail missingPlugin(MissingPluginException ex, HttpServletRequest req) {
        ProblemDetail pd = problem(HttpStatus.NOT_FOUND, "Plugin required", ex.getMessage(), req);
        pd.setProperty("pluginId", ex.getPluginId());
        return pd;
    }

    /** Fires for any request Spring MVC found no {@code @RequestMapping} for at all (see {@code
     *  spring.mvc.throw-exception-if-no-handler-found} in {@code application.yml}) — the ONLY way
     *  to tell "a plugin that would own this path isn't currently loaded" apart from "this path
     *  never existed," since a not-currently-loaded plugin's controller was never registered with
     *  Spring MVC in the first place (see {@code PluginLoader}'s dynamic {@code
     *  @RestController}/{@code PluginRestController} registration). Matches the request path
     *  against every installed {@link Plugin#getOwnedPathPrefixes()} (kept even while a plugin is
     *  disabled/unloaded, precisely for this) — a match gets the same plugin-naming message as
     *  {@link #missingPlugin}; no match falls back to an ordinary 404, unchanged from before this
     *  handler existed. */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ProblemDetail noHandlerFound(NoHandlerFoundException ex, HttpServletRequest req) {
        String path = req.getRequestURI();
        Plugin owner = pluginRepo.findAll().stream()
            .filter(p -> p.getOwnedPathPrefixes().stream().anyMatch(prefix -> pathMatcher.match(prefix + "/**", path)))
            .findFirst().orElse(null);
        if (owner != null) {
            String msg = PluginMessages.missingPluginMessage(pluginRepo, "This endpoint", owner.getPluginId());
            ProblemDetail pd = problem(HttpStatus.NOT_FOUND, "Plugin required", msg, req);
            pd.setProperty("pluginId", owner.getPluginId());
            return pd;
        }
        return problem(HttpStatus.NOT_FOUND, "Not found", "No endpoint " + req.getMethod() + " " + path, req);
    }

    /** Thrown by {@code PluginService#install} when a plugin's own {@code dependsOn} can't
     *  currently be satisfied — carries structured {@code pluginId}/{@code dependencyId}/{@code
     *  dependencyDisabled} properties so the admin UI can show a dedicated "can't install —
     *  missing dependency" modal instead of a generic error toast (see {@code
     *  PluginDependencyException}'s own doc). */
    @ExceptionHandler(PluginDependencyException.class)
    public ProblemDetail pluginDependency(PluginDependencyException ex, HttpServletRequest req) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Missing plugin dependency", ex.getMessage(), req);
        pd.setProperty("pluginId", ex.getPluginId());
        pd.setProperty("dependencyId", ex.getDependencyId());
        pd.setProperty("dependencyDisabled", ex.isDisabled());
        return pd;
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail conflict(ConflictException ex, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), req);
    }

    /** Same 409 status as plain ConflictException, but with a machine-readable `code` property
     *  the frontend keys off of to offer an "overwrite or rename" choice, rather than just
     *  surfacing the message as a dead-end error. */
    @ExceptionHandler(DuplicateNameException.class)
    public ProblemDetail duplicateName(DuplicateNameException ex, HttpServletRequest req) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "Duplicate name", ex.getMessage(), req);
        pd.setProperty("code", "duplicate_name");
        return pd;
    }

    /** AQL grammar errors, unresolvable field names, and compile-time type/operator mismatches
     *  are all surfaced as 400s rather than silently falling back — unlike the sort-field
     *  whitelist convention elsewhere, a mistyped query should tell the user, not guess. */
    @ExceptionHandler({
        com.martecyber.ares.aql.parser.AqlParseException.class,
        com.martecyber.ares.aql.registry.AqlFieldNotFoundException.class,
        com.martecyber.ares.aql.compile.AqlCompileException.class
    })
    public ProblemDetail aqlError(RuntimeException ex, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid AQL query", ex.getMessage(), req);
    }

    @ExceptionHandler(com.martecyber.ares.workflows.WorkflowValidationException.class)
    public ProblemDetail workflowValidation(com.martecyber.ares.workflows.WorkflowValidationException ex, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid workflow graph", ex.getMessage(), req);
    }

    @ExceptionHandler(AuthService.InvalidCredentialsException.class)
    public ProblemDetail invalidCredentials(HttpServletRequest req) {
        return problem(HttpStatus.UNAUTHORIZED, "Invalid credentials",
            "Email or password is incorrect, or refresh token is not valid.", req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail accessDenied(HttpServletRequest req) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden",
            "You do not have permission to access this resource.", req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> Map.of(
                "field", fe.getField(),
                "code", fe.getCode() == null ? "invalid" : fe.getCode(),
                "message", fe.getDefaultMessage() == null ? "" : fe.getDefaultMessage()))
            .toList();
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed",
            "Request body contains invalid fields.", req);
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail constraint(ConstraintViolationException ex, HttpServletRequest req) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed", ex.getMessage(), req);
    }

    /** Thrown by domain-level validation (e.g. AssetLinkType.validate) that isn't tied to
     *  bean-validation annotations — surface as a real 422, not a 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail illegalArgument(IllegalArgumentException ex, HttpServletRequest req) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed", ex.getMessage(), req);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail badRequest(Exception ex, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", ex.getMessage(), req);
    }

    @ExceptionHandler(ReportGenerationException.class)
    public ProblemDetail reportGeneration(ReportGenerationException ex, HttpServletRequest req) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Report generation failed", ex.getMessage(), req);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail responseStatus(ResponseStatusException ex, HttpServletRequest req) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        if (status.is5xxServerError()) {
            log.error("Unhandled exception at {} {}", req.getMethod(), req.getRequestURI(), ex);
        }
        String reason = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        return problem(status, status.getReasonPhrase(), reason, req);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception at {} {}", req.getMethod(), req.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Server error",
            "An unexpected error occurred.", req);
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail == null ? "" : detail);
        pd.setTitle(title);
        pd.setType(URI.create("https://ares.martecyber.com/errors/" + status.value()));
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
