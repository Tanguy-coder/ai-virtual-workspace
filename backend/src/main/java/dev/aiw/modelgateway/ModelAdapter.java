package dev.aiw.modelgateway;

interface ModelAdapter {
    ProviderKind kind();

    AdapterResult call(String providerId, String baseUrl, String apiKey, ModelRequest request);
    /** Model ids the provider reports for this key, sorted. */
    java.util.List<String> listModels(String providerId, String baseUrl, String apiKey);

    /** The same models with what the provider says about them (context window), for the router (docs/15). */
    default java.util.List<ModelInfo> listModelInfo(String providerId, String baseUrl, String apiKey) {
        return listModels(providerId, baseUrl, apiKey).stream().map(id -> new ModelInfo(id, null)).toList();
    }

    /** Called when a provider is deleted or its key changes, to drop cached clients. */
    default void evict(String providerId) {}
}
