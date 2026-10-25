package com.shadowplay.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shadowplay.config.ShadowplayConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Minimal async client for Gemini's generateContent REST endpoint in JSON output mode. Never blocks the caller.
 * The API key travels in the x-goog-api-key header (not the URL) and is never logged or put in error messages.
 */
public final class GeminiClient {
	public static final Duration TIMEOUT = Duration.ofSeconds(15);
	private static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	/** A failed call; the message is safe to show (short, no secrets). status is the HTTP status, or 0. */
	public static final class GeminiException extends RuntimeException {
		private final int status;

		public GeminiException(String message) {
			this(0, message);
		}

		public GeminiException(int status, String message) {
			super(message);
			this.status = status;
		}

		public int status() {
			return status;
		}
	}

	private GeminiClient() {
	}

	/** Sends one request and completes with the model's text (a JSON document), or exceptionally with GeminiException. */
	public static CompletableFuture<String> generateJson(String systemPrompt, String userPrompt) {
		ShadowplayConfig config = ShadowplayConfig.get();
		String key = config.geminiApiKey();
		if (key.isEmpty()) {
			return CompletableFuture.failedFuture(new GeminiException("no Gemini API key"));
		}

		JsonObject body = new JsonObject();
		body.add("systemInstruction", content(null, systemPrompt));
		JsonArray contents = new JsonArray();
		contents.add(content("user", userPrompt));
		body.add("contents", contents);
		JsonObject generation = new JsonObject();
		generation.addProperty("responseMimeType", "application/json");
		generation.addProperty("temperature", 0.2);
		body.add("generationConfig", generation);

		String model = URLEncoder.encode(config.geminiModel, StandardCharsets.UTF_8);
		HttpRequest request = HttpRequest.newBuilder(URI.create(String.format(ENDPOINT, model)))
				.timeout(TIMEOUT)
				.header("Content-Type", "application/json")
				.header("x-goog-api-key", key)
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();

		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.orTimeout(TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS)
				.thenApply(response -> {
					if (response.statusCode() != 200) {
						throw new GeminiException(response.statusCode(),
								"Gemini HTTP " + response.statusCode() + errorDetail(response.body(), key));
					}
					return extractText(response.body());
				});
	}

	private static JsonObject content(String role, String text) {
		JsonObject part = new JsonObject();
		part.addProperty("text", text);
		JsonArray parts = new JsonArray();
		parts.add(part);
		JsonObject content = new JsonObject();
		if (role != null) {
			content.addProperty("role", role);
		}
		content.add("parts", parts);
		return content;
	}

	private static String extractText(String responseBody) {
		try {
			JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
			JsonArray candidates = root.getAsJsonArray("candidates");
			if (candidates == null || candidates.isEmpty()) {
				throw new GeminiException("Gemini returned no candidates");
			}
			JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
			StringBuilder text = new StringBuilder();
			if (content != null && content.has("parts")) {
				for (JsonElement part : content.getAsJsonArray("parts")) {
					JsonObject p = part.getAsJsonObject();
					boolean thought = p.has("thought") && p.get("thought").getAsBoolean();
					if (p.has("text") && !thought) {
						text.append(p.get("text").getAsString());
					}
				}
			}
			if (text.isEmpty()) {
				throw new GeminiException("Gemini returned an empty answer");
			}
			return text.toString();
		} catch (IllegalStateException | ClassCastException | com.google.gson.JsonParseException e) {
			throw new GeminiException("Gemini returned malformed JSON");
		}
	}

	private static String errorDetail(String responseBody, String key) {
		try {
			JsonObject error = JsonParser.parseString(responseBody).getAsJsonObject().getAsJsonObject("error");
			String message = error.get("message").getAsString().replace(key, "***");
			return ": " + (message.length() > 120 ? message.substring(0, 120) + "…" : message);
		} catch (RuntimeException e) {
			return "";
		}
	}
}
