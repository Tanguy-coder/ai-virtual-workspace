package dev.aiw.modelgateway;

import dev.aiw.access.WorkspaceAccess;
import dev.aiw.access.WorkspaceRole;
import dev.aiw.shared.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
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

    ModelGatewayController(WorkspaceAccess access, ProviderService providers, PricingService pricing,
                           BudgetService budgets, ModelGateway gateway) {
        this.access = access;
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

    record CreateProvider(@NotNull ProviderKind kind, @NotBlank @Size(max = 100) String name,
                          @Size(max = 500) String baseUrl, @Size(max = 500) String apiKey) {}

    @PostMapping("/api/v1/workspaces/{workspaceId}/providers")
    @ResponseStatus(HttpStatus.CREATED)
    Provider create(@PathVariable String workspaceId, @Valid @RequestBody CreateProvider body) {
        String userId = access.require(workspaceId, WorkspaceRole.ADMIN);
        return providers.create(workspaceId, userId, body.kind(), body.name(), body.baseUrl(), body.apiKey());
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

    record BudgetView(BigDecimal dailyLimitUsd, BigDecimal totalLimitUsd, BudgetService.Spend spend) {}

    @GetMapping("/api/v1/projects/{projectId}/budget")
    BudgetView budget(@PathVariable String projectId) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        BudgetService.Budget b = budgets.get(projectId);
        return new BudgetView(b.dailyLimitUsd(), b.totalLimitUsd(), budgets.spend(projectId));
    }

    record SetBudget(@DecimalMin("0") BigDecimal dailyLimitUsd, @DecimalMin("0") BigDecimal totalLimitUsd) {}

    @PutMapping("/api/v1/projects/{projectId}/budget")
    BudgetView setBudget(@PathVariable String projectId, @Valid @RequestBody SetBudget body) {
        String workspaceId = access.requireProject(projectId, WorkspaceRole.ADMIN);
        budgets.set(workspaceId, projectId, body.dailyLimitUsd(), body.totalLimitUsd());
        return budget(projectId);
    }
}
