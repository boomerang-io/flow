package io.boomerang.error;

import java.util.Locale;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import io.boomerang.error.model.BoomerangError;
import io.boomerang.error.model.ErrorDetail;

@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class RestExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger LOGGER = LogManager.getLogger(ResponseEntityExceptionHandler.class);

	@Autowired
	private MessageSource messageSource;

	@ExceptionHandler({ BoomerangException.class })
	public ResponseEntity<Object> handleBoomerangException(BoomerangException ex, WebRequest request) {

		BoomerangError error = new BoomerangError();
		ErrorDetail errorDetail = new ErrorDetail();
		errorDetail.setCode(ex.getCode());
		errorDetail.setDescription(ex.getDescription());
		errorDetail.setMessage(messageSource.getMessage(errorDetail.getDescription(), ex.getArgs(), errorDetail.getDescription(), Locale.ENGLISH));
		error.setError(errorDetail);

		LOGGER.error("Exception::" + errorDetail.getDescription() + ": ", errorDetail.getMessage());
		LOGGER.error(ExceptionUtils.getStackTrace(ex));

		return new ResponseEntity<Object>(error, new HttpHeaders(), ex.getHttpStatus());
	}

}