package dev.aiw.modelgateway;

interface ModelAdapter {
    ProviderKind kind();

    AdapterResult call(String providerId, String baseUrl, String apiKey, ModelRequest request);

    /** Called when a provider is deleted or its key changes, to drop cached clients. */
    default void evict(String providerId) {}
}
