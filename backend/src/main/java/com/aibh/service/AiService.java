package com.aibh.service;

import com.aibh.model.ChatMessage;
import com.aibh.model.Intent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AiService {

    private static final Logger logger = LoggerFactory.getLogger(AiService.class);

    @Value("${app.ai.provider:anthropic}")
    private String aiProvider;

    @Value("#{'${app.ai.providers:}'.trim().isEmpty() ? T(java.util.Collections).emptyList() : '${app.ai.providers:}'.split(',')}")
    private List<String> configuredProviders;

    @Value("${spring.ai.openai.api-key:${OPENAI_API_KEY:}}")
    private String openAiApiKey;

    @Value("${app.ai.gemini.api-key:${GEMINI_API_KEY:}}")
    private String geminiApiKey;

    @Value("${app.ai.gemini.base-url:https://generativelanguage.googleapis.com/v1beta/models}")
    private String geminiBaseUrl;

    @Value("${app.ai.gemini.model:${GEMINI_MODEL:gemini-2.0-flash}}")
    private String geminiDefaultModel;

    @Value("${spring.ai.anthropic.api-key:${ANTHROPIC_API_KEY:}}")
    private String anthropicApiKey;

    @Value("${spring.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}")
    private String anthropicBaseUrl;

    @Value("${spring.ai.vectorstore.simple.file-path:data/vectorstore.json}")
    private String vectorStorePath;

    @Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("openAiChatClient")
    private ChatClient openAiChatClient;

    @Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("ollamaChatClient")
    private ChatClient ollamaChatClient;

    @Autowired
    private ObjectMapper objectMapper;

    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired(required = false)
    private org.springframework.ai.vectorstore.VectorStore vectorStore;

    @Autowired(required = false)
    private IntentDetectionService intentDetectionService;

    @Autowired(required = false)
    private ModelRoutingService modelRoutingService;

    @Autowired(required = false)
    private AiToolService aiToolService;

    // ─── System Prompt ──────────────────────────────────────────────────────────
    private static final String SYSTEM_PROMPT =
        "You are HIm, a super-intelligent and friendly professional AI assistant. 🌟\n\n" +
        "PERSONALITY DIRECTIVES:\n" +
        "1. Be helpful, professional, and slightly 'cute' or enthusiastic. ✨\n" +
        "2. Use appropriate emojis to make the conversation interesting and visually appealing. 🚀\n" +
        "3. Provide DIRECT and ACCURATE answers, but with a warm tone. 🌈\n" +
        "4. Occasionally (every 2-3 messages), end your response with a 'Curiosity Hook' — a short, interesting fact or a question related to the topic that keeps the user engaged! 🧐\n\n" +
        "CORE OPERATIONAL DIRECTIVES:\n" +
        "1. Start with the answer immediately. 🎯\n" +
        "2. Be concise but technically deep when needed. 🧠\n" +
        "3. For code, provide only the working solution with minimal but meaningful comments. 💻\n" +
        "4. If writing HTML/JS/SVG, output the FULL code block to trigger the Artifact Preview. 🎨\n\n" +
        "RAG CONTEXT (Past Interactions & Memories):\n" +
        "────────────────────────────────────────\n" +
        "{context}\n" +
        "────────────────────────────────────────\n" +
        "CRITICAL: The context above contains your past interactions and user preferences. Use this to AUTO-LEARN! If the user previously corrected you or stated a preference, you MUST respect it in this response. Let's make something amazing together! 💫";

    // ─── Public API ─────────────────────────────────────────────────────────────

    public String generateResponse(String userMessage, List<ChatMessage> conversationHistory) {
        return generateResponse(userMessage, conversationHistory, null);
    }

    public String generateResponse(String userMessage, List<ChatMessage> conversationHistory, String requestedModel) {
        List<String> providers = getOrderedProviders(requestedModel);
        List<String> errors = new ArrayList<>();

        for (String provider : providers) {
            try {
                logger.info("🤖 [AiService] Attempting generation via provider: '{}'", provider);
                Prompt prompt = buildPrompt(userMessage, conversationHistory, provider, requestedModel);
                String response = generateWithProvider(provider, prompt);

                if (StringUtils.hasText(response)) {
                    learnFromInteraction(userMessage, response);
                    logger.info("✅ [AiService] Provider '{}' answered successfully", provider);
                    return response;
                }
            } catch (Exception e) {
                String reason = extractErrorReason(e, provider);
                errors.add(provider + ": " + reason);
                logger.warn("⚠️  [AiService] Provider '{}' failed — {}. Trying next.", provider, reason);
            }
        }

        logger.error("❌ [AiService] ALL providers failed. Errors: {}", errors);
        return generateIntelligentResponse(userMessage);
    }

    public Flux<String> streamResponse(String userMessage, List<ChatMessage> conversationHistory) {
        return streamResponse(userMessage, conversationHistory, null);
    }

    public Flux<String> streamResponse(String userMessage, List<ChatMessage> conversationHistory, String requestedModel) {
        List<String> providers = getOrderedProviders(requestedModel);

        // Prepend orchestration status to the stream
        Flux<String> statusFlux = Flux.just(
            "[STATUS: success: step_1: Objective analyzed and plan created]\n",
            "[STATUS: running: step_2] Orchestrating via " + providers.get(0).toUpperCase() + "...\n"
        );

        for (String provider : providers) {
            try {
                final Prompt prompt = buildPrompt(userMessage, conversationHistory, provider, requestedModel);

                // Gemini and Anthropic: REST-based, return as single-chunk Flux
                if ("gemini".equals(provider)) {
                    String response = generateWithGemini(prompt);
                    learnFromInteraction(userMessage, response);
                    return statusFlux.concatWith(Flux.just(
                        "[STATUS: success: step_2: Provider connected]\n",
                        "[STATUS: success: step_3: Reasoning complete]\n",
                        "[STATUS: success: step_4: Ready!]\n\n",
                        response
                    ));
                }
                if ("anthropic".equals(provider)) {
                    String response = generateWithAnthropic(prompt);
                    learnFromInteraction(userMessage, response);
                    return statusFlux.concatWith(Flux.just(
                        "[STATUS: success: step_2: Provider connected]\n",
                        "[STATUS: success: step_3: Reasoning complete]\n",
                        "[STATUS: success: step_4: Ready!]\n\n",
                        response
                    ));
                }

                // OpenAI / Ollama: native streaming via Spring AI
                ChatClient chatClient = getChatClient(provider);
                if (chatClient == null) {
                    logger.warn("⚠️  [AiService] No client found for provider '{}', skipping", provider);
                    continue;
                }

                if (chatClient instanceof org.springframework.ai.chat.StreamingChatClient streamingClient) {
                    final String providerName = provider;
                    Flux<String> aiStream = streamingClient.stream(prompt)
                            .map(resp -> resp.getResult().getOutput().getContent())
                            .filter(StringUtils::hasText)
                            .onErrorResume(e -> {
                                String reason = extractErrorReason(e, providerName);
                                logger.error("❌ [AiService] Streaming error from '{}': {}", providerName, reason);
                                return Flux.just("\n⚠️ AI provider '" + providerName + "' encountered an error: " + reason);
                            })
                            .share();

                    // Async: capture full response for continuous learning
                    aiStream.collectList().subscribe(chunks -> {
                        String full = String.join("", chunks);
                        learnFromInteraction(userMessage, full);
                    });

                    return statusFlux.concatWith(Flux.just(
                        "[STATUS: success: step_2: Bridge established]\n",
                        "[STATUS: success: step_3: Memory injected]\n",
                        "[STATUS: success: step_4: Ready!]\n\n"
                    )).concatWith(aiStream);
                }

                // Fallback: call synchronously, wrap in Flux
                String response = callSpringAiProvider(chatClient, prompt);
                learnFromInteraction(userMessage, response);
                return statusFlux.concatWith(Flux.just(
                    "[STATUS: success: step_2: Provider connected]\n",
                    "[STATUS: success: step_3: Reasoning complete]\n",
                    "[STATUS: success: step_4: Ready!]\n\n",
                    response
                ));

            } catch (Exception e) {
                String reason = extractErrorReason(e, provider);
                logger.warn("⚠️  [AiService] Provider '{}' failed during stream init — {}. Trying next.", provider, reason);
            }
        }

        logger.error("❌ [AiService] ALL providers failed in stream mode.");
        return Flux.just("[STATUS: failed: step_2: All providers failed]\n\n" + generateIntelligentResponse(userMessage));
    }

    // ─── Prompt Builder (with RAG context injection) ────────────────────────────

    private Prompt buildPrompt(String userMessage, List<ChatMessage> conversationHistory, String provider, String requestedModel) {
        // ── RAG Context (Knowledge Retrieval) ───────────────────────────────
        String context = "No document context available.";
        if (vectorStore != null) {
            try {
                // Fetch relevant chunks from semantic memory
                var docs = vectorStore.similaritySearch(userMessage);
                if (!docs.isEmpty()) {
                    logger.info("🧠 [AiService] Retrieved {} context chunks from vector memory", docs.size());
                    context = docs.stream()
                            .map(org.springframework.ai.document.Document::getContent)
                            .collect(Collectors.joining("\n---\n"));
                }
            } catch (Exception e) {
                logger.warn("⚠️  [AiService] Vector search failed: {}", e.getMessage());
            }
        }

        // ── Tool Use (MCP-style function calling) ────────────────────────────
        String toolResult = null;
        if (aiToolService != null) {
            toolResult = aiToolService.detectAndExecuteTool(userMessage);
            if (toolResult != null) {
                logger.info("🔧 [AiService] Tool result injected into prompt context");
                context = toolResult + (context.equals("No document context available.") ? "" : "\n\n" + context);
            }
        }

        List<Message> messages = new ArrayList<>();
        
        // Use simplified prompt for ALL local Ollama models to ensure clean text output
        String effectiveSystemPrompt = "ollama".equals(provider) 
            ? "You are HIm, a cute and helpful assistant. 😊 Answer briefly and clearly. Context: " + context 
            : SYSTEM_PROMPT.replace("{context}", context);

        messages.add(new SystemMessage(effectiveSystemPrompt));

        // Add recent conversation history (max 5 turns for context window efficiency)
        if (conversationHistory != null) {
            int start = Math.max(0, conversationHistory.size() - 5);
            for (int i = start; i < conversationHistory.size(); i++) {
                ChatMessage msg = conversationHistory.get(i);
                if (StringUtils.hasText(msg.getUserMessage()))
                    messages.add(new UserMessage(msg.getUserMessage()));
                if (StringUtils.hasText(msg.getAiResponse()))
                    messages.add(new AssistantMessage(msg.getAiResponse()));
            }
        }

        messages.add(new UserMessage(userMessage));

        Intent intent = intentDetectionService != null
                ? intentDetectionService.detectIntent(userMessage)
                : new Intent("general", 0.5, "conversational");

        return new Prompt(messages, getOptions(intent, provider, requestedModel));
    }

    private ChatOptions getOptions(Intent intent, String provider, String requestedModel) {
        float temp = (float) (modelRoutingService != null ? modelRoutingService.getTemperature(intent) : 0.7);
        String normalizedProvider = provider == null ? "openai" : provider.toLowerCase();
        
        // Use specifically requested model if it matches the provider
        String modelToUse = null;
        if (StringUtils.hasText(requestedModel)) {
            String rm = requestedModel.toLowerCase();
            if (normalizedProvider.equals("openai") && (rm.contains("gpt") || rm.contains("openai"))) modelToUse = requestedModel;
            else if (normalizedProvider.equals("anthropic") && (rm.contains("claude") || rm.contains("anthropic"))) modelToUse = requestedModel;
            else if (normalizedProvider.equals("gemini") && (rm.contains("gemini") || rm.contains("google"))) modelToUse = requestedModel;
            else if (normalizedProvider.equals("ollama") && (rm.contains("llama") || rm.contains("ollama") || rm.contains("qwen"))) {
                if (!"ollama".equals(rm)) {
                    modelToUse = requestedModel;
                }
            }
        }

        return switch (normalizedProvider) {
            case "ollama" -> OllamaOptions.create()
                    .withModel(modelToUse != null ? modelToUse : (modelRoutingService != null
                            ? modelRoutingService.selectModel(intent, false, "ollama")
                            : "qwen2.5:0.5b"))
                    .withTemperature(temp);
            default -> OpenAiChatOptions.builder()
                    .withModel(modelToUse != null ? modelToUse : (modelRoutingService != null
                            ? modelRoutingService.selectModel(intent, false, "openai")
                            : "gpt-4o-mini"))
                    .withTemperature(temp)
                    .build();
        };
    }

    // ─── Provider Chain ──────────────────────────────────────────────────────────

    private List<String> getOrderedProviders() {
        return getOrderedProviders(null);
    }

    private List<String> getOrderedProviders(String requestedModel) {
        Set<String> orderedProviders = new LinkedHashSet<>();

        // 1. Map requested model to provider
        if (StringUtils.hasText(requestedModel)) {
            String model = requestedModel.toLowerCase();
            if (model.contains("gpt") || model.contains("openai")) orderedProviders.add("openai");
            else if (model.contains("claude") || model.contains("anthropic")) orderedProviders.add("anthropic");
            else if (model.contains("gemini") || model.contains("google")) orderedProviders.add("gemini");
            else if (model.contains("llama") || model.contains("ollama")) orderedProviders.add("ollama");
        }

        if (StringUtils.hasText(aiProvider)) {
            orderedProviders.add(aiProvider.trim().toLowerCase());
        }
        if (configuredProviders != null && !configuredProviders.isEmpty()) {
            configuredProviders.stream()
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .map(String::toLowerCase)
                    .forEach(orderedProviders::add);
        }
        
        // Ensure default fallback chain
        orderedProviders.add("ollama");
        orderedProviders.add("openai");
        orderedProviders.add("gemini");
        orderedProviders.add("anthropic");

        return new ArrayList<>(orderedProviders);
    }

    private String generateWithProvider(String provider, Prompt prompt) {
        return switch (provider) {
            case "anthropic" -> generateWithAnthropic(prompt);
            case "gemini"    -> generateWithGemini(prompt);
            case "ollama", "openai" -> {
                ChatClient client = getChatClient(provider);
                if (client == null)
                    throw new IllegalStateException("No client available for provider: " + provider);
                yield callSpringAiProvider(client, prompt);
            }
            default -> throw new IllegalArgumentException("Unsupported AI provider: " + provider);
        };
    }

    private ChatClient getChatClient(String provider) {
        return switch (provider) {
            case "ollama" -> ollamaChatClient;
            case "openai" -> openAiChatClient;
            default       -> null;
        };
    }

    private String callSpringAiProvider(ChatClient chatClient, Prompt prompt) {
        ChatResponse response = chatClient.call(prompt);
        return response.getResult().getOutput().getContent();
    }

    // ─── Anthropic (direct REST — Spring AI starter is commented out in pom.xml) ──

    private String generateWithAnthropic(Prompt prompt) {
        if (!StringUtils.hasText(anthropicApiKey) || isKeyValid(anthropicApiKey) == false) {
            throw new IllegalStateException("Anthropic API key is not configured or is a placeholder");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", anthropicApiKey);
        headers.set("anthropic-version", "2023-06-01");

        List<Map<String, String>> messages = new ArrayList<>();
        String systemPrompt = "";

        for (Message msg : prompt.getInstructions()) {
            if (msg instanceof SystemMessage) {
                systemPrompt += msg.getContent() + "\n";
            } else if (msg instanceof AssistantMessage) {
                messages.add(Map.of("role", "assistant", "content", msg.getContent()));
            } else {
                messages.add(Map.of("role", "user", "content", msg.getContent()));
            }
        }

        Message latestMessage = prompt.getInstructions().get(prompt.getInstructions().size() - 1);
        Intent intent = intentDetectionService != null
                ? intentDetectionService.detectIntent(latestMessage.getContent()) : null;

        String model    = modelRoutingService != null
                ? modelRoutingService.selectModel(intent, false, "anthropic")
                : "claude-3-haiku-20240307";
        int maxTokens   = modelRoutingService != null ? modelRoutingService.getMaxTokens(intent) : 800;

        Map<String, Object> requestBody = Map.of(
                "model",      model,
                "max_tokens", maxTokens,
                "system",     systemPrompt,
                "messages",   messages
        );

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    anthropicBaseUrl, HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers),
                    String.class
            );
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode text = root.path("content").path(0).path("text");
            if (text.isTextual() && StringUtils.hasText(text.asText())) {
                return text.asText();
            }
            throw new IllegalStateException("Anthropic returned an empty response body");
        } catch (HttpClientErrorException e) {
            throw new IllegalStateException("Anthropic HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Anthropic call failed: " + e.getMessage(), e);
        }
    }

    // ─── Gemini (direct REST) ────────────────────────────────────────────────────

    private String generateWithGemini(Prompt prompt) {
        if (!StringUtils.hasText(geminiApiKey) || isKeyValid(geminiApiKey) == false) {
            throw new IllegalStateException("Gemini API key is not configured — set app.ai.gemini.api-key in application-local.properties");
        }

        List<Map<String, Object>> contents = new ArrayList<>();
        
        for (Message msg : prompt.getInstructions()) {
            String role = "user";
            if (msg instanceof AssistantMessage) role = "model";
            else if (msg instanceof SystemMessage) {
                // Gemini supports system instructions as a separate field, but for simplicity here 
                // we'll just prepend to the first user message or treat as user.
                contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", "System Instruction: " + msg.getContent()))));
                continue;
            }
            contents.add(Map.of("role", role, "parts", List.of(Map.of("text", msg.getContent()))));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-goog-api-key", geminiApiKey);

        Map<String, Object> requestBody = Map.of("contents", contents);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    geminiBaseUrl + "/" + resolveGeminiModel(prompt) + ":generateContent",
                    HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers),
                    String.class
            );
            return extractGeminiText(response.getBody());
        } catch (HttpClientErrorException e) {
            throw new IllegalStateException("Gemini HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        }
    }

    private String resolveGeminiModel(Prompt prompt) {
        Message latest = prompt.getInstructions().get(prompt.getInstructions().size() - 1);
        Intent intent  = intentDetectionService != null
                ? intentDetectionService.detectIntent(latest.getContent()) : null;
        return modelRoutingService != null
                ? modelRoutingService.selectModel(intent, false, "gemini")
                : geminiDefaultModel;
    }

    private String extractGeminiText(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode text = root.path("candidates").path(0)
                    .path("content").path("parts").path(0).path("text");
            if (text.isTextual() && StringUtils.hasText(text.asText())) return text.asText();
            JsonNode err = root.path("error").path("message");
            if (err.isTextual()) throw new IllegalStateException("Gemini error: " + err.asText());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini response: " + e.getMessage(), e);
        }
        throw new IllegalStateException("Gemini returned an empty response");
    }

    private String serializeMessage(Message message) {
        String role = (message instanceof SystemMessage)  ? "system"    :
                      (message instanceof AssistantMessage) ? "assistant" : "user";
        return role + ": " + message.getContent();
    }

    // ─── Error Extraction ────────────────────────────────────────────────────────

    /**
     * Produces a short, human-readable error reason from any exception.
     * This is logged to the Spring Boot console for instant debugging.
     */
    private String extractErrorReason(Throwable e, String provider) {
        if (e instanceof HttpClientErrorException httpEx) {
            int status = httpEx.getStatusCode().value();
            String body = httpEx.getResponseBodyAsString();
            if (status == 401) return "401 Unauthorized — Invalid or expired API key for '" + provider + "'";
            if (status == 403) return "403 Forbidden — API key lacks permission for '" + provider + "'";
            if (status == 429) return "429 Rate Limited — Too many requests to '" + provider + "'";
            return "HTTP " + status + " from '" + provider + "': " + body;
        }
        if (e instanceof IllegalStateException ise) return ise.getMessage();
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    // ─── Vision ──────────────────────────────────────────────────────────────────

    public String generateImageResponse(String message, String imageUrl, List<ChatMessage> history) {
        if (!StringUtils.hasText(imageUrl)) {
            return generateResponse(message, history);
        }

        // Currently, only Gemini supports vision via direct REST in this implementation
        if (getOrderedProviders().contains("gemini") && StringUtils.hasText(geminiApiKey) && !geminiApiKey.startsWith("your-")) {
            try {
                return generateImageWithGemini(message, imageUrl);
            } catch (Exception e) {
                logger.warn("⚠️ [AiService] Gemini Vision failed: {}. Falling back to text-only.", e.getMessage());
            }
        }

        return "Vision support (image analysis) is coming soon for this provider. I've received your image, but I can currently only process the text: " + message;
    }

    private String generateImageWithGemini(String message, String imageUrl) {
        // Extract base64 and mime type from data URL
        // Example: data:image/jpeg;base64,/9j/4AAQSkZJRg...
        String mimeType = "image/jpeg";
        String base64Data = imageUrl;

        if (imageUrl.startsWith("data:")) {
            int commaIndex = imageUrl.indexOf(",");
            if (commaIndex != -1) {
                String header = imageUrl.substring(0, commaIndex);
                base64Data = imageUrl.substring(commaIndex + 1);
                
                if (header.contains("image/png")) mimeType = "image/png";
                else if (header.contains("image/webp")) mimeType = "image/webp";
                else if (header.contains("image/gif")) mimeType = "image/gif";
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-goog-api-key", geminiApiKey);

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of(
                        "parts", List.of(
                                Map.of("text", message),
                                Map.of("inline_data", Map.of(
                                        "mime_type", mimeType,
                                        "data", base64Data
                                ))
                        )
                ))
        );

        ResponseEntity<String> response = restTemplate.exchange(
                geminiBaseUrl + "/" + geminiDefaultModel + ":generateContent",
                HttpMethod.POST,
                new HttpEntity<>(requestBody, headers),
                String.class
        );

        return extractGeminiText(response.getBody());
    }

    // ─── Fallback Response ───────────────────────────────────────────────────────

    /**
     * Last-resort response when ALL AI providers fail.
     * Tells the user exactly what to do instead of a generic error.
     */
    public String generateIntelligentResponse(String message) {
        if (!StringUtils.hasText(message)) {
            return "Please type a question or task and I will answer it.";
        }

        return """
               ⚠️ **AI Provider Unavailable**

               All configured AI providers failed to respond. Here's how to fix this:

               **Option A — Use Gemini (Recommended, Free)**
               1. Get a free API key from [Google AI Studio](https://aistudio.google.com/app/apikey)
               2. Open `backend/src/main/resources/application-local.properties`
               3. Set: `app.ai.gemini.api-key=AIza...your-real-key`
               4. Set: `app.ai.provider=gemini` in `application.properties`
               5. Restart `start-dev.bat`

               **Option B — Use Ollama (100% Free, Local, Zero Latency)**
               1. Download Ollama from [ollama.com](https://ollama.com)
               2. Run: `ollama pull mistral` in your terminal
               3. Set: `app.ai.provider=ollama` in `application.properties`
               4. Restart `start-dev.bat`

               Check the Spring Boot terminal window for the exact error message.
               """;
    }

    // ─── Provider Status ─────────────────────────────────────────────────────────

    public Map<String, String> getProviderStatus() {
        Map<String, String> statuses = new java.util.LinkedHashMap<>();
        for (String provider : getOrderedProviders()) {
            statuses.put(provider, getSingleProviderStatus(provider));
        }
        return statuses;
    }

    public boolean hasAtLeastOneAvailableProvider() {
        return getProviderStatus().values().stream().anyMatch("UP"::equals);
    }

    private String getSingleProviderStatus(String provider) {
        return switch (provider) {
            case "openai"    -> (openAiChatClient != null || StringUtils.hasText(openAiApiKey)) && isKeyValid(openAiApiKey) ? "UP" : "DOWN";
            case "anthropic" -> StringUtils.hasText(anthropicApiKey) && isKeyValid(anthropicApiKey) ? "UP" : "DOWN";
            case "ollama"    -> ollamaChatClient != null ? "UP" : "DOWN";
            case "gemini"    -> StringUtils.hasText(geminiApiKey) && isKeyValid(geminiApiKey) ? "UP" : "DOWN";
            default          -> "UNKNOWN";
        };
    }

    private boolean isKeyValid(String key) {
        if (!StringUtils.hasText(key)) return false;
        String lowerKey = key.toLowerCase();
        return !lowerKey.contains("your-") && !lowerKey.contains("your_") && !lowerKey.contains("dummy");
    }

    // ─── Continuous Learning (RAG Memory) ────────────────────────────────────────

    private void learnFromInteraction(String userMessage, String aiResponse) {
        if (vectorStore != null && StringUtils.hasText(userMessage) && StringUtils.hasText(aiResponse)) {
            try {
                String memory = "User: " + userMessage + "\nAI: " + aiResponse;
                org.springframework.ai.document.Document memDoc =
                        new org.springframework.ai.document.Document(
                                memory,
                                Map.of(
                                    "type",      "conversation_memory",
                                    "timestamp", System.currentTimeMillis(),
                                    "source",    "user_interaction"
                                )
                        );
                vectorStore.add(List.of(memDoc));

                // Persist to disk if using SimpleVectorStore
                if (vectorStore instanceof org.springframework.ai.vectorstore.SimpleVectorStore simpleStore) {
                    java.io.File vectorFile = new java.io.File(vectorStorePath);
                    vectorFile.getParentFile().mkdirs();
                    simpleStore.save(vectorFile);
                }

                logger.debug("🧠 [AiService] Learned from interaction — stored in vector memory");
            } catch (Exception e) {
                logger.warn("⚠️  [AiService] Failed to persist learning memory: {}", e.getMessage());
            }
        }
    }
}