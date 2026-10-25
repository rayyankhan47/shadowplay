package com.shadowplay.skills;

public final class SkillResult {
	public enum Status {
		RUNNING,
		SUCCESS,
		FAILURE
	}

	public static final SkillResult RUNNING = new SkillResult(Status.RUNNING, null, true);
	public static final SkillResult SUCCESS = new SkillResult(Status.SUCCESS, null, true);

	private final Status status;
	private final String reason;
	private final boolean retryable;

	private SkillResult(Status status, String reason, boolean retryable) {
		this.status = status;
		this.reason = reason;
		this.retryable = retryable;
	}

	/** reason is short and lowercase, shown as "Step failed (reason)" / "Couldn't finish: reason". */
	public static SkillResult failure(String reason) {
		return new SkillResult(Status.FAILURE, reason, true);
	}

	/** A failure that would happen again on retry (e.g. missing ingredients); the task fails right away. */
	public static SkillResult fatal(String reason) {
		return new SkillResult(Status.FAILURE, reason, false);
	}

	public boolean retryable() {
		return retryable;
	}

	public Status status() {
		return status;
	}

	public String reason() {
		return reason;
	}

	@Override
	public String toString() {
		return reason == null ? status.name() : status.name() + "(" + reason + ")";
	}
}
