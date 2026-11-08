package dev.aiw.projects;

import dev.aiw.access.WorkspaceAccess;
import dev.aiw.access.WorkspaceRole;
import dev.aiw.shared.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
class ProjectController {
    private final WorkspaceAccess access;
    private final ProjectService projects;

    ProjectController(WorkspaceAccess access, ProjectService projects) {
        this.access = access;
        this.projects = projects;
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/projects")
    List<Project> list(@PathVariable String workspaceId) {
        access.require(workspaceId, WorkspaceRole.VIEWER);
        return projects.list(workspaceId);
    }

    record CreateProject(@NotBlank @Size(max = 120) String name, @Size(max = 2000) String description) {}

    @PostMapping("/api/v1/workspaces/{workspaceId}/projects")
    @ResponseStatus(HttpStatus.CREATED)
    Project create(@PathVariable String workspaceId, @Valid @RequestBody CreateProject body) {
        String userId = access.require(workspaceId, WorkspaceRole.MEMBER);
        return projects.create(workspaceId, userId, body.name(), body.description());
    }

    @GetMapping("/api/v1/projects/{projectId}")
    Project get(@PathVariable String projectId) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return projects.find(projectId).orElseThrow(() -> ApiException.notFound("Project"));
    }

    @GetMapping("/api/v1/projects/{projectId}/documents")
    List<ProjectDocument> documents(@PathVariable String projectId) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return projects.documents(projectId);
    }

    @GetMapping("/api/v1/projects/{projectId}/documents/{documentId}")
    ProjectDocument document(@PathVariable String projectId, @PathVariable String documentId) {
        access.requireProject(projectId, WorkspaceRole.VIEWER);
        return projects.document(projectId, documentId).orElseThrow(() -> ApiException.notFound("Document"));
    }

    record TextDocument(@NotBlank @Size(max = 200) String title,
                        @Pattern(regexp = "SPECIFICATION|TECHNICAL") String kind,
                        @NotBlank String content) {}

    /** Paste a specification as text (Markdown). */
    @PostMapping(value = "/api/v1/projects/{projectId}/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ProjectDocument addText(@PathVariable String projectId, @Valid @RequestBody TextDocument body) {
        String workspaceId = access.requireProject(projectId, WorkspaceRole.MEMBER);
        String kind = body.kind() == null ? "SPECIFICATION" : body.kind();
        return projects.addDocument(workspaceId, projectId, access.require(workspaceId, WorkspaceRole.MEMBER), kind,
                body.title(), "text/markdown", body.content());
    }

    /** Upload a .md or .txt file. */
    @PostMapping(value = "/api/v1/projects/{projectId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ProjectDocument upload(@PathVariable String projectId, @RequestPart("file") MultipartFile file,
                           @RequestParam(defaultValue = "SPECIFICATION") String kind) throws IOException {
        String workspaceId = access.requireProject(projectId, WorkspaceRole.MEMBER);
        String userId = access.require(workspaceId, WorkspaceRole.MEMBER);
        if (!kind.equals("SPECIFICATION") && !kind.equals("TECHNICAL")) {
            throw ApiException.badRequest("invalid_kind", "kind must be SPECIFICATION or TECHNICAL");
        }
        String filename = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
        String lower = filename.toLowerCase();
        String mediaType;
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) mediaType = "text/markdown";
        else if (lower.endsWith(".txt")) mediaType = "text/plain";
        else throw ApiException.badRequest("unsupported_format", "Only .md and .txt files are supported for now");
        if (file.getSize() > ProjectService.MAX_DOCUMENT_BYTES) {
            throw ApiException.badRequest("document_too_large", "Documents are limited to 2 MB");
        }
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.getBytes())).toString();
        } catch (CharacterCodingException e) {
            throw ApiException.badRequest("invalid_encoding", "The file must be UTF-8 text");
        }
        return projects.addDocument(workspaceId, projectId, userId, kind, filename, mediaType, content);
    }
}
