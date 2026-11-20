package dev.aiw.modelgateway;

import dev.aiw.access.WorkspaceAccess;
import dev.aiw.access.WorkspaceRole;
import dev.aiw.shared.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ModelGatewayController {
    private final WorkspaceAccess access;
    private final ProviderService providers;
    private final PricingService pricing;
    private final BudgetService budgets;
    private final ModelGateway gateway;
    private final long defaultTaskTokens;
    private final long maxTaskTokens;

    ModelGatewayController(WorkspaceAccess access, ProviderService providers, PricingService pricing,
                           BudgetService budgets, ModelGateway gateway,
                           @org.springframework.beans.factory.annotation.Value("${aiw.execution.token-budget:200000}") long defaultTaskTokens,
                           @org.springframework.beans.factory.annotation.Value("${aiw.execution.max-task-tokens:200000}") long maxTaskTokens) {
        this.access = access;
        this.maxTaskTokens = maxTaskTokens;
        this.defaultTaskTokens = defaultTaskTokens;
        this.providers = providers;
        this.pricing = pricing;
        this.budgets = budgets;
        this.gateway = gateway;
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/providers")
    List<Provider> list(@PathVariable String workspaceId) {
        access.require(workspaceId, WorkspaceRole.VIEWER);
        return providers.list(workspaceId);
    }

    /** {@code preset} (see GET /api/v1/provider-presets) fills kind, base URL and suggested models; {@code kind} alone is the custom path. */
    record CreateProvider(ProviderKind kind, @Size(max = 40) String preset, @NotBlank @Size(max = 100) String name,
                          @Size(max = 500) String baseUrl, @Size(max = 500) String apiKey,
                          @Size(max = 200) String defaultModel, @Size(max = 200) String economyModel) {}

    @PostMapping("/api/v1/workspaces/{workspaceId}/providers")
    @ResponseStatus(HttpStatus.CREATED)
    Provider create(@PathVariable String workspaceId, @Valid @RequestBody CreateProvider body) {
        String userId = access.require(workspaceId, WorkspaceRole.ADMIN);
        return providers.create(workspaceId, userId, body.kind(), body.name(), body.baseUrl(), body.apiKey(),
                body.preset(), body.defaultModel(), body.economyModel());
    }

    record Models(@Size(max = 200) String defaultModel, @Size(max = 200) String economyModel) {}

    @PutMapping("/api/v1/workspaces/{workspaceId}/providers/{providerId}/models")
    Provider models(@PathVariable String workspaceId, @PathVariable String providerId, @Valid @RequestBody Models body) {
        String userId = access.require(workspaceId, WorkspaceRole.ADMIN);
        return providers.setModels(workspaceId, userId, providerId, body.defaultModel(), body.economyModel());
    }

    @PutMapping("/api/v1/workspaces/{workspaceId}/providers/{providerId}/default")
    Provider makeDefault(@PathVariable String workspaceId, @PathVariable String providerId) {
        String userId = access.require(workspaceId, WorkspaceRole.ADMIN);
        return providers.makeDefault(workspaceId, userId, providerId);
    }

    /** Models the provider reports for the stored key (a real call to the provider, not billed). */
    @GetMapping("/api/v1/workspaces/{workspaceId}/providers/{providerId}/models")
    List<String> listModels(@PathVariable String workspaceId, @PathVariable String providerId) {
        access.require(workspaceId, WorkspaceRole.MEMBER);
        try {
            return providers.models(workspaceId, providerId);
        } catch (ModelCallException e) {
            throw ApiException.badRequest("models_unavailable", e.getMessage());
        }
    }

    @GetMapping("/api/v1/provider-presets")
    List<ProviderPresets.Preset> presets() {
        return ProviderPresets.ALL;
    }

    @DeleteMapping("/api/v1/workspaces/{workspaceId}/providers/{providerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String workspaceId, @PathVariable String providerId) {
        String userId = access.require(workspaceId, WorkspaceRole.ADMIN);
        providers.delete(workspaceId, userId, providerId);
    }

    record TestRequest(@NotBlank @Size(max = 200) String model) {}

    record TestResult(boolean ok, String reply, String servedModel, long inputTokens, long outputTokens,
                      long latencyMs, String costUsd, String error) {}

    @PostMapping("/api/v1/workspaces/{workspaceId}/providers/{providerId}/test")
    TestResult test(@PathVariable String workspaceId, @PathVariable String providerId,
                    @Valid @RequestBody TestRequest body) {
        access.require(workspaceId, WorkspaceRole.ADMIN);
        try {
            ModelResult r = gateway.test(workspaceId, providerId, body.model().trim());
            return new TestResult(!r.refused(), r.text(), r.servedModel(), r.inputTokens(), r.outputTokens(),
                    r.latencyMs(), r.costUsd() == null ? null : r.costUsd().toPlainString(), null);
        } catch (ModelCallException e) {
            return new TestResult(false, null, null, 0, 0, 0, null, e.getMessage());
        }
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/model-prices")
    List<PricingService.Price> prices(@PathVariable String workspaceId) {
        access.require(workspaceId, WorkspaceRole.VIEWER);
        return pricing.list(workspaceId);
    }

    record SetPrice(@NotNull ProviderKind providerKind, @NotBlank @Size(max = 200) String model,
                    @NotNull @DecimalMin("0") BigDecimal inputUsdPerMtok,
                    @NotNull @DecimalMin("0") BigDecimal outputUsdPerMtok) {}

    @PutMapping("/api/v1/workspaces/{workspaceId}/model-prices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setPrice(@PathVariable String workspaceId, @Valid @RequestBody SetPrice body) {
        access.require(workspaceId, WorkspaceRole.ADMIN);
        pricing.upsert(workspaceId, body.providerKind(), body.model().trim(), body.inputUsdPerMtok(),
                body.outputUsdPerMtok());
    }

    /** {@code taskTokenBudget}: what the project sets (null: none); {@code defaultTaskTokenBudget}: the platform's. */
    /** {@code maxTaskTokenBudget}: the ceiling no task ever goes past (a task that needs more is split). */
    record BudgetView(BigDecimal dailyLimitUsd, BigDecimal totalLimitUsd, Long taskTokenBudget, long defaultTaskTokenBudget,
                      long maxTaskTokenBudget, BudgetService.Spend spend) {}

    @GetMapping("/api/v1/projects/{projectId}/budget")
    BudgetView budget(@PathVariable String projectId) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        BudgetService.Budget b = budgets.get(projectId);
        return new BudgetView(b.dailyLimitUsd(), b.totalLimitUsd(), b.taskTokenBudget(), Math.min(defaultTaskTokens, maxTaskTokens),
                maxTaskTokens, budgets.spend(projectId));
    }

    record SetBudget(@DecimalMin("0") BigDecimal dailyLimitUsd, @DecimalMin("0") BigDecimal totalLimitUsd,
                     @Min(10_000) @Max(50_000_000) Long taskTokenBudget) {}

    @PutMapping("/api/v1/projects/{projectId}/budget")
    BudgetView setBudget(@PathVariable String projectId, @Valid @RequestBody SetBudget body) {
        String workspaceId = access.requireProject(projectId, WorkspaceRole.ADMIN);
        if (body.taskTokenBudget() != null && body.taskTokenBudget() > maxTaskTokens) {
            throw dev.aiw.shared.ApiException.badRequest("over_task_ceiling", "A task never uses more than " + maxTaskTokens
                    + " tokens; the Lead splits bigger work into smaller tasks");
        }
        budgets.set(workspaceId, projectId, body.dailyLimitUsd(), body.totalLimitUsd(), body.taskTokenBudget());
        return budget(projectId);
    }
}
