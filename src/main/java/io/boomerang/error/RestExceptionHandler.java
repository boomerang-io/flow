package io.boomerang.error;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import io.boomerang.errors.model.BoomerangError;
import io.boomerang.errors.model.ErrorDetail;

@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class RestExceptionHandler extends ResponseEntityExceptionHandler {

  @Autowired
  private MessageSource messageSource;

  @ExceptionHandler({BoomerangException.class})
  public ResponseEntity<Object> handleBoomerangException(BoomerangException ex,
      WebRequest request) {

    BoomerangError error = new BoomerangError();
    ErrorDetail errorDetail = new ErrorDetail();
    errorDetail.setCode(ex.getCode());
    errorDetail.setDescription(ex.getDescription());

    String message = messageSource.getMessage(errorDetail.getDescription(), null, Locale.ENGLISH);
    errorDetail.setMessage(message);

    error.setError(errorDetail);

    return new ResponseEntity<>(error, new HttpHeaders(), ex.getHttpStatus());
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
      HttpHeaders headers, HttpStatusCode status, WebRequest request) {

    // Create response structure
    Map<String, Object> response = new HashMap<>();
    response.put("status", HttpStatus.BAD_REQUEST.value());
    response.put("error", "Validation Failed");

    // Create details map for field errors
    Map<String, String> details = new HashMap<>();

    // Process all field errors
    ex.getBindingResult().getAllErrors().forEach((error) -> {
      if (error instanceof FieldError) {
        String fieldName = ((FieldError) error).getField();
        String errorMessage = error.getDefaultMessage();
        details.put(fieldName, errorMessage);
      }
    });

    response.put("details", details);
    return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
  }
}
