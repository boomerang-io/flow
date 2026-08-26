package io.boomerang.error;

import org.springframework.http.HttpStatus;

public class BoomerangException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final int code;
	private final String description;
	private final HttpStatus httpStatus;
	private final Object[] args;

	public BoomerangException(int code, String description, HttpStatus httpStatus, Object... args) {
		super();
		this.code = code;
		this.description = description;
		this.httpStatus = httpStatus;
		this.args = args;
	}
	
	public BoomerangException(Throwable ex, int code, String description, HttpStatus httpStatus, Object... args) {
		super(ex);
		this.code = code;
		this.description = description;
		this.httpStatus = httpStatus;
		this.args = args;
	}

	public BoomerangException(BoomerangError error, Object... args) {
		super();
		this.code = error.getCode();
		this.description = error.getDescription(args);
		this.httpStatus = error.getHttpStatus();
		this.args = args;
	}

	public BoomerangException(Throwable ex, BoomerangError error, Object... args) {
		super(ex);
		this.code = error.getCode();
		this.description = error.getDescription(args);
		this.httpStatus = error.getHttpStatus();
		this.args = args;
	}

	public int getCode() {
		return code;
	}

	public String getDescription() {
		return description;
	}

	public HttpStatus getHttpStatus() {
		return httpStatus;
	}

	public Object[] getArgs() {
		return args;
	}

	@Override
	public String toString() {
		return "[" + code + "]: " + description;
	}

}
