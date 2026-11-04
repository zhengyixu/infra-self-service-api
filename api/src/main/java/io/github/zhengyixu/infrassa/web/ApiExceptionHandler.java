package io.github.zhengyixu.infrassa.web;

import io.github.zhengyixu.infrassa.environment.DuplicateEnvironmentNameException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into RFC 9457 problem details ({@code application/problem+json}).
 *
 * <p>The base class already handles Spring MVC's own exceptions (malformed JSON, a path variable
 * that is not a UUID, unsupported media type, ...) as ProblemDetail. This class adds field-level
 * validation errors and the API's domain exceptions.
 *
 * <p>.NET analogy: {@code app.UseExceptionHandler()} + {@code IProblemDetailsService}, or an
 * exception filter. {@code @RestControllerAdvice} applies to every controller.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** 400 with one entry per invalid field, e.g. {@code errors: [{field: "name", message: ...}]}. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setTitle("Invalid request");
        problem.setDetail("One or more fields are invalid.");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @ExceptionHandler
    ProblemDetail handleDuplicateName(DuplicateEnvironmentNameException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Environment name already in use");
        problem.setProperty("name", ex.getName());
        return problem;
    }

    // TODO(you): map EnvironmentNotFoundException to 404 Not Found.
    //  - Same shape as handleDuplicateName above: an @ExceptionHandler method returning a
    //    ProblemDetail with HttpStatus.NOT_FOUND, a title (e.g. "Environment not found") and the
    //    exception message as detail. Optionally add the id as a property, like "name" above.
    //  - Without it, GET /environments/{unknown-id} returns 500, because nothing handles it.
    //  - Then remove @Disabled from EnvironmentControllerTest.getUnknownEnvironmentReturns404Problem.
    //  - Alternative worth knowing for interviews: make the exception extend Spring's
    //    ErrorResponseException; the base class of this handler then renders it with no
    //    handler method at all. Trade-off: the domain exception then depends on Spring Web.
}
