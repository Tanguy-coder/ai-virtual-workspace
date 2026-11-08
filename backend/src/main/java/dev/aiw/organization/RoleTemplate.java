package dev.aiw.organization;

import java.util.List;

public record RoleTemplate(String id, String family, String title, Department department, List<String> skills,
                           List<String> responsibilities, boolean builtin) {}
