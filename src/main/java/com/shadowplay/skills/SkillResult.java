package com.shadowplay.skills;

public final class SkillResult {
	public enum Status {
		RUNNING,
		SUCCESS,
		FAILURE
	}

	public static final SkillResult RUNNING = new SkillResult(Status.RUNNING, null);
	public static final SkillResult SUCCESS = new SkillResult(Status.SUCCESS, null);

	private final Status status;
	private final String reason;

	private SkillResult(Status status, String reason) {
		this.status = status;
		this.reason = reason;
	}

	/** reason is short and lowercase, shown as "Step failed (reason)" / "Couldn't finish: reason". */
	public static SkillResult failure(String reason) {
		return new SkillResult(Status.FAILURE, reason);
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
