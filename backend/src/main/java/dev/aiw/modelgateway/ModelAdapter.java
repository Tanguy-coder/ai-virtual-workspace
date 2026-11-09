package dev.aiw.modelgateway;

interface ModelAdapter {
    ProviderKind kind();

    AdapterResult call(String providerId, String baseUrl, String apiKey, ModelRequest request);
    /** Model ids the provider reports for this key, sorted. */
    java.util.List<String> listModels(String providerId, String baseUrl, String apiKey);

    /** Called when a provider is deleted or its key changes, to drop cached clients. */
    default void evict(String providerId) {}
}
